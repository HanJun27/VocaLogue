package com.lingoai.controller;

import com.lingoai.config.AiConfig;
import com.lingoai.dto.response.ApiResponse;
import com.lingoai.service.ai.FasterWhisperGrpcClient;
import com.lingoai.service.ai.WhisperService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Whisper 语音识别控制器
 * 提供音频文件转文字接口
 * 
 * 优先使用本地 Faster-Whisper (gRPC)，不可用时回退到云端 Whisper API
 * 
 * 来源参考：everyone-can-use-english 的 use-transcribe.tsx 中 transcribeByOpenAi() 实现
 */
@RestController
@RequestMapping("/api/asr")
@RequiredArgsConstructor
@Slf4j
public class WhisperController {

    private final WhisperService whisperService;
    private final FasterWhisperGrpcClient fasterWhisperGrpcClient;
    private final AiConfig aiConfig;

    /**
     * 转录音频文件
     * POST /api/asr/transcribe
     * 
     * @param file 音频文件（支持 mp3, wav, m4a, ogg, webm 等格式）
     * @param language 语言代码（可选，如 "en", "zh"，为空则自动检测）
     * @return 转录结果
     */
    @PostMapping("/transcribe")
    public ResponseEntity<ApiResponse<Map<String, Object>>> transcribe(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "language", required = false) String language) {
        
        log.info("Received transcription request: filename={}, size={}, language={}",
                file.getOriginalFilename(), file.getSize(), language);
        
        // 验证文件
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.error("音频文件不能为空"));
        }

        // 优先使用本地 Faster-Whisper (gRPC)
        if (useLocalFasterWhisper()) {
            try {
                return transcribeWithLocalFasterWhisper(file, language);
            } catch (Exception e) {
                log.warn("本地 Faster-Whisper 转录失败，回退到云端 Whisper: {}", e.getMessage());
            }
        }

        // 回退：云端 Whisper API
        return transcribeWithCloudWhisper(file, language);
    }

    /**
     * 判断是否使用本地 Faster-Whisper
     * 同时做一次快速健康检查确认服务真正可用
     */
    private boolean useLocalFasterWhisper() {
        try {
            String engine = aiConfig.getAsr().getDefaultEngine();
            if (!"faster-whisper".equals(engine) || !fasterWhisperGrpcClient.isReady()) {
                return false;
            }
            // 实际检查 gRPC 服务是否可达
            var status = fasterWhisperGrpcClient.getStatus();
            boolean loaded = status != null && status.getModelLoaded();
            log.info("Faster-Whisper 状态: modelLoaded={}, device={}, model={}",
                    loaded, status.getDevice(), status.getCurrentModel());
            return loaded;
        } catch (Exception e) {
            log.warn("Faster-Whisper 健康检查失败 ({}), 将回退到云端 API", e.getMessage());
            return false;
        }
    }

    /**
     * 使用本地 Faster-Whisper 转录
     */
    private ResponseEntity<ApiResponse<Map<String, Object>>> transcribeWithLocalFasterWhisper(
            MultipartFile file, String language) throws IOException {
        
        byte[] audioData = file.getBytes();
        log.info("使用本地 Faster-Whisper 转录: {} bytes", audioData.length);

        var result = fasterWhisperGrpcClient.recognizeFile(audioData, language, true);

        if (result == null) {
            throw new IOException("gRPC 识别返回空");
        }

        log.info("本地 Faster-Whisper 转录完成: text={}", result.getText());

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "text", result.getText() != null ? result.getText() : "",
                "language", result.getLanguage() != null && !result.getLanguage().isEmpty() ? result.getLanguage() : "en",
                "duration", result.getDuration(),
                "hasTimeline", result.getSegmentsCount() > 0
        )));
    }

    /**
     * 使用云端 Whisper API 转录（原有逻辑）
     */
    private ResponseEntity<ApiResponse<Map<String, Object>>> transcribeWithCloudWhisper(
            MultipartFile file, String language) {
        
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("whisper-", ".webm");
            file.transferTo(tempFile.toFile());
            
            log.debug("Saved temp file: {}", tempFile);
            
            WhisperService.WhisperResult result = whisperService.transcribe(tempFile, language);
            
            log.info("云端 Whisper 转录完成: text length={}, duration={}",
                    result.getText() != null ? result.getText().length() : 0, result.getDuration());
            
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "text", result.getText() != null ? result.getText() : "",
                    "language", result.getLanguage() != null ? result.getLanguage() : "en",
                    "duration", result.getDuration(),
                    "hasTimeline", result.getSegments() != null && result.getSegments().length > 0
            )));
            
        } catch (IOException | InterruptedException e) {
            log.error("云端 Whisper 转录失败", e);
            return ResponseEntity.ok(ApiResponse.error("所有 ASR 引擎均不可用: "
                    + "Faster-Whisper 服务未启动，且云端 Whisper API 未配置。"
                    + "请确保已启动 ASR 服务 (cd asr-service && python -m app.server) "
                    + "或配置 OPENAI_API_KEY / DEEPSEEK_API_KEY。"));
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("Failed to delete temp file: {}", tempFile);
                }
            }
        }
    }

    /**
     * 测试 ASR 连接
     * GET /api/asr/test
     */
    @GetMapping("/test")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testConnection() {
        boolean localReady = fasterWhisperGrpcClient.isReady();
        var asrConfig = aiConfig.getAsr();
        
        String status;
        if (localReady) {
            var grpcStatus = fasterWhisperGrpcClient.getStatus();
            status = "本地 Faster-Whisper: " + grpcStatus.getCurrentModel() 
                   + " (" + grpcStatus.getDevice() + ")";
        } else {
            status = "Faster-Whisper 未连接，将使用云端 API";
        }

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "available", true,
                "engine", asrConfig.getDefaultEngine(),
                "localFasterWhisper", localReady,
                "message", "ASR 服务可用 - " + status
        )));
    }
}
