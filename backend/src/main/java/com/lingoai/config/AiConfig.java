package com.lingoai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI 配置（映射 application.yml 中的 ai 配置节）
 * 来源参考：everyone-can-use-english 项目的 LLM/TTS/ASR 配置模式
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai")
public class AiConfig {

    private LlmConfig llm;
    private TtsConfig tts;
    private AzureConfig azure;
    private AsrConfig asr;
    private String audioStoragePath;

    @Data
    public static class LlmConfig {
        private String defaultEngine = "openai";
        private LlmProvider openai = new LlmProvider();
        private LlmProvider deepseek = new LlmProvider();  // DeepSeek - 国产大模型
        private LlmProvider glm = new LlmProvider();       // GLM 智谱 - 国产大模型
        private LlmProvider qianwen = new LlmProvider();   // 通义千问 - 国产大模型
        private LlmProvider doubao = new LlmProvider();    // 豆包 - 国产大模型
        private LlmProvider enjoyai = new LlmProvider();
        private LlmProvider ollama = new LlmProvider();
    }

    @Data
    public static class LlmProvider {
        private String apiKey;
        private String baseUrl;
        private String defaultModel;
        private Double temperature = 0.8;
    }

    @Data
    public static class TtsConfig {
        private String defaultEngine = "openai";
        private TtsProvider openai = new TtsProvider();
        private TtsProvider enjoyai = new TtsProvider();
    }

    @Data
    public static class TtsProvider {
        private String apiKey;
        private String baseUrl;
        private String defaultModel;
        private String defaultVoice;
    }

    @Data
    public static class AzureConfig {
        private String speechKey;
        private String speechRegion;
    }

    @Data
    public static class AsrConfig {
        private String defaultEngine = "doubao";
        private AsrProvider doubao = new AsrProvider();
        private AsrProvider azure = new AsrProvider();
    }

    @Data
    public static class AsrProvider {
        private String appId;
        private String accessToken;
        private String secretKey;
        private String wsUrl;
        private String speechKey;
        private String speechRegion;
    }
}
