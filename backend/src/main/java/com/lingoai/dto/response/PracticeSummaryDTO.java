package com.lingoai.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 练习总结DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PracticeSummaryDTO {

    private Integer overallScore;

    private DimensionsDTO dimensions;

    private List<ErrorItemDTO> errors;

    private List<String> suggestions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DimensionsDTO {
        private Integer pronunciation;
        private Integer grammar;
        private Integer fluency;
        private Integer vocabulary;
        private Integer interactivity;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorItemDTO {
        private String original;
        private String corrected;
        private String type;
    }

}
