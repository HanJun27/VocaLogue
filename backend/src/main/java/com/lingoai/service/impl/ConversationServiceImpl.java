package com.lingoai.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingoai.dto.request.GrammarFeedbackDTO;
import com.lingoai.dto.response.ConversationDTO;
import com.lingoai.dto.response.MessageDTO;
import com.lingoai.dto.response.PracticeSummaryDTO;
import com.lingoai.entity.Conversation;
import com.lingoai.entity.Message;
import com.lingoai.repository.ConversationRepository;
import com.lingoai.repository.MessageRepository;
import com.lingoai.service.ConversationService;
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
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationServiceImpl implements ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String SESSION_PREFIX = "conv:";
    private static final int SESSION_TTL_SECONDS = 24 * 60 * 60; // 24小时

    @Override
    @Transactional
    public ConversationDTO createConversation(String scenarioId, String userId) {
        log.debug("创建会话, scenarioId={}, userId={}", scenarioId, userId);
        
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
    }

}
