package com.lingoai.dto.response;

import com.lingoai.entity.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 消息响应DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageDTO {

    private String id;

    private String conversationId;

    private String role;

    private String text;

    private String translation;

    private Integer pronunciationScore;

    private Object grammarFeedback;

    private LocalDateTime timestamp;

    public static MessageDTO fromEntity(Message message) {
        return MessageDTO.builder()
                .id(message.getId())
                .conversationId(message.getConversationId())
                .role(message.getRole())
                .text(message.getText())
                .translation(message.getTranslation())
                .pronunciationScore(message.getPronunciationScore())
                .timestamp(message.getTimestamp())
                .build();
    }

}
