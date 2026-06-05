package com.lingoai.controller;

import com.lingoai.dto.response.ApiResponse;
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
import java.util.UUID;

/**
 * Whisper 语音识别控制器
 * 提供音频文件转文字接口
 * 
 * 来源参考：everyone-can-use-english 的 use-transcribe.tsx 中 transcribeByOpenAi() 实现
 */
@RestController
@RequestMapping("/api/asr")
@RequiredArgsConstructor
@Slf4j
public class WhisperController {

    private final WhisperService whisperService;

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
        
        // 检查文件类型
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("audio/")) {
            log.warn("Invalid content type: {}", contentType);
            // 仍然尝试处理，某些浏览器可能不设置正确的 content-type
        }
        
        Path tempFile = null;
        try {
            // 保存临时文件
            tempFile = Files.createTempFile("whisper-", ".webm");
            file.transferTo(tempFile.toFile());
            
            log.debug("Saved temp file: {}", tempFile);
            
            // 调用 Whisper 服务转录
            WhisperService.WhisperResult result = whisperService.transcribe(tempFile, language);
            
            log.info("Transcription completed: text length={}, duration={}",
                    result.getText() != null ? result.getText().length() : 0, result.getDuration());
            
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "text", result.getText() != null ? result.getText() : "",
                    "language", result.getLanguage() != null ? result.getLanguage() : "en",
                    "duration", result.getDuration(),
                    "hasTimeline", result.getSegments() != null && result.getSegments().length > 0
            )));
            
        } catch (IOException | InterruptedException e) {
            log.error("Transcription failed", e);
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "success", false,
                    "message", "转录失败: " + e.getMessage()
            )));
        } finally {
            // 清理临时文件
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
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "available", true,
                "message", "ASR 服务可用"
        )));
    }
}
