package com.lingoai.controller;

import com.lingoai.dto.request.PipelineConfigRequest;
import com.lingoai.dto.response.ApiResponse;
import com.lingoai.service.ai.AiPipelineSettings;
import com.lingoai.service.ai.ConversationPipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * AI 管线设置控制器
 * 管理用户的 ASR→LLM→TTS 管线配置
 */
@RestController
@RequestMapping("/api/settings/ai-pipeline")
@RequiredArgsConstructor
@Slf4j
public class AiSettingsController {

    private final AiPipelineSettings pipelineSettings;

    /**
     * 获取用户的管线配置
     * GET /api/settings/ai-pipeline?userId=xxx
     */
    @GetMapping
    public ResponseEntity<ApiResponse<ConversationPipelineService.PipelineConfig>> getConfig(
            @RequestParam String userId) {
        log.debug("Get AI pipeline config for user: {}", userId);
        ConversationPipelineService.PipelineConfig config =
                pipelineSettings.getUserPipelineConfig(userId);
        return ResponseEntity.ok(ApiResponse.success(config));
    }

    /**
     * 保存用户的管线配置
     * POST /api/settings/ai-pipeline
     */
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> saveConfig(
            @RequestParam String userId,
            @RequestBody PipelineConfigRequest request) {
        log.info("Save AI pipeline config for user: {}", userId);

        ConversationPipelineService.PipelineConfig config =
                ConversationPipelineService.PipelineConfig.builder()
                        .useAsr(request.isUseAsr())
                        .useTts(request.isUseTts())
                        .agentName(request.getAgentName())
                        .asrEngine(request.getAsrEngine())
                        .llmModel(request.getLlmModel())
                        .llmTemperature(request.getLlmTemperature())
                        .ttsEngine(request.getTtsEngine())
                        .ttsModel(request.getTtsModel())
                        .ttsVoice(request.getTtsVoice())
                        .build();

        pipelineSettings.saveUserPipelineConfig(userId, config);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * 获取会话级别的管线配置
     * GET /api/settings/ai-pipeline/session?sessionId=xxx
     */
    @GetMapping("/session")
    public ResponseEntity<ApiResponse<ConversationPipelineService.PipelineConfig>> getSessionConfig(
            @RequestParam String sessionId) {
        ConversationPipelineService.PipelineConfig config =
                pipelineSettings.getSessionPipelineConfig(sessionId);
        if (config == null) {
            config = ConversationPipelineService.PipelineConfig.defaultConfig();
        }
        return ResponseEntity.ok(ApiResponse.success(config));
    }

    /**
     * 保存会话级别的管线配置
     * POST /api/settings/ai-pipeline/session
     */
    @PostMapping("/session")
    public ResponseEntity<ApiResponse<Void>> saveSessionConfig(
            @RequestParam String sessionId,
            @RequestBody PipelineConfigRequest request) {
        log.info("Save session pipeline config: {}", sessionId);

        ConversationPipelineService.PipelineConfig config =
                ConversationPipelineService.PipelineConfig.builder()
                        .useAsr(request.isUseAsr())
                        .useTts(request.isUseTts())
                        .agentName(request.getAgentName())
                        .asrEngine(request.getAsrEngine())
                        .llmModel(request.getLlmModel())
                        .llmTemperature(request.getLlmTemperature())
                        .ttsEngine(request.getTtsEngine())
                        .ttsModel(request.getTtsModel())
                        .ttsVoice(request.getTtsVoice())
                        .build();

        pipelineSettings.saveSessionPipelineConfig(sessionId, config);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
