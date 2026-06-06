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
        private LocalTtsConfig local = new LocalTtsConfig();
    }

    @Data
    public static class TtsProvider {
        private String apiKey;
        private String baseUrl;
        private String defaultModel;
        private String defaultVoice;
    }

    @Data
    public static class LocalTtsConfig {
        /** 本地 TTS 服务地址（tts-service FastAPI 地址） */
        private String baseUrl = "http://localhost:8000";
        /** 默认引擎: piper 或 edge-tts */
        private String defaultEngine = "piper";
        /** Piper 默认语音 */
        private String piperVoice = "en_US-amy-medium";
        /** Edge TTS 默认语音 */
        private String edgeVoice = "en-US-AriaNeural";
        /** 默认语速倍率 (仅 Piper) */
        private Double piperSpeed = 1.0;
        /** 默认语速调节 (仅 Edge TTS) */
        private String edgeRate = "+0%";
        /** 默认音调 (仅 Edge TTS) */
        private String edgePitch = "+0Hz";
        /** 超时时间（毫秒） */
        private int timeoutMs = 30000;
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
        private FasterWhisperProvider fasterWhisper = new FasterWhisperProvider();
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

    @Data
    public static class FasterWhisperProvider {
        private String host = "localhost";
        private int port = 50051;
        private String model = "large-v2";
        private String device = "cuda";        // cuda / cpu
        private String computeType = "int8_float16";  // int8 / float16 / int8_float16
        private boolean enableVad = true;
        private int vadThresholdMs = 500;
        private int windowSizeMs = 500;
        private String language = "";
        private String downloadRoot = "./models";
    }
}
