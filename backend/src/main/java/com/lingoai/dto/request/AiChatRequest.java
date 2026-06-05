package com.lingoai.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * AI 对话请求 DTO
 * 前端发送一次对话交互请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiChatRequest {
    @NotBlank(message = "会话ID不能为空")
    private String sessionId;

    @NotBlank(message = "消息文本不能为空")
    private String text;

    private String userId;

    private Boolean useAsr;

    private Boolean useTts;

    // 可选的管线配置覆盖
    private PipelineConfigRequest pipelineConfig;
}
