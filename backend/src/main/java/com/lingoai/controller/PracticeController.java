package com.lingoai.controller;

import com.lingoai.dto.request.AiChatRequest;
import com.lingoai.dto.response.*;
import com.lingoai.service.ai.ConversationPipelineService;
import com.lingoai.service.ai.LlmService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 口语陪练控制器
 * 提供 ASR→LLM→TTS 管线驱动的 AI 对话练习接口
 */
@RestController
@RequestMapping("/api/practice")
@RequiredArgsConstructor
@Slf4j
public class PracticeController {

    private final ConversationPipelineService pipelineService;
    private final LlmService llmService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 获取可用的 AI 角色列表
     * 来源：everyone-can-use-english AGENT_FIXTURE_AVA / AGENT_FIXTURE_ANDREW
     * GET /api/practice/agents
     */
    @GetMapping("/agents")
    public ResponseEntity<ApiResponse<List<AgentInfoDTO>>> getAgents() {
        List<ConversationPipelineService.AgentInfo> agents = pipelineService.getAvailableAgents();
        List<AgentInfoDTO> dtos = agents.stream()
                .map(a -> AgentInfoDTO.builder()
                        .name(a.getName())
                        .description(a.getDescription())
                        .language(a.getLanguage())
                        .ttsVoice(a.getTtsVoice())
                        .ttsModel(a.getTtsModel())
                        .build())
                .toList();
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    /**
     * 发送 AI 口语练习消息（触发 ASR→LLM→TTS 管线）
     * POST /api/practice/chat
     */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<AiChatResponse>> chat(
            @Valid @RequestBody AiChatRequest request) throws Exception {
        log.info("AI practice chat: sessionId={}, text={}, useAsr={}, useTts={}",
                request.getSessionId(), request.getText(), request.getUseAsr(), request.getUseTts());

        // 构建管线配置
        ConversationPipelineService.PipelineConfig.PipelineConfigBuilder configBuilder =
                ConversationPipelineService.PipelineConfig.builder();

        if (request.getPipelineConfig() != null) {
            var cfg = request.getPipelineConfig();
            configBuilder.useAsr(cfg.isUseAsr());
            configBuilder.useTts(cfg.isUseTts());
            configBuilder.agentName(cfg.getAgentName());
            configBuilder.asrEngine(cfg.getAsrEngine());
            configBuilder.llmEngine(cfg.getLlmEngine());
            configBuilder.llmModel(cfg.getLlmModel());
            configBuilder.llmApiKey(cfg.getLlmApiKey());
            configBuilder.llmBaseUrl(cfg.getLlmBaseUrl());
            configBuilder.llmTemperature(cfg.getLlmTemperature());
            configBuilder.ttsEngine(cfg.getTtsEngine());
            configBuilder.ttsModel(cfg.getTtsModel());
            configBuilder.ttsVoice(cfg.getTtsVoice());
        } else {
            configBuilder
                    .useAsr(request.getUseAsr() != null && request.getUseAsr())
                    .useTts(request.getUseTts() != null && request.getUseTts());
        }

        ConversationPipelineService.PipelineConfig config = configBuilder.build();

        // 执行管线
        ConversationPipelineService.PipelineResult result = pipelineService.executePipeline(
                request.getText(),
                List.of(),  // history 由前端管理
                config
        );

        // 构建响应
        AiChatResponse response = AiChatResponse.builder()
                .userText(result.getUserText())
                .aiResponseText(result.getAiResponseText())
                .ttsUrl(result.getTtsUrl())
                .agentName(result.getAgentName())
                .agentDescription(result.getAgentDescription())
                .pipelineConfig(AiChatResponse.PipelineConfigInfo.builder()
                        .useAsr(config.isUseAsr())
                        .useTts(config.isUseTts())
                        .asrEngine(config.getAsrEngine())
                        .llmModel(config.getLlmModel())
                        .ttsEngine(config.getTtsEngine())
                        .ttsModel(config.getTtsModel())
                        .ttsVoice(config.getTtsVoice())
                        .build())
                .build();

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 流式 AI 口语练习消息（Server-Sent Events）
     * GET /api/practice/chat/stream
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestParam String sessionId,
            @RequestParam String text,
            @RequestParam(required = false) Boolean useAsr,
            @RequestParam(required = false) Boolean useTts,
            @RequestParam(required = false) String agentName,
            @RequestParam(required = false) String llmEngine,
            @RequestParam(required = false) String llmModel,
            @RequestParam(required = false) String llmApiKey,
            @RequestParam(required = false) String llmBaseUrl,
            @RequestParam(required = false) String ttsVoice,
            @RequestParam(required = false) String ttsEngine) {

        log.info("Stream chat request: sessionId={}, text={}, agentName={}, llmEngine={}, llmModel={}",
                sessionId, text, agentName, llmEngine, llmModel);

        SseEmitter emitter = new SseEmitter(300_000L); // 5分钟超时

        executor.execute(() -> {
            try {
                // 查找 AI 角色
                ConversationPipelineService.AgentInfo agent = null;
                if (agentName != null && !agentName.isEmpty()) {
                    agent = pipelineService.getAvailableAgents().stream()
                            .filter(a -> a.getName().equalsIgnoreCase(agentName))
                            .findFirst()
                            .orElse(null);
                }

                List<Map<String, String>> messages = new ArrayList<>();
                messages.add(Map.of("role", "user", "content", text));

                String engine = llmEngine != null ? llmEngine : "openai";
                String model = llmModel != null ? llmModel : "gpt-4o";

                log.info("Calling LLM stream: engine={}, model={}", engine, model);

                // 使用 Consumer 回调实现真正的逐行流式发送
                llmService.streamChat(
                        messages,
                        agent != null ? agent.getSystemPrompt() : null,
                        model,
                        agent != null ? agent.getTemperature() : 0.8,
                        llmApiKey,
                        llmBaseUrl,
                        engine,
                        line -> {
                            try {
                                if (line.startsWith("data: ")) {
                                    String jsonContent = line.substring(6).trim();
                                    if ("[DONE]".equals(jsonContent)) {
                                        return;
                                    }
                                    emitter.send(SseEmitter.event()
                                            .name("message")
                                            .data(jsonContent));
                                }
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to send SSE event", e);
                            }
                        }
                );

                emitter.complete();
            } catch (Exception e) {
                log.error("Stream chat failed", e);
                if (e instanceof RuntimeException && e.getCause() instanceof IOException) {
                    log.warn("Client disconnected, stream cancelled");
                } else {
                    try {
                        emitter.send(SseEmitter.event()
                                .name("error")
                                .data("Error: " + e.getMessage()));
                    } catch (IOException ex) {
                        log.warn("Failed to send error event", ex);
                    }
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 测试 LLM 引擎连接
     * POST /api/practice/test-llm
     */
    @PostMapping("/test-llm")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testLlmConnection(
            @RequestBody Map<String, String> params) {
        String engine = params.get("engine");
        String apiKey = params.get("apiKey");
        String baseUrl = params.get("baseUrl");
        String model = params.get("model");

        log.info("Testing LLM connection: engine={}, model={}", engine, model);

        try {
            // 调用 LLM 服务测试连接
            String response = llmService.chat(
                    List.of(Map.of("role", "user", "content", "Hello")),
                    "You are a helpful assistant.",
                    model,
                    0.8,
                    apiKey,
                    baseUrl,
                    engine
            );

            if (response != null && !response.isEmpty() && !"[No content]".equals(response)) {
                return ResponseEntity.ok(ApiResponse.success(Map.of(
                        "success", true,
                        "message", "连接成功"
                )));
            } else {
                return ResponseEntity.ok(ApiResponse.success(Map.of(
                        "success", false,
                        "message", "连接失败：未收到有效响应"
                )));
            }
        } catch (Exception e) {
            log.error("LLM connection test failed", e);
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "success", false,
                    "message", "连接失败：" + e.getMessage()
            )));
        }
    }
}
