package com.lingoai.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发音评分DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PronunciationScoreDTO {

    private Integer accuracy;

    private Integer fluency;

    private Integer grammar;

    private Integer overall;

    private String feedbackSummary;

}
