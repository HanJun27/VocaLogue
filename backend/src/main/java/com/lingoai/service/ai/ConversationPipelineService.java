package com.lingoai.service.ai;

import com.lingoai.constants.AgentTemplates;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ASR→LLM→TTS 会话管线编排服务
 * 这是整个 AI 口语陪练的核心编排器
 *
 * 管线流程（用户可在设置中选择启用的阶段）：
 *   [USER VOICE INPUT]
 *       │
 *       ▼
 *   ASR Phase (可选) ── 语音转文字（豆包/Whisper/Azure）
 *       │
 *       ▼
 *   LLM Phase ── AI 对话/翻译/分析（LlmService）
 *       │
 *       ▼
 *   TTS Phase (可选) ── 文本转语音（OpenAI/Azure TTS）
 *       │
 *       ▼
 *   [RESPONSE: text + audio URL]
 *
 * 来源参考：everyone-can-use-english 的整体架构设计思路
 */
@Slf4j
@Service
public class ConversationPipelineService {

    private final LlmService llmService;
    private final TtsService ttsService;
    private final AsrService asrService;

    public ConversationPipelineService(LlmService llmService,
                                        TtsService ttsService,
                                        AsrService asrService) {
        this.llmService = llmService;
        this.ttsService = ttsService;
        this.asrService = asrService;
    }

    /**
     * Pipeline 配置（每个会话可以独立设置）
     */
    @lombok.Builder
    @lombok.Data
    public static class PipelineConfig {
        private boolean useAsr;      // 是否使用 ASR
        private boolean useTts;      // 是否使用 TTS
        private String agentName;    // AI 角色名
        private String asrEngine;    // ASR 引擎: doubao | azure | whisper
        private String llmEngine;    // LLM 引擎: openai | deepseek | glm | qianwen | doubao
        private String llmModel;     // LLM 模型
        private String llmApiKey;   // LLM API Key（可选）
        private String llmBaseUrl;  // LLM Base URL（可选）
        private Double llmTemperature; // LLM 温度
        private String ttsEngine;    // TTS 引擎: openai | enjoyai | azure
        private String ttsModel;     // TTS 模型
        private String ttsVoice;     // TTS 声音

        public static PipelineConfig defaultConfig() {
            return PipelineConfig.builder()
                    .useAsr(false)
                    .useTts(false)
                    .agentName("Ava")
                    .asrEngine("doubao")
                    .llmEngine("openai")
                    .llmModel("gpt-4o")
                    .llmTemperature(0.8)
                    .ttsEngine("openai")
                    .ttsModel("tts-1")
                    .ttsVoice("alloy")
                    .build();
        }
    }

    /**
     * Pipeline 执行结果
     */
    @lombok.Builder
    @lombok.Data
    public static class PipelineResult {
        private String userText;              // 用户文本（ASR 处理后）
        private String aiResponseText;        // AI 回复文本（LLM 生成）
        private String translatedText;        // 翻译（可选）
        private String analysisText;          // 语法分析（可选）
        private String ttsUrl;                // TTS 音频 URL（可选）
        private String agentName;             // 当前 AI 角色名
        private String agentDescription;      // 角色描述
    }

