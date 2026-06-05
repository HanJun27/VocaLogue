package com.lingoai.service.ai;

import com.lingoai.config.AiConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * TTS 语音合成服务
 * 来源参考：everyone-can-use-english 的 speech.ts (Speech.generate 静态方法)
 *   + tts-providers.tsx (TTS_PROVIDERS 配置)
 *
 * 支持的引擎：
 *  - openai:  OpenAI TTS (tts-1, tts-1-hd)，支持 alloy/echo/fable/onyx/nova/shimmer
 *  - enjoyai: 转发到 EnjoyAI 代理（支持 openai/tts-1 和 azure/speech）
 *  - azure:   Azure Neural Voice（数百种多语言语音）—— 预留
 */
@Slf4j
@Service
public class TtsService {

    private final OpenAiClient openAiClient;
    private final AiConfig aiConfig;

    public TtsService(OpenAiClient openAiClient, AiConfig aiConfig) {
        this.openAiClient = openAiClient;
        this.aiConfig = aiConfig;
    }

    /**
     * 生成语音并保存为文件
     * 对应 everyone-can-use-english Speech.generate()
     */
    public TtsResult generateSpeech(String text,
                                    String engine,
                                    String model,
                                    String voice,
                                    String apiKey,
                                    String baseUrl) throws IOException {
        String effectiveEngine = engine != null ? engine : aiConfig.getTts().getDefaultEngine();
        String effectiveModel = model;
        String effectiveVoice = voice;
        String effectiveApiKey = apiKey;
        String effectiveBaseUrl = baseUrl;

        // 根据引擎选择配置
        if ("enjoyai".equals(effectiveEngine)) {
            // EnjoyAI 作为代理转发
            if (effectiveModel == null) effectiveModel = "openai/tts-1";
            if (effectiveVoice == null) effectiveVoice = "alloy";
            var enjoyai = aiConfig.getTts().getEnjoyai();
            if (effectiveApiKey == null && enjoyai != null) {
                effectiveApiKey = enjoyai.getApiKey();
            }
            if (effectiveBaseUrl == null && enjoyai != null) {
                effectiveBaseUrl = enjoyai.getBaseUrl();
            }
        } else {
            // OpenAI TTS
            if (effectiveModel == null) effectiveModel = "tts-1";
            if (effectiveVoice == null) effectiveVoice = "alloy";
            var openaiConfig = aiConfig.getTts().getOpenai();
            if (effectiveApiKey == null && openaiConfig != null) {
                effectiveApiKey = openaiConfig.getApiKey();
            }
            if (effectiveBaseUrl == null && openaiConfig != null) {
                effectiveBaseUrl = openaiConfig.getBaseUrl();
            }
        }

        log.info("Generating TTS: engine={}, model={}, voice={}, text={}",
                effectiveEngine, effectiveModel, effectiveVoice, text);

        // 调用 OpenAI TTS API
        byte[] audioData = openAiClient.speechCreate(
                text, effectiveModel, effectiveVoice, effectiveApiKey, effectiveBaseUrl);

        // 保存为文件
        String storageDir = aiConfig.getAudioStoragePath() != null
                ? aiConfig.getAudioStoragePath() : "./data/audio";
        Path dirPath = Paths.get(storageDir, "tts");
        Files.createDirectories(dirPath);

        String filename = UUID.randomUUID().toString() + ".mp3";
        Path filePath = dirPath.resolve(filename);
        Files.write(filePath, audioData);

        log.info("TTS saved to: {}", filePath);

        return TtsResult.builder()
                .filePath(filePath.toString())
                .filename(filename)
                .audioData(audioData)
                .duration(audioData.length)
                .engine(effectiveEngine)
                .model(effectiveModel)
                .voice(effectiveVoice)
                .build();
    }

    /**
     * TTS 结果
     */
    @lombok.Builder
    @lombok.Data
    public static class TtsResult {
        private String filePath;
        private String filename;
        private byte[] audioData;
        private int duration;
        private String engine;
        private String model;
        private String voice;
    }
}
