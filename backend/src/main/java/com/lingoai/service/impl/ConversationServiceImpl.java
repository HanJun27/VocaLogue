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

    private static final String SESSION_PREFIX = "conv:";
    private static final String AI_MODE_PREFIX = "ai:mode:";
    private static final int SESSION_TTL_SECONDS = 24 * 60 * 60; // 24小时

    @Override
    @Transactional
    public ConversationDTO createConversation(String scenarioId, String userId,
                                              Boolean useAiPractice,
                                              PipelineConfigRequest pipelineConfig) {
        log.debug("创建会话, scenarioId={}, userId={}, useAiPractice={}",
                scenarioId, userId, useAiPractice);

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

            if (pipelineConfig != null && userId != null) {
                // 将会话级配置写入 Redis
                var config = ConversationPipelineService.PipelineConfig.builder()
                        .useAsr(pipelineConfig.isUseAsr())
                        .useTts(pipelineConfig.isUseTts())
                        .agentName(pipelineConfig.getAgentName())
                        .asrEngine(pipelineConfig.getAsrEngine())
                        .llmEngine(pipelineConfig.getLlmEngine())
                        .llmModel(pipelineConfig.getLlmModel())
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
                        .llmTemperature(pipelineConfigReq.getLlmTemperature())
                        .ttsEngine(pipelineConfigReq.getTtsEngine())
                        .ttsModel(pipelineConfigReq.getTtsModel())
                        .ttsVoice(pipelineConfigReq.getTtsVoice())
                        .build();
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
        log.debug("生成练习总结, sessionId={}", sessionId);

        Conversation conversation = conversationRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("会话不存在: " + sessionId));

        List<Message> messages = messageRepository.findByConversationIdOrderByTimestampAsc(sessionId);

        // 计算各维度分数
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
                    } catch (JsonProcessingException e) {
                        log.warn("解析语法反馈失败", e);
                    }
                }
            }
        }

        // 计算平均分
        int pronunciationAvg = pronunciationScores.isEmpty() ? 0 :
                pronunciationScores.stream().mapToInt(Integer::intValue).sum() / pronunciationScores.size();

        // 模拟其他维度分数
        int grammarAvg = 78;
        int fluencyAvg = 90;
        int vocabularyAvg = 82;
        int interactivityAvg = (int) (messageRepository.countByConversationIdAndRole(sessionId, "user") * 10);

        // 计算总分
        int overallScore = (pronunciationAvg + grammarAvg + fluencyAvg + vocabularyAvg + interactivityAvg) / 5;

        // 生成建议
        List<String> suggestions = new ArrayList<>();
        if (pronunciationAvg < 80) {
            suggestions.add("继续练习发音，可以尝试跟读模仿");
        }
        if (grammarAvg < 80) {
            suggestions.add("注意语法结构，多练习句子时态");
        }
        if (fluencyAvg < 85) {
            suggestions.add("试着说得更流畅一些，减少停顿");
        }

        return PracticeSummaryDTO.builder()
                .overallScore(overallScore)
                .dimensions(PracticeSummaryDTO.DimensionsDTO.builder()
                        .pronunciation(pronunciationAvg)
                        .grammar(grammarAvg)
                        .fluency(fluencyAvg)
                        .vocabulary(vocabularyAvg)
                        .interactivity(interactivityAvg)
                        .build())
                .errors(errors)
                .suggestions(suggestions)
                .build();
    }

    @Override
    @Transactional
    public void endConversation(String sessionId) {
        log.debug("结束会话, sessionId={}", sessionId);

        Conversation conversation = conversationRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("会话不存在: " + sessionId));

        // 生成总结
        PracticeSummaryDTO summary = getSummary(sessionId);
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
