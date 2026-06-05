package com.lingoai.service.ai;

import com.lingoai.config.AiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whisper 语音识别服务
 * 使用 OpenAI Whisper API 进行音频转文字
 * 
 * 来源参考：everyone-can-use-english 的 use-transcribe.tsx 中 transcribeByOpenAi() 实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhisperService {

    private final AiConfig aiConfig;

    private static final MediaType MEDIA_TYPE_OCTET = MediaType.parse("application/octet-stream");
    private static final MediaType MEDIA_TYPE_MULTIPART = MediaType.parse("multipart/form-data");

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(30))
            .writeTimeout(Duration.ofSeconds(60))
            .readTimeout(Duration.ofSeconds(60))
            .build();

    /**
     * Whisper 转录结果
     */
    @lombok.Builder
    @lombok.Data
    public static class WhisperResult {
        private String text;
        private String language;
        private double duration;
        private String[] words;
        private Segment[] segments;
    }

    @lombok.Builder
    @lombok.Data
    public static class Segment {
        private int id;
        private String text;
        private double startTime;
        private double endTime;
    }

    /**
     * 转录音频文件
     * 
     * @param audioFile 音频文件路径（支持 mp3, wav, m4a, ogg, webm 等格式）
     * @param language 语言代码（如 "en", "zh"，null 则自动检测）
     * @return 转录结果
     */
    public WhisperResult transcribe(Path audioFile, String language) throws IOException, InterruptedException {
        log.info("Starting Whisper transcription for file: {}", audioFile);
        
        // 获取 OpenAI 配置
        AiConfig.LlmProvider openaiConfig = aiConfig.getLlm().getOpenai();
        String apiKey = openaiConfig.getApiKey();
        String baseUrl = openaiConfig.getBaseUrl();
        
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("sk-your-key")) {
            // 如果没有配置 OpenAI API，尝试使用 DeepSeek 的 Whisper
            AiConfig.LlmConfig llmConfig = aiConfig.getLlm();
            AiConfig.LlmProvider deepseekConfig = llmConfig.getDeepseek();
            
            if (deepseekConfig != null && deepseekConfig.getApiKey() != null && !deepseekConfig.getApiKey().isEmpty()) {
                log.info("OpenAI not configured, trying DeepSeek Whisper...");
                return transcribeWithDeepSeek(audioFile, language, deepseekConfig);
            }
            
            throw new IOException("No Whisper API configured. Please set OPENAI_API_KEY or DEEPSEEK_API_KEY in environment.");
        }
        
        // 构建 Whisper API URL
        String whisperUrl = buildWhisperUrl(baseUrl);
        
        log.debug("Whisper API URL: {}", whisperUrl);
        
        // 读取音频文件
        byte[] audioData = Files.readAllBytes(audioFile);
        String fileName = audioFile.getFileName().toString();
        String mimeType = getMimeType(fileName);
        
        // 构建 multipart form data 请求
        RequestBody audioBody = RequestBody.create(audioData, MediaType.parse(mimeType));
        
        MultipartBody.Builder multipartBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, audioBody)
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart("response_format", "verbose_json");
        
        if (language != null && !language.isEmpty()) {
            multipartBuilder.addFormDataPart("language", language);
        }
        
        RequestBody requestBody = multipartBuilder.build();
        
        Request request = new Request.Builder()
                .url(whisperUrl)
                .header("Authorization", "Bearer " + apiKey)
                .post(requestBody)
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            
            if (!response.isSuccessful()) {
                log.error("Whisper API error: {} - {}", response.code(), responseBody);
                throw new IOException("Whisper API error: " + response.code() + " - " + responseBody);
            }
            
            log.info("Whisper transcription completed successfully");
            return parseWhisperResponse(responseBody);
        }
    }
    
    /**
     * 使用 DeepSeek API 转录
     */
    private WhisperResult transcribeWithDeepSeek(Path audioFile, String language, AiConfig.LlmProvider config) 
            throws IOException, InterruptedException {
        String whisperUrl = buildWhisperUrl(config.getBaseUrl());
        
        byte[] audioData = Files.readAllBytes(audioFile);
        String fileName = audioFile.getFileName().toString();
        String mimeType = getMimeType(fileName);
        
        RequestBody audioBody = RequestBody.create(audioData, MediaType.parse(mimeType));
        
        MultipartBody.Builder multipartBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, audioBody)
                .addFormDataPart("model", "deepseek-chat") // DeepSeek 的 Whisper 模型
                .addFormDataPart("response_format", "verbose_json");
        
        if (language != null && !language.isEmpty()) {
            multipartBuilder.addFormDataPart("language", language);
        }
        
        RequestBody requestBody = multipartBuilder.build();
        
        Request request = new Request.Builder()
                .url(whisperUrl)
                .header("Authorization", "Bearer " + config.getApiKey())
                .post(requestBody)
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            
            if (!response.isSuccessful()) {
                log.error("DeepSeek Whisper API error: {} - {}", response.code(), responseBody);
                throw new IOException("DeepSeek Whisper API error: " + response.code() + " - " + responseBody);
            }
            
            log.info("DeepSeek Whisper transcription completed successfully");
            return parseWhisperResponse(responseBody);
        }
    }
    
    /**
     * 构建 Whisper API URL
     */
    private String buildWhisperUrl(String baseUrl) {
        if (baseUrl.contains("/v1/chat/completions")) {
            return baseUrl.replace("/v1/chat/completions", "/v1/audio/transcriptions");
        }
        if (!baseUrl.endsWith("/v1/audio/transcriptions")) {
            return baseUrl + "/v1/audio/transcriptions";
        }
        return baseUrl;
    }
    
    /**
     * 根据文件扩展名获取 MIME 类型
     */
    private String getMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".webm")) return "audio/webm";
        if (lower.endsWith(".mp4")) return "audio/mp4";
        return "audio/mpeg";
    }
    
    /**
     * 解析 Whisper API 响应
     */
    private WhisperResult parseWhisperResponse(String jsonResponse) {
        log.debug("Whisper response: {}", jsonResponse);
        
        String text = extractJsonValue(jsonResponse, "text");
        String detectedLanguage = extractJsonValue(jsonResponse, "language");
        double duration = 0.0;
        
        try {
            String durationStr = extractJsonValue(jsonResponse, "duration");
            if (durationStr != null) {
                duration = Double.parseDouble(durationStr);
            }
        } catch (Exception e) {
            log.debug("Could not parse duration");
        }
        
        // 解析 words
        String[] words = null;
        Pattern wordsPattern = Pattern.compile("\"words\"\\s*:\\s*\\[([^\\]]+)\\]");
        Matcher wordsMatcher = wordsPattern.matcher(jsonResponse);
        if (wordsMatcher.find()) {
            String wordsStr = wordsMatcher.group(1);
            Pattern wordPattern = Pattern.compile("\"word\"\\s*:\\s*\"([^\"]+)\"");
            Matcher wordMatcher = wordPattern.matcher(wordsStr);
            java.util.List<String> wordList = new java.util.ArrayList<>();
            while (wordMatcher.find()) {
                wordList.add(wordMatcher.group(1));
            }
            words = wordList.toArray(new String[0]);
        }
        
        // 解析 segments
        java.util.List<Segment> segmentList = new java.util.ArrayList<>();
        
        // 使用更精确的正则表达式解析 segments 数组
        Pattern segmentsArrayPattern = Pattern.compile("\"segments\"\\s*:\\s*\\[");
        Matcher segmentsArrayMatcher = segmentsArrayPattern.matcher(jsonResponse);
        if (segmentsArrayMatcher.find()) {
            // 找到 segments 数组的开始位置
            int startPos = segmentsArrayMatcher.end();
            // 找到对应的结束括号
            int bracketCount = 1;
            int endPos = startPos;
            for (int i = startPos; i < jsonResponse.length() && bracketCount > 0; i++) {
                char c = jsonResponse.charAt(i);
                if (c == '[') bracketCount++;
                else if (c == ']') bracketCount--;
                else if (c == '{') {
                    // 开始解析单个 segment
                    int segStart = i;
                    int segBracketCount = 1;
                    int j;
                    for (j = i + 1; j < jsonResponse.length() && segBracketCount > 0; j++) {
                        char cc = jsonResponse.charAt(j);
                        if (cc == '{') segBracketCount++;
                        else if (cc == '}') segBracketCount--;
                    }
                    String segmentJson = jsonResponse.substring(segStart, j);
                    
                    String segmentText = extractJsonValue(segmentJson, "text");
                    double start = 0.0, end = 0.0;
                    try {
                        String startStr = extractJsonValue(segmentJson, "start");
                        if (startStr != null) start = Double.parseDouble(startStr);
                        String endStr = extractJsonValue(segmentJson, "end");
                        if (endStr != null) end = Double.parseDouble(endStr);
                    } catch (Exception ignored) {}
                    
                    segmentList.add(Segment.builder()
                            .id(segmentList.size())
                            .text(segmentText)
                            .startTime(start)
                            .endTime(end)
                            .build());
                    
                    i = j;
                }
            }
        }
        
        return WhisperResult.builder()
                .text(text)
                .language(detectedLanguage)
                .duration(duration)
                .words(words)
                .segments(segmentList.toArray(new Segment[0]))
                .build();
    }
    
    /**
     * 从 JSON 字符串中提取值
     */
    private String extractJsonValue(String json, String key) {
        // 匹配 "key": "value" 或 "key": value
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*(\"[^\"]*\"|[^,}\\s\\[\\]]+)");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            String value = matcher.group(1);
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            return value;
        }
        return null;
    }
}
