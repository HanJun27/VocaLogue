package com.lingoai.controller;

import com.lingoai.dto.request.CreateConversationRequest;
import com.lingoai.dto.request.SendMessageRequest;
import com.lingoai.dto.response.ApiResponse;
import com.lingoai.dto.response.ConversationDTO;
import com.lingoai.dto.response.MessageDTO;
import com.lingoai.dto.response.PracticeSummaryDTO;
import com.lingoai.service.ConversationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话管理控制器
 */
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
@Slf4j
public class ConversationController {

    private final ConversationService conversationService;

    @PostMapping
    public ResponseEntity<ApiResponse<ConversationDTO>> createConversation(
            @Valid @RequestBody CreateConversationRequest request) {
        log.info("创建会话: scenarioId={}, userId={}", request.getScenarioId(), request.getUserId());
        ConversationDTO conversation = conversationService.createConversation(
                request.getScenarioId(), request.getUserId());
        return ResponseEntity.ok(ApiResponse.success(conversation));
    }

    @PostMapping("/{sessionId}/messages")
    public ResponseEntity<ApiResponse<MessageDTO>> sendMessage(
            @PathVariable String sessionId,
            @Valid @RequestBody SendMessageRequest request) {
        log.info("保存消息: sessionId={}", sessionId);
        MessageDTO message = conversationService.saveMessage(
                sessionId,
                request.getText(),
                request.getUseVoice(),
                request.getPronunciationScore(),
                request.getGrammarFeedback()
        );
        return ResponseEntity.ok(ApiResponse.success(message));
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<ApiResponse<List<MessageDTO>>> getConversationHistory(
            @PathVariable String sessionId) {
        log.info("获取对话历史: sessionId={}", sessionId);
        List<MessageDTO> messages = conversationService.getConversationHistory(sessionId);
        return ResponseEntity.ok(ApiResponse.success(messages));
    }

    @GetMapping("/{sessionId}/summary")
    public ResponseEntity<ApiResponse<PracticeSummaryDTO>> getSummary(
            @PathVariable String sessionId) {
        log.info("获取练习总结: sessionId={}", sessionId);
        PracticeSummaryDTO summary = conversationService.getSummary(sessionId);
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<ApiResponse<Void>> endConversation(@PathVariable String sessionId) {
        log.info("结束会话: sessionId={}", sessionId);
        conversationService.endConversation(sessionId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

}