    /**
     * 执行完整管线：ASR → LLM → TTS
     *
     * @param userText       用户输入的文本（来自 ASR 或直接输入）
     * @param history        对话历史
     * @param config         管线配置
     * @return 管线执行结果
     */
    public PipelineResult executePipeline(String userText,
                                           List<Map<String, String>> history,
                                           PipelineConfig config) throws IOException {
        log.info("Executing pipeline: agent={}, useAsr={}, useTts={}",
                config.getAgentName(), config.isUseAsr(), config.isUseTts());

        // ========== Phase 1: ASR 处理 ==========
        // ASR 已在前端通过 WebSocket 完成，后端接收的是文本结果
        // 此阶段主要用于文本后处理和发音评估
        if (config.isUseAsr()) {
            log.debug("ASR phase: text={}", userText);
            // 标点恢复（ASR 输出通常没有标点）
            try {
                userText = llmService.punctuate(userText,
                        config.getLlmModel(), config.getLlmTemperature(),
                        config.getLlmApiKey(), config.getLlmBaseUrl());
                log.debug("After punctuate: {}", userText);
            } catch (Exception e) {
                log.warn("Punctuate failed, using original text: {}", e.getMessage());
            }
        }

        // ========== Phase 2: LLM 处理 ==========
        // 查找 AI 角色
        AgentTemplates agent = findAgent(config.getAgentName());

        // 构建带 system prompt 的消息列表
        List<Map<String, String>> messages = new ArrayList<>();
        messages.addAll(history);
        messages.add(Map.of("role", "user", "content", userText));

        // 调用 LLM 生成回复
        String llmEngine = config.getLlmEngine() != null ? config.getLlmEngine() : "openai";
        String aiResponse = llmService.chat(
                history,
                agent != null ? agent.getSystemPrompt() : null,
                config.getLlmModel(),
                config.getLlmTemperature() != null ? config.getLlmTemperature()
                        : (agent != null ? agent.getTemperature() : 0.8),
                config.getLlmApiKey(),
                config.getLlmBaseUrl(),
                llmEngine
        );

        log.info("LLM response: {}", aiResponse.length() > 100 ? aiResponse.substring(0, 100) + "..." : aiResponse);

        // ========== Phase 3: TTS 处理 ==========
        String ttsUrl = null;
        if (config.isUseTts() && aiResponse != null && !aiResponse.isEmpty()) {
            try {
                String ttsEngine = config.getTtsEngine() != null ? config.getTtsEngine()
                        : (agent != null ? agent.getTtsEngine() : "openai");
                String ttsModel = config.getTtsModel() != null ? config.getTtsModel()
                        : (agent != null ? agent.getTtsModel() : "tts-1");
                String ttsVoice = config.getTtsVoice() != null ? config.getTtsVoice()
                        : (agent != null ? agent.getTtsVoice() : "alloy");

                TtsService.TtsResult ttsResult = ttsService.generateSpeech(
                        aiResponse, ttsEngine, ttsModel, ttsVoice, null, null);
                ttsUrl = "/api/audio/tts/" + ttsResult.getFilename();
                log.info("TTS generated: {}", ttsUrl);
            } catch (Exception e) {
                log.warn("TTS generation failed: {}", e.getMessage());
            }
        }

        // ========== 构建结果 ==========
        return PipelineResult.builder()
                .userText(userText)
                .aiResponseText(aiResponse)
                .ttsUrl(ttsUrl)
                .agentName(agent != null ? agent.getName() : config.getAgentName())
                .agentDescription(agent != null ? agent.getDescription() : "")
                .build();
    }

    /**
     * 查找 AI 角色模板
     */
    private AgentTemplates findAgent(String agentName) {
        if (agentName == null) return null;
        for (AgentTemplates agent : AgentTemplates.values()) {
            if (agent.getName().equalsIgnoreCase(agentName)) {
                return agent;
            }
        }
        return null;
    }

    /**
     * 获取所有可用的 AI 角色
     */
    public List<AgentInfo> getAvailableAgents() {
        List<AgentInfo> agents = new ArrayList<>();
        for (AgentTemplates agent : AgentTemplates.values()) {
            agents.add(AgentInfo.builder()
                    .name(agent.getName())
                    .description(agent.getDescription())
                    .language(agent.getLanguage())
                    .systemPrompt(agent.getSystemPrompt())
                    .temperature(agent.getTemperature())
                    .ttsVoice(agent.getTtsVoice())
                    .ttsModel(agent.getTtsModel())
                    .build());
        }
        return agents;
    }

    @lombok.Builder
    @lombok.Data
    public static class AgentInfo {
        private String name;
        private String description;
        private String language;
        private String systemPrompt;
        private Double temperature;
        private String ttsVoice;
        private String ttsModel;
    }
}
