package com.lingoai.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 对话响应 DTO
 * 包含完整的 ASR→LLM→TTS 管线输出
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiChatResponse {
    private String userText;
    private String aiResponseText;
    private String translatedText;
    private String analysisText;
    private String ttsUrl;
    private String agentName;
    private String agentDescription;
    private PipelineConfigInfo pipelineConfig;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PipelineConfigInfo {
        private boolean useAsr;
        private boolean useTts;
        private String asrEngine;
        private String llmModel;
        private String ttsEngine;
        private String ttsModel;
        private String ttsVoice;
    }
}
