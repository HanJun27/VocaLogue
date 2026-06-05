package com.lingoai.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 场景问题实体类
 */
@Entity
@Table(name = "scenario_questions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScenarioQuestion {

    @Id
    @Column(length = 50)
    private String id;

    @Column(name = "scenario_id", length = 50, nullable = false)
    private String scenarioId;

    @Column(name = "question_text", columnDefinition = "TEXT")
    private String questionText;

    @Column(name = "question_translation", columnDefinition = "TEXT")
    private String questionTranslation;

    @Column(name = "order_index")
    private Integer orderIndex;

    @ElementCollection
    @CollectionTable(name = "scenario_question_keywords", joinColumns = @JoinColumn(name = "question_id"))
    @Column(name = "keyword")
    @Builder.Default
    private List<String> keywords = new ArrayList<>();

}
