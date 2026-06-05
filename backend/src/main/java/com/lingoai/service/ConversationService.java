package com.lingoai.service;

import com.lingoai.dto.request.GrammarFeedbackDTO;
import com.lingoai.dto.response.ConversationDTO;
import com.lingoai.dto.response.MessageDTO;
import com.lingoai.dto.response.PracticeSummaryDTO;

import java.util.List;

/**
 * 会话服务接口
 */
public interface ConversationService {

    ConversationDTO createConversation(String scenarioId, String userId);

    MessageDTO saveMessage(String sessionId, String text, Boolean useVoice, 
                           Integer pronunciationScore, GrammarFeedbackDTO grammarFeedback);

    List<MessageDTO> getConversationHistory(String sessionId);

    PracticeSummaryDTO getSummary(String sessionId);

    void endConversation(String sessionId);

}
