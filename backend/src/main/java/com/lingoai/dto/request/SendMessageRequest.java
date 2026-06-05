package com.lingoai.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发送消息请求DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendMessageRequest {

    @NotBlank(message = "消息文本不能为空")
    private String text;

    private Boolean useVoice = false;

    private Integer pronunciationScore;

    private GrammarFeedbackDTO grammarFeedback;

}
