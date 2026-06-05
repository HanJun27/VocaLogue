package com.lingoai.dto.response;

import com.lingoai.entity.Scenario;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    public static ScenarioDTO fromEntity(Scenario scenario) {
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
