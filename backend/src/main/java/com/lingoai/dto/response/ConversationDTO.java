package com.lingoai.dto.response;

import com.lingoai.entity.Conversation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话响应DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationDTO {

    private String sessionId;

    private String userId;

    private String scenarioId;

    private String scenarioTitle;

    private String scenarioEmoji;

    private String scenarioTag;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer overallScore;

    private Long messageCount;

    public static ConversationDTO fromEntity(Conversation conversation) {
        return ConversationDTO.builder()
                .sessionId(conversation.getId())
                .userId(conversation.getUserId())
                .scenarioId(conversation.getScenarioId())
                .startTime(conversation.getStartTime())
                .endTime(conversation.getEndTime())
                .overallScore(conversation.getOverallScore())
                .build();
    }

}
