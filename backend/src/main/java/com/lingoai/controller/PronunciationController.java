package com.lingoai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.lingoai.dto.response.ApiResponse;
import com.lingoai.service.ai.Wav2vec2PronunciationClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 发音评测控制器
 * <p>
 * 代理前端请求到 wav2vec2 Python 微服务。
 * 完全独立于现有评价系统，仅新增此端点即可支持前端实时发音评测。
 */
@RestController
@RequestMapping("/api/pronunciation")
@Slf4j
public class PronunciationController {

    private final Wav2vec2PronunciationClient pronunciationClient;

    public PronunciationController(Wav2vec2PronunciationClient pronunciationClient) {
        this.pronunciationClient = pronunciationClient;
    }

    /**
     * 发音评测
     * POST /api/pronunciation/evaluate
     *
     * @param file          用户录音文件 (wav/webm/opus)
     * @param referenceText 参考文本（用户想说的句子）
     * @param language      语言代码 (en/zh/ja，默认为 en)
     * @return 发音评测结果（accuracy/fluency/completeness + 词级得分）
     */
    @PostMapping("/evaluate")
    public ResponseEntity<ApiResponse<JsonNode>> evaluate(
            @RequestParam("file") MultipartFile file,
            @RequestParam("reference_text") String referenceText,
            @RequestParam(value = "language", defaultValue = "en") String language) {

        if (referenceText == null || referenceText.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "reference_text 不能为空"));
        }

        // 保存上传文件到临时目录
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("pronunciation_", ".wav");
            Files.copy(file.getInputStream(), tempFile, StandardCopyOption.REPLACE_EXISTING);

            JsonNode result = pronunciationClient.evaluate(tempFile, referenceText, language);

            return ResponseEntity.ok(ApiResponse.success(result));

        } catch (IOException e) {
            log.error("[Pronunciation] 文件处理失败: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "文件处理失败: " + e.getMessage()));
        } finally {
            // 清理临时文件
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 健康检查
     * GET /api/pronunciation/health
     */
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<String>> health() {
        return ResponseEntity.ok(ApiResponse.success("ok"));
    }
}
