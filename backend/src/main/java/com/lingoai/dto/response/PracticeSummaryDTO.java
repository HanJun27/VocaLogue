package com.lingoai.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 练习总结DTO
 * 由 LLM 分析完整对话记录后生成，包含五维评分、详细文本评价、语法纠正和建议
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PracticeSummaryDTO {

    /** 综合得分 (0-100) */
    private Integer overallScore;

    /** 五维能力评估 */
    private DimensionsDTO dimensions;

    /** 本轮重点语法偏误及调优分析 */
    private List<ErrorItemDTO> errors;

    /** 行动力提升建议 */
    private List<SuggestionDTO> suggestions;

    /** 五维能力评估 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DimensionsDTO {
        private DimensionDetail pronunciation;
        private DimensionDetail grammar;
        private DimensionDetail fluency;
        private DimensionDetail vocabulary;
        private DimensionDetail interactivity;
    }

    /** 单个维度的评分 + 详细文本评价 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DimensionDetail {
        /** 分数 (0-100) */
        private Integer score;
        /** 详细文本评价（例如：指出具体发音问题、语法弱项等） */
        private String evaluation;
    }

    /** 语法/表达错误纠正条目 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorItemDTO {
        /** 用户原文 */
        private String original;
        /** 纠正后的表达 */
        private String corrected;
        /** 错误类型（如：时态、介词搭配、词性区分等） */
        private String type;
    }

    /** 行动力提升建议 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SuggestionDTO {
        /** 建议标题 */
        private String title;
        /** 建议详细描述 */
        private String description;
    }
}
