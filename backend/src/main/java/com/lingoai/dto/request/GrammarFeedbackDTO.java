package com.lingoai.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 语法反馈DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GrammarFeedbackDTO {

    private String original;

    private String suggested;

    private String title;

    private String explanation;

}
