package com.lingoai.controller;

import com.lingoai.dto.response.ApiResponse;
import com.lingoai.service.ai.FasterWhisperGrpcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ASR 设置控制器
 * 提供 Faster-Whisper 本地 ASR 服务的配置和管理接口
 */
@RestController
@RequestMapping("/api/asr/settings")
@RequiredArgsConstructor
@Slf4j
public class AsrSettingsController {

    private final FasterWhisperGrpcClient grpcClient;

    /**
     * 获取 ASR 服务状态
     * GET /api/asr/settings/status
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatus() {
        var status = grpcClient.getStatus();

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "modelLoaded", status.getModelLoaded(),
                "currentModel", status.getCurrentModel(),
                "gpuAvailable", status.getGpuAvailable(),
                "device", status.getDevice(),
                "message", status.getMessage(),
                "grpcReady", grpcClient.isReady()
        )));
    }

    /**
     * 更新 ASR 设置
     * POST /api/asr/settings/update
     */
    @PostMapping("/update")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateSettings(@RequestBody Map<String, Object> settings) {
        // 不传入 model 时保持当前模型（不硬编码默认 large-v2）
        String modelName;
        if (settings.containsKey("model") && settings.get("model") != null) {
            modelName = (String) settings.get("model");
        } else {
            modelName = null; // 让 gRPC 客户端获取当前模型
        }
        String device = (String) settings.getOrDefault("device", "cuda");
        int computeType = settings.containsKey("computeType") ? ((Number) settings.get("computeType")).intValue() : 2;
        boolean enableVad = (boolean) settings.getOrDefault("enableVad", true);
        int vadThresholdMs = settings.containsKey("vadThresholdMs") ? ((Number) settings.get("vadThresholdMs")).intValue() : 500;
        int windowSizeMs = settings.containsKey("windowSizeMs") ? ((Number) settings.get("windowSizeMs")).intValue() : 500;
        String language = (String) settings.getOrDefault("language", "");

        log.info("更新 ASR 设置: model={}, device={}, computeType={}, vad={}, vadThreshold={}ms, windowSize={}ms",
                modelName, device, computeType, enableVad, vadThresholdMs, windowSizeMs);

        try {
            var status = grpcClient.updateSettings(modelName, device, computeType,
                    enableVad, vadThresholdMs, windowSizeMs, language);

            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "modelLoaded", status.getModelLoaded(),
                    "currentModel", status.getCurrentModel(),
                    "gpuAvailable", status.getGpuAvailable(),
                    "device", status.getDevice(),
                    "message", status.getMessage()
            )));
        } catch (Exception e) {
            log.error("更新 ASR 设置失败: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "success", false,
                    "message", "更新设置失败: " + e.getMessage()
            )));
        }
    }

    /**
     * 获取可用模型列表
     * GET /api/asr/settings/models
     */
    @GetMapping("/models")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAvailableModels() {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "models", new String[]{"large-v3", "large-v2", "medium", "small", "base", "tiny"},
                "devices", new String[]{"cuda", "cpu"},
                "computeTypes", new String[]{"int8_float16", "float16", "float32", "int8"},
                "recommended", Map.of(
                        "model", "large-v2",
                        "device", "cuda",
                        "computeType", "int8_float16"
                )
        )));
    }
}
