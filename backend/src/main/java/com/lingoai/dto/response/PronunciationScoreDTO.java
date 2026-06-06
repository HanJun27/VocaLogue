package com.lingoai.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发音评测结果 DTO
 * 来源参考：everyone-can-use-english azure-speech-sdk.ts pronunciationAssessment()
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PronunciationScoreDTO {
    private int accuracy;
    private int fluency;
    private int grammar;
    private int overall;
    private String feedbackSummary;
    private String phonemeLevel;
    private String wordLevel;
    private String detailResult;
}
