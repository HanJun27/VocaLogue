package com.lingoai.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管线配置请求 DTO
 * 用户在设置界面选择 ASR→LLM→TTS 开关的配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineConfigRequest {
    private boolean useAsr;
    private boolean useTts;
    private String agentName;
    private String asrEngine;
    private String llmEngine;       // LLM 引擎类型 openai/deepseek/glm/qianwen/doubao
    private String llmModel;
    private String llmApiKey;       // LLM API Key（前端直接传递，不持久化）
    private String llmBaseUrl;      // LLM Base URL（前端直接传递，不持久化）
    private Double llmTemperature;
    private String ttsEngine;
    private String ttsModel;
    private String ttsVoice;
}
