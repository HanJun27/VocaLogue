package com.lingoai.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingoai.dto.request.GrammarFeedbackDTO;
import com.lingoai.dto.request.PipelineConfigRequest;
import com.lingoai.dto.response.AiChatResponse;
import com.lingoai.dto.response.ConversationDTO;
import com.lingoai.dto.response.MessageDTO;
import com.lingoai.dto.response.PracticeSummaryDTO;
import com.lingoai.entity.Conversation;
import com.lingoai.entity.Message;
import com.lingoai.entity.Scenario;
import com.lingoai.repository.ConversationRepository;
import com.lingoai.repository.MessageRepository;
import com.lingoai.repository.ScenarioRepository;
import com.lingoai.service.ConversationService;
import com.lingoai.service.ai.AiPipelineSettings;
import com.lingoai.service.ai.ConversationPipelineService;
import com.lingoai.service.ai.EvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 会话服务实现类
 * 已集成 ASR→LLM→TTS 管线用于 AI 口语陪练
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationServiceImpl implements ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ScenarioRepository scenarioRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ConversationPipelineService pipelineService;
    private final AiPipelineSettings pipelineSettings;
    private final EvaluationService evaluationService;

    private static final String SESSION_PREFIX = "conv:";
    private static final String AI_MODE_PREFIX = "ai:mode:";
    private static final int SESSION_TTL_SECONDS = 24 * 60 * 60; // 24小时

    @Override
    @Transactional
    public ConversationDTO createConversation(String scenarioId, String userId,
                                              Boolean useAiPractice,
                                              PipelineConfigRequest pipelineConfig) {
        log.info("[createConversation] 收到请求: scenarioId={}, userId={}, useAiPractice={}, pipelineConfig={}",
                scenarioId, userId, useAiPractice, pipelineConfig != null ? "传入" : "null");

        String sessionId = UUID.randomUUID().toString();

        Conversation conversation = Conversation.builder()
                .id(sessionId)
                .userId(userId)
                .scenarioId(scenarioId)
                .startTime(LocalDateTime.now())
                .build();

        conversationRepository.save(conversation);

        // 存入Redis
        redisTemplate.opsForValue().set(SESSION_PREFIX + sessionId, scenarioId, SESSION_TTL_SECONDS);

        // 如果启用 AI 口语陪练模式，保存管线配置
        if (Boolean.TRUE.equals(useAiPractice)) {
            redisTemplate.opsForValue().set(AI_MODE_PREFIX + sessionId, "true", SESSION_TTL_SECONDS);

            if (pipelineConfig != null) {
                log.info("[createConversation] 保存管线配置到Redis: sessionId={}, engine={}, model={}, apiKey={}...",
                        sessionId, pipelineConfig.getLlmEngine(), pipelineConfig.getLlmModel(),
                        pipelineConfig.getLlmApiKey() != null && pipelineConfig.getLlmApiKey().length() > 8
                                ? pipelineConfig.getLlmApiKey().substring(0, 8) : "N/A");
                // 将会话级配置写入 Redis（含 API Key，供评测阶段使用）
                var config = ConversationPipelineService.PipelineConfig.builder()
                        .useAsr(pipelineConfig.isUseAsr())
                        .useTts(pipelineConfig.isUseTts())
                        .agentName(pipelineConfig.getAgentName())
                        .asrEngine(pipelineConfig.getAsrEngine())
                        .llmEngine(pipelineConfig.getLlmEngine())
                        .llmModel(pipelineConfig.getLlmModel())
                        .llmApiKey(pipelineConfig.getLlmApiKey())
                        .llmBaseUrl(pipelineConfig.getLlmBaseUrl())
                        .llmTemperature(pipelineConfig.getLlmTemperature())
                        .ttsEngine(pipelineConfig.getTtsEngine())
                        .ttsModel(pipelineConfig.getTtsModel())
                        .ttsVoice(pipelineConfig.getTtsVoice())
                        .build();
                pipelineSettings.saveSessionPipelineConfig(sessionId, config);
            }
        }

        return ConversationDTO.fromEntity(conversation);
    }

    @Override
    @Transactional
    public MessageDTO saveMessage(String sessionId, String text, Boolean useVoice,
                                  Integer pronunciationScore, GrammarFeedbackDTO grammarFeedback) {
        log.debug("保存消息, sessionId={}, role=user", sessionId);

        // 验证会话存在
        Conversation conversation = conversationRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("会话不存在: " + sessionId));

        String feedbackJson = null;
        if (grammarFeedback != null) {
            try {
                feedbackJson = objectMapper.writeValueAsString(grammarFeedback);
            } catch (JsonProcessingException e) {
                log.warn("序列化语法反馈失败", e);
            }
        }

        Message message = Message.builder()
                .id(UUID.randomUUID().toString())
                .conversationId(sessionId)
                .role("user")
                .text(text)
                .pronunciationScore(pronunciationScore)
                .grammarFeedback(feedbackJson)
                .timestamp(LocalDateTime.now())
                .build();

        messageRepository.save(message);

        return MessageDTO.fromEntity(message);
    }

    @Override
    public AiChatResponse sendAiPracticeMessage(String sessionId, String text,
                                                  Boolean useAsr, Boolean useTts,
                                                  PipelineConfigRequest pipelineConfigReq) {
        log.debug("AI practice message: sessionId={}, text={}", sessionId, text);

        // 验证会话存在
        conversationRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("会话不存在: " + sessionId));

        // 先保存用户消息
        saveMessage(sessionId, text, useAsr, null, null);

        try {
            // 获取管线配置
            ConversationPipelineService.PipelineConfig config;
            if (pipelineConfigReq != null) {
                config = ConversationPipelineService.PipelineConfig.builder()
                        .useAsr(pipelineConfigReq.isUseAsr())
                        .useTts(pipelineConfigReq.isUseTts())
                        .agentName(pipelineConfigReq.getAgentName())
                        .asrEngine(pipelineConfigReq.getAsrEngine())
                        .llmEngine(pipelineConfigReq.getLlmEngine())
                        .llmModel(pipelineConfigReq.getLlmModel())
                        .llmApiKey(pipelineConfigReq.getLlmApiKey())
                        .llmBaseUrl(pipelineConfigReq.getLlmBaseUrl())
                        .llmTemperature(pipelineConfigReq.getLlmTemperature())
                        .ttsEngine(pipelineConfigReq.getTtsEngine())
                        .ttsModel(pipelineConfigReq.getTtsModel())
                        .ttsVoice(pipelineConfigReq.getTtsVoice())
                        .build();
                // 保存到 Redis 以供后续评测（getSummary）使用
                pipelineSettings.saveSessionPipelineConfig(sessionId, config);
            } else {
                // 尝试从 Redis 获取会话配置
                config = pipelineSettings.getSessionPipelineConfig(sessionId);
                if (config == null) {
                    config = ConversationPipelineService.PipelineConfig.builder()
                            .useAsr(useAsr != null && useAsr)
                            .useTts(useTts != null && useTts)
                            .build();
                }
            }

            // 获取对话历史
            List<com.lingoai.entity.Message> historyEntities =
                    messageRepository.findByConversationIdOrderByTimestampAsc(sessionId);
            List<java.util.Map<String, String>> history = historyEntities.stream()
                    .map(msg -> java.util.Map.of(
                            "role", msg.getRole(),
                            "content", msg.getText() != null ? msg.getText() : ""
                    ))
                    .collect(Collectors.toList());

            // 执行管线
            ConversationPipelineService.PipelineResult result = pipelineService.executePipeline(
                    text, history, config
            );

            // 保存 AI 回复消息
            Message aiMessage = Message.builder()
                    .id(UUID.randomUUID().toString())
                    .conversationId(sessionId)
                    .role("assistant")
                    .text(result.getAiResponseText())
                    .timestamp(LocalDateTime.now())
                    .build();
            messageRepository.save(aiMessage);

            // 构建返回
            return AiChatResponse.builder()
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

        } catch (Exception e) {
            log.error("AI practice failed: sessionId={}", sessionId, e);
            throw new RuntimeException("AI 回复失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<MessageDTO> getConversationHistory(String sessionId) {
        log.debug("获取对话历史, sessionId={}", sessionId);

        // 验证会话存在
        conversationRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("会话不存在: " + sessionId));

        return messageRepository.findByConversationIdOrderByTimestampAsc(sessionId)
                .stream()
                .map(MessageDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    public PracticeSummaryDTO getSummary(String sessionId) {
        return getSummary(sessionId, null, null, null, null);
    }

    @Override
    public PracticeSummaryDTO getSummary(String sessionId,
                                         String llmEngine,
                                         String llmModel,
                                         String llmApiKey,
                                         String llmBaseUrl) {
        log.debug("生成练习总结, sessionId={}", sessionId);

        Conversation conversation = conversationRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("会话不存在: " + sessionId));

        // 1. 如果已经缓存了 summaryData，直接从缓存读取（避免重复调用 LLM）
        if (conversation.getSummaryData() != null && !conversation.getSummaryData().isEmpty()) {
            try {
                PracticeSummaryDTO cached = objectMapper.readValue(
                        conversation.getSummaryData(), PracticeSummaryDTO.class);
                log.info("[getSummary] 从缓存读取总结: sessionId={}, overallScore={}",
                        sessionId, cached.getOverallScore());
                return cached;
            } catch (JsonProcessingException e) {
                log.warn("[getSummary] 缓存反序列化失败，重新计算: {}", e.getMessage());
                // 继续重新计算
            }
        }

        List<Message> messages = messageRepository.findByConversationIdOrderByTimestampAsc(sessionId);

        // 获取场景信息（用于 LLM 上下文理解）
        String scenarioTitle = "";
        if (conversation.getScenarioId() != null) {
            java.util.Optional<Scenario> scenarioOpt = scenarioRepository.findById(conversation.getScenarioId());
            if (scenarioOpt.isPresent()) {
                scenarioTitle = scenarioOpt.get().getTitle();
            }
        }

        try {
            // 1. 将对话记录格式化为 LLM 可读的文本
            List<java.util.Map<String, String>> conversationHistory = messages.stream()
                    .map(msg -> java.util.Map.of(
                            "role", "assistant".equals(msg.getRole()) ? "ai" : msg.getRole(),
                            "content", msg.getText() != null ? msg.getText() : ""
                    ))
                    .collect(Collectors.toList());

            String conversationText = EvaluationService.formatConversation(conversationHistory);

            // 2. 获取管线配置（用于确定使用哪个 LLM 引擎/模型）
            //    优先级：前端传入参数 > Redis 会话配置 > Redis 用户配置
            ConversationPipelineService.PipelineConfig pipelineConfig = pipelineSettings.getSessionPipelineConfig(sessionId);
            log.info("[getSummary] Redis读取结果: pipelineConfig={}, engine={}, model={}, apiKey={}...",
                    pipelineConfig != null ? "存在" : "null",
                    pipelineConfig != null ? pipelineConfig.getLlmEngine() : "N/A",
                    pipelineConfig != null ? pipelineConfig.getLlmModel() : "N/A",
                    pipelineConfig != null && pipelineConfig.getLlmApiKey() != null && pipelineConfig.getLlmApiKey().length() > 8
                            ? pipelineConfig.getLlmApiKey().substring(0, 8) : "N/A");
            if (pipelineConfig == null && conversation.getUserId() != null) {
                pipelineConfig = pipelineSettings.getUserPipelineConfig(conversation.getUserId());
                log.info("[getSummary] 使用用户级配置: engine={}", pipelineConfig != null ? pipelineConfig.getLlmEngine() : "N/A");
            }
            // 如果有前端传入配置（handleEndConversation 中传入），覆盖 Redis 值
            String effectiveEngine = (llmEngine != null && !llmEngine.isEmpty()) ? llmEngine
                    : (pipelineConfig != null ? pipelineConfig.getLlmEngine() : null);
            String effectiveModel = (llmModel != null && !llmModel.isEmpty()) ? llmModel
                    : (pipelineConfig != null ? pipelineConfig.getLlmModel() : null);
            String effectiveApiKey = (llmApiKey != null && !llmApiKey.isEmpty()) ? llmApiKey
                    : (pipelineConfig != null ? pipelineConfig.getLlmApiKey() : null);
            String effectiveBaseUrl = (llmBaseUrl != null && !llmBaseUrl.isEmpty()) ? llmBaseUrl
                    : (pipelineConfig != null ? pipelineConfig.getLlmBaseUrl() : null);
            log.info("[getSummary] 最终LLM配置: engine={}, model={}, apiKey={}...",
                    effectiveEngine, effectiveModel,
                    effectiveApiKey != null && effectiveApiKey.length() > 8 ? effectiveApiKey.substring(0, 8) : "N/A");

            // 3. 调用 LLM 评估
            PracticeSummaryDTO result = evaluationService.evaluate(
                    conversationText,
                    scenarioTitle,
                    effectiveEngine,
                    effectiveModel,
                    effectiveApiKey,
                    effectiveBaseUrl
            );

            log.info("[getSummary] LLM 评估完成: sessionId={}, overallScore={}", sessionId, result.getOverallScore());
            return result;

        } catch (Exception e) {
            log.error("[getSummary] LLM 评估失败，回退到规则引擎: sessionId={}, error={}", sessionId, e.getMessage());

            // === 回退方案：使用规则引擎计算分数 ===
            List<Integer> pronunciationScores = new ArrayList<>();
            List<PracticeSummaryDTO.ErrorItemDTO> errors = new ArrayList<>();

            for (Message msg : messages) {
                if ("user".equals(msg.getRole())) {
                    if (msg.getPronunciationScore() != null) {
                        pronunciationScores.add(msg.getPronunciationScore());
                    }
                    if (msg.getGrammarFeedback() != null) {
                        try {
                            GrammarFeedbackDTO feedback = objectMapper.readValue(
                                    msg.getGrammarFeedback(), GrammarFeedbackDTO.class);
                            errors.add(PracticeSummaryDTO.ErrorItemDTO.builder()
                                    .original(feedback.getOriginal())
                                    .corrected(feedback.getSuggested())
                                    .type(feedback.getTitle())
                                    .build());
                        } catch (JsonProcessingException ex) {
                            log.warn("解析语法反馈失败", ex);
                        }
                    }
                }
            }

            int pronunciationAvg = pronunciationScores.isEmpty() ? 80 :
                    pronunciationScores.stream().mapToInt(Integer::intValue).sum() / pronunciationScores.size();
            int grammarAvg = 78;
            int fluencyAvg = 90;
            int vocabularyAvg = 82;
            int interactivityAvg = (int) (messageRepository.countByConversationIdAndRole(sessionId, "user") * 10);
            int overallScore = (pronunciationAvg + grammarAvg + fluencyAvg + vocabularyAvg + interactivityAvg) / 5;

            return PracticeSummaryDTO.builder()
                    .overallScore(overallScore)
                    .dimensions(PracticeSummaryDTO.DimensionsDTO.builder()
                            .pronunciation(PracticeSummaryDTO.DimensionDetail.builder()
                                    .score(pronunciationAvg).evaluation("基于规则引擎的粗略评估。").build())
                            .grammar(PracticeSummaryDTO.DimensionDetail.builder()
                                    .score(grammarAvg).evaluation("基于规则引擎的粗略评估。").build())
                            .fluency(PracticeSummaryDTO.DimensionDetail.builder()
                                    .score(fluencyAvg).evaluation("基于规则引擎的粗略评估。").build())
                            .vocabulary(PracticeSummaryDTO.DimensionDetail.builder()
                                    .score(vocabularyAvg).evaluation("基于规则引擎的粗略评估。").build())
                            .interactivity(PracticeSummaryDTO.DimensionDetail.builder()
                                    .score(interactivityAvg).evaluation("基于规则引擎的粗略评估。").build())
                            .build())
                    .errors(errors)
                    .suggestions(List.of(
                            PracticeSummaryDTO.SuggestionDTO.builder()
                                    .title("注意时态的连贯一致性")
                                    .description("在表达已发生的事情时，注意动词过去式的变化规则。建议进行专项的时态转换练习。")
                                    .build(),
                            PracticeSummaryDTO.SuggestionDTO.builder()
                                    .title("丰富句型逻辑连接词")
                                    .description("避免重复使用简单的连接词。尝试使用 'furthermore', 'moreover', 'nevertheless' 等过渡词来增强逻辑层次。")
                                    .build(),
                            PracticeSummaryDTO.SuggestionDTO.builder()
                                    .title("保持稳步调，注重连读练习")
                                    .description("注意辅元音连读现象，如 'depends on' 等常见搭配的自然过渡。")
                                    .build()
                    ))
                    .build();
        }
    }

    @Override
    @Transactional
    public PracticeSummaryDTO endConversation(String sessionId) {
        return endConversation(sessionId, null, null, null, null);
    }

    @Override
    @Transactional
    public PracticeSummaryDTO endConversation(String sessionId,
                                              String llmEngine,
                                              String llmModel,
                                              String llmApiKey,
                                              String llmBaseUrl) {
        log.debug("结束会话, sessionId={}", sessionId);

        Conversation conversation = conversationRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("会话不存在: " + sessionId));

        // 生成总结（传入 LLM 配置参数，覆盖 Redis 值）
        PracticeSummaryDTO summary = getSummary(sessionId, llmEngine, llmModel, llmApiKey, llmBaseUrl);
        conversation.setEndTime(LocalDateTime.now());
        conversation.setOverallScore(summary.getOverallScore());

        try {
            conversation.setSummaryData(objectMapper.writeValueAsString(summary));
        } catch (JsonProcessingException e) {
            log.warn("序列化总结失败", e);
        }

        conversationRepository.save(conversation);

        // 从Redis删除
        redisTemplate.delete(SESSION_PREFIX + sessionId);
        redisTemplate.delete(AI_MODE_PREFIX + sessionId);

        return summary;
    }

    @Override
    public List<ConversationDTO> getUserConversations(String userId) {
        log.debug("获取用户会话列表: userId={}", userId);

        List<Conversation> conversations = conversationRepository.findByUserIdOrderByStartTimeDesc(userId);

        return conversations.stream().map(conv -> {
            ConversationDTO dto = ConversationDTO.fromEntity(conv);

            // 补充场景信息
            scenarioRepository.findById(conv.getScenarioId()).ifPresent(scenario -> {
                dto.setScenarioTitle(scenario.getTitle());
                dto.setScenarioEmoji(scenario.getEmoji());
                dto.setScenarioTag(scenario.getTag());
            });

            // 统计消息数
            long msgCount = messageRepository.countByConversationId(conv.getId());
            dto.setMessageCount(msgCount);

            return dto;
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteConversation(String sessionId) {
        log.debug("删除会话及其消息: sessionId={}", sessionId);

        Conversation conversation = conversationRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("会话不存在: " + sessionId));

        // 先删除所有关联的消息
        List<Message> messages = messageRepository.findByConversationIdOrderByTimestampAsc(sessionId);
        messageRepository.deleteAll(messages);

        // 删除会话
        conversationRepository.delete(conversation);

        // 清理Redis缓存
        redisTemplate.delete(SESSION_PREFIX + sessionId);
        redisTemplate.delete(AI_MODE_PREFIX + sessionId);

        log.info("会话已删除: sessionId={}", sessionId);
    }

}
