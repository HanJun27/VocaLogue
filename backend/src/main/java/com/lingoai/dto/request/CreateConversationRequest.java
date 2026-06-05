package com.lingoai.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建会话请求DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateConversationRequest {

    @NotBlank(message = "场景ID不能为空")
    private String scenarioId;

    private String userId;

    // AI 口语陪练模式：启用 ASR→LLM→TTS 管线
    private Boolean useAiPractice;

    // 管线配置（可选，默认使用用户设置）
    private PipelineConfigRequest pipelineConfig;
}
