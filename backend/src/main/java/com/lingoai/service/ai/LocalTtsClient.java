package com.lingoai.service.ai;

import com.lingoai.config.AiConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * 本地 TTS 客户端
 * 调用 tts-service (FastAPI) 的 HTTP API 生成语音
 *
 * 支持引擎:
 *  - piper:    本地神经 TTS，完全离线
 *  - edge-tts: Microsoft Edge 免费在线 TTS
 */
@Slf4j
@Service
public class LocalTtsClient {

    private final AiConfig.LocalTtsConfig config;
    private final HttpClient httpClient;

    public LocalTtsClient(AiConfig aiConfig) {
        this.config = aiConfig.getTts().getLocal();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofMillis(config.getTimeoutMs()))
                .build();
    }

    /**
     * 调用 tts-service 合成语音
     *
     * @param text      待合成文本
     * @param engine    TTS 引擎: "piper" 或 "edge-tts"
     * @param voice     语音 ID
     * @return 音频二进制数据 (wav 或 mp3)
     */
    public byte[] synthesize(String text, String engine, String voice) throws IOException {
        String effectiveEngine = engine != null ? engine : config.getDefaultEngine();
        String effectiveVoice = effectiveVoice(effectiveEngine, voice);
        String outputFormat = "piper".equals(effectiveEngine) ? "wav" : "mp3";

        // 构建 JSON 请求体
        String json = buildJsonPayload(text, effectiveEngine, effectiveVoice, outputFormat);

        String url = config.getBaseUrl() + "/tts";
        log.info("LocalTTS request: engine={} voice={} text_len={} url={}",
                effectiveEngine, effectiveVoice, text.length(), url);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofMillis(config.getTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                String errorBody = new String(response.body(), StandardCharsets.UTF_8);
                throw new IOException(
                        "LocalTTS service returned " + response.statusCode() + ": " + errorBody);
            }

            byte[] audioData = response.body();
            log.info("LocalTTS OK: engine={} voice={} audio_len={}",
                    effectiveEngine, effectiveVoice, audioData.length);

            return audioData;

        } catch (java.net.ConnectException e) {
            throw new IOException(
                    "Cannot connect to local TTS service at " + config.getBaseUrl()
                    + ". Is the tts-service container running?", e);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IOException(
                    "Local TTS service timeout after " + config.getTimeoutMs() + "ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("LocalTTS request interrupted", e);
        }
    }

    /**
     * 获取可用语音列表
     */
    public String getVoices(String engine) throws IOException {
        String url = config.getBaseUrl() + "/voices?engine=" + engine;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofMillis(10000))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                throw new IOException("Failed to list voices: " + response.statusCode());
            }

            return response.body();

        } catch (java.net.ConnectException e) {
            throw new IOException("Cannot connect to local TTS service", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("List voices interrupted", e);
        }
    }

    /**
     * 健康检查
     */
    public boolean isHealthy() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getBaseUrl() + "/health"))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- private helpers ----

    private String effectiveVoice(String engine, String requestedVoice) {
        if (requestedVoice != null && !requestedVoice.isBlank()) {
            return requestedVoice;
        }
        if ("piper".equals(engine)) {
            return config.getPiperVoice();
        }
        return config.getEdgeVoice();
    }

    private String buildJsonPayload(String text, String engine, String voice, String format) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"text\":").append(jsonEscape(text)).append(",");
        sb.append("\"engine\":\"").append(engine).append("\",");
        sb.append("\"voice\":\"").append(voice).append("\",");
        sb.append("\"output_format\":\"").append(format).append("\"");

        if ("piper".equals(engine)) {
            sb.append(",\"speed\":").append(config.getPiperSpeed());
        } else {
            sb.append(",\"rate\":\"").append(config.getEdgeRate()).append("\"");
            sb.append(",\"pitch\":\"").append(config.getEdgePitch()).append("\"");
        }

        sb.append("}");
        return sb.toString();
    }

    private String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
