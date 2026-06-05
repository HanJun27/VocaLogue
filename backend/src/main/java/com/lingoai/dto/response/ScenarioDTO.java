package com.lingoai.dto.response;

import com.lingoai.entity.Scenario;
import com.lingoai.entity.ScenarioQuestion;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 场景响应DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScenarioDTO {

    private String id;

    private String title;

    private String tag;

    private String emoji;

    private Integer difficulty;

    private String description;

    private String welcomeMessage;

    private String welcomeTranslation;

    private List<QuestionDTO> questions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionDTO {
        private String text;
        private String translation;
        private List<KeywordDTO> keywords;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class KeywordDTO {
            private String phrase;
            private String suggested;
            private String explanation;
        }
    }

    public static ScenarioDTO fromEntity(Scenario scenario, List<ScenarioQuestion> questions) {
        List<QuestionDTO> questionDTOs = questions.stream()
                .map(q -> QuestionDTO.builder()
                        .text(q.getQuestionText())
                        .translation(q.getQuestionTranslation())
                        .build())
                .toList();

        return ScenarioDTO.builder()
                .id(scenario.getId())
                .title(scenario.getTitle())
                .tag(scenario.getTag())
                .emoji(scenario.getEmoji())
                .difficulty(scenario.getDifficulty())
                .description(scenario.getDescription())
                .welcomeMessage(scenario.getWelcomeMessage())
                .welcomeTranslation(scenario.getWelcomeTranslation())
                .questions(questionDTOs)
                .build();
    }

    public static ScenarioDTO fromEntitySimple(Scenario scenario) {
        return ScenarioDTO.builder()
                .id(scenario.getId())
                .title(scenario.getTitle())
                .tag(scenario.getTag())
                .emoji(scenario.getEmoji())
                .difficulty(scenario.getDifficulty())
                .description(scenario.getDescription())
                .welcomeMessage(scenario.getWelcomeMessage())
                .welcomeTranslation(scenario.getWelcomeTranslation())
                .build();
    }

}
