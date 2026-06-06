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
 *  - openai:   OpenAI TTS (tts-1, tts-1-hd)，支持 alloy/echo/fable/onyx/nova/shimmer
 *  - enjoyai:  转发到 EnjoyAI 代理（支持 openai/tts-1 和 azure/speech）
 *  - piper:    Piper 本地神经 TTS（完全离线，通过 tts-service 调用）
 *  - edge-tts: Microsoft Edge 免费在线 TTS（需网络，通过 tts-service 调用）
 */
@Slf4j
@Service
public class TtsService {

    private final OpenAiClient openAiClient;
    private final LocalTtsClient localTtsClient;
    private final AiConfig aiConfig;

    public TtsService(OpenAiClient openAiClient, LocalTtsClient localTtsClient, AiConfig aiConfig) {
        this.openAiClient = openAiClient;
        this.localTtsClient = localTtsClient;
        this.aiConfig = aiConfig;
    }

    /**
     * 生成语音并保存为文件
     */
    public TtsResult generateSpeech(String text,
                                    String engine,
                                    String model,
                                    String voice,
                                    String apiKey,
                                    String baseUrl) throws IOException {
        String effectiveEngine = resolveEngine(engine);
        String effectiveVoice = resolveVoice(effectiveEngine, voice);

        byte[] audioData;

        if (isLocalEngine(effectiveEngine)) {
            // 本地 TTS (Piper / Edge TTS)
            audioData = localTtsClient.synthesize(text, effectiveEngine, effectiveVoice);

        } else if ("enjoyai".equals(effectiveEngine)) {
            // EnjoyAI 作为代理转发到 OpenAI TTS
            audioData = callEnjoyAiTts(text, effectiveVoice, apiKey, baseUrl);

        } else {
            // OpenAI TTS
            audioData = callOpenAiTts(text, model, effectiveVoice, apiKey, baseUrl);
        }

        // 保存为文件
        String filename = saveAudioFile(audioData, "piper".equals(effectiveEngine) ? "wav" : "mp3");

        log.info("TTS saved: engine={} voice={} file={}", effectiveEngine, effectiveVoice, filename);

        return TtsResult.builder()
                .filePath(Paths.get(getStorageDir(), "tts", filename).toString())
                .filename(filename)
                .audioData(audioData)
                .duration(audioData.length)
                .engine(effectiveEngine)
                .voice(effectiveVoice)
                .build();
    }

    // ---- private helpers ----

    private boolean isLocalEngine(String engine) {
        return "piper".equals(engine) || "edge-tts".equals(engine);
    }

    private String resolveEngine(String engine) {
        if (engine != null) return engine;
        String configured = aiConfig.getTts().getDefaultEngine();
        // 如果默认引擎是本地引擎，直接用
        if (isLocalEngine(configured)) return configured;
        // 否则 fallback 到 openai
        return "openai";
    }

    private String resolveVoice(String engine, String voice) {
        if (voice != null && !voice.isBlank()) return voice;
        var local = aiConfig.getTts().getLocal();
        if ("piper".equals(engine)) return local.getPiperVoice();
        if ("edge-tts".equals(engine)) return local.getEdgeVoice();
        return "alloy";  // OpenAI default
    }

    private byte[] callOpenAiTts(String text, String model, String voice,
                                  String apiKey, String baseUrl) throws IOException {
        String effectiveModel = model != null ? model : "tts-1";
        String effectiveApiKey = apiKey;
        String effectiveBaseUrl = baseUrl;

        if (effectiveApiKey == null) {
            var openaiConfig = aiConfig.getTts().getOpenai();
            effectiveApiKey = openaiConfig != null ? openaiConfig.getApiKey() : null;
        }
        if (effectiveBaseUrl == null) {
            var openaiConfig = aiConfig.getTts().getOpenai();
            effectiveBaseUrl = openaiConfig != null ? openaiConfig.getBaseUrl() : null;
        }

        return openAiClient.speechCreate(
                text, effectiveModel, voice, effectiveApiKey, effectiveBaseUrl);
    }

    private byte[] callEnjoyAiTts(String text, String voice,
                                   String apiKey, String baseUrl) throws IOException {
        String effectiveApiKey = apiKey;
        String effectiveBaseUrl = baseUrl;

        if (effectiveApiKey == null) {
            var enjoyai = aiConfig.getTts().getEnjoyai();
            effectiveApiKey = enjoyai != null ? enjoyai.getApiKey() : null;
        }
        if (effectiveBaseUrl == null) {
            var enjoyai = aiConfig.getTts().getEnjoyai();
            effectiveBaseUrl = enjoyai != null ? enjoyai.getBaseUrl() : null;
        }

        return openAiClient.speechCreate(
                text, "openai/tts-1", voice, effectiveApiKey, effectiveBaseUrl);
    }

    private String saveAudioFile(byte[] audioData, String extension) throws IOException {
        String storageDir = aiConfig.getAudioStoragePath() != null
                ? aiConfig.getAudioStoragePath() : "./data/audio";
        Path dirPath = Paths.get(storageDir, "tts");
        Files.createDirectories(dirPath);

        String filename = UUID.randomUUID().toString() + "." + extension;
        Files.write(dirPath.resolve(filename), audioData);
        return filename;
    }

    private String getStorageDir() {
        return aiConfig.getAudioStoragePath() != null
                ? aiConfig.getAudioStoragePath() : "./data/audio";
    }

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
