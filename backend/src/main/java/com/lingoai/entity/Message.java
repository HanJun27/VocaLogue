package com.lingoai.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 消息实体类
 */
@Entity
@Table(name = "messages")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message {

    @Id
    @Column(length = 50)
    private String id;

    @Column(name = "conversation_id", length = 50, nullable = false)
    private String conversationId;

    @Column(length = 10, nullable = false)
    private String role;

    @Column(columnDefinition = "TEXT")
    private String text;

    @Column(columnDefinition = "TEXT")
    private String translation;

    @Column(name = "pronunciation_score")
    private Integer pronunciationScore;

    @Column(name = "grammar_feedback", columnDefinition = "TEXT")
    private String grammarFeedback;

    @Column(nullable = false)
    private LocalDateTime timestamp;

}
