package com.lingoai.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 场景实体类
 */
@Entity
@Table(name = "scenarios")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Scenario {

    @Id
    @Column(length = 50)
    private String id;

    @Column(length = 100, nullable = false)
    private String title;

    @Column(length = 50)
    private String tag;

    @Column(length = 10)
    private String emoji;

    @Column(nullable = false)
    private Integer difficulty;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "welcome_message", columnDefinition = "TEXT")
    private String welcomeMessage;

    @Column(name = "welcome_translation", columnDefinition = "TEXT")
    private String welcomeTranslation;

}
