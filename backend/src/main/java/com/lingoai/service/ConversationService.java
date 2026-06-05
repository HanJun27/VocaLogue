package com.lingoai.service;

import com.lingoai.dto.request.GrammarFeedbackDTO;
import com.lingoai.dto.request.PipelineConfigRequest;
import com.lingoai.dto.response.AiChatResponse;
import com.lingoai.dto.response.ConversationDTO;
import com.lingoai.dto.response.MessageDTO;
import com.lingoai.dto.response.PracticeSummaryDTO;

import java.util.List;

/**
 * 会话服务接口
 */
public interface ConversationService {

    ConversationDTO createConversation(String scenarioId, String userId,
                                       Boolean useAiPractice,
                                       PipelineConfigRequest pipelineConfig);

    MessageDTO saveMessage(String sessionId, String text, Boolean useVoice,
                           Integer pronunciationScore, GrammarFeedbackDTO grammarFeedback);

    /**
     * AI 口语陪练：发送消息并接收 AI 回复（触发 ASR→LLM→TTS 管线）
     */
    AiChatResponse sendAiPracticeMessage(String sessionId, String text,
                                          Boolean useAsr, Boolean useTts,
                                          PipelineConfigRequest pipelineConfig);

    List<MessageDTO> getConversationHistory(String sessionId);

    PracticeSummaryDTO getSummary(String sessionId);

    void endConversation(String sessionId);

}
