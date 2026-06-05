package com.lingoai.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lingoai.config.AiConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI API 客户端
 * 来源参考：everyone-can-use-english 的 text.command.ts + json.command.ts
 *
 * 核心能力（从原项目移植）：
 *  - textCommand:    基础文本生成（对应 text.command.ts）
 *  - jsonCommand:    结构化 JSON 输出（对应 json.command.ts + Zod Schema）
 *  - speechCreate:   TTS 语音合成（对应 speech.ts Speech.generate）
 */
@Slf4j
@Service
public class OpenAiClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AiConfig aiConfig;

    public OpenAiClient(AiConfig aiConfig) {
        this.aiConfig = aiConfig;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * text.command.ts 等价实现 — 基础文本生成
     */
    public String textCommand(List<Map<String, String>> messages,
                              String model,
                              Double temperature,
                              String apiKey,
                              String baseUrl) throws IOException {
        return textCommand(messages, model, temperature, apiKey, baseUrl, "openai");
    }

    /**
     * text.command.ts 等价实现 — 基础文本生成（支持多引擎）
     * @param engine 引擎类型：openai | deepseek | glm | qianwen | doubao
     */
    public String textCommand(List<Map<String, String>> messages,
                              String model,
                              Double temperature,
                              String apiKey,
                              String baseUrl,
                              String engine) throws IOException {
        // 获取引擎配置
        AiConfig.LlmProvider provider = getLlmProvider(engine);
        
        // 使用参数或默认配置
        String effectiveApiKey = (apiKey != null && !apiKey.isEmpty()) ? apiKey : 
                                 (provider != null ? provider.getApiKey() : "");
        String effectiveBaseUrl = (baseUrl != null && !baseUrl.isEmpty()) ? baseUrl : 
                                  (provider != null ? provider.getBaseUrl() : "https://api.openai.com/v1");
        String effectiveModel = (model != null && !model.isEmpty()) ? model : 
                                (provider != null && provider.getDefaultModel() != null ? 
                                 provider.getDefaultModel() : "gpt-4o");
        
        ObjectNode requestBody = buildChatRequest(messages, effectiveModel, temperature, false, engine);
        String response = executeChatCompletion(requestBody, effectiveApiKey, effectiveBaseUrl);
        return extractTextContent(response);
    }

    /**
     * 流式文本生成 — 返回 SSE 格式的流
     * @param messages 消息列表
     * @param model 模型名称
     * @param temperature 温度参数
     * @param apiKey API Key
     * @param baseUrl Base URL
     * @param engine 引擎类型
     * @return SSE 格式的字符串流
     */
    public String streamTextCommand(List<Map<String, String>> messages,
                                    String model,
                                    Double temperature,
                                    String apiKey,
                                    String baseUrl,
                                    String engine) throws IOException {
        // 获取引擎配置
        AiConfig.LlmProvider provider = getLlmProvider(engine);

        // 使用参数或默认配置
        String effectiveApiKey = (apiKey != null && !apiKey.isEmpty()) ? apiKey :
                                 (provider != null ? provider.getApiKey() : "");
        String effectiveBaseUrl = (baseUrl != null && !baseUrl.isEmpty()) ? baseUrl :
                                  (provider != null ? provider.getBaseUrl() : "https://api.openai.com/v1");
        String effectiveModel = (model != null && !model.isEmpty()) ? model :
                                (provider != null && provider.getDefaultModel() != null ?
                                 provider.getDefaultModel() : "gpt-4o");

        ObjectNode requestBody = buildStreamChatRequest(messages, effectiveModel, temperature, engine);
        return executeStreamChatCompletion(requestBody, effectiveApiKey, effectiveBaseUrl);
    }

    /**
     * json.command.ts 等价实现 — 结构化 JSON 输出
     * 原项目使用 Zod Schema + withStructuredOutput，这里用 response_format: json_object
     */
    public JsonNode jsonCommand(List<Map<String, String>> messages,
                                String model,
                                Double temperature,
                                String apiKey,
                                String baseUrl) throws IOException {
        ObjectNode requestBody = buildChatRequest(messages, model, temperature, true, "openai");
        String response = executeChatCompletion(requestBody, apiKey, baseUrl);
        String content = extractTextContent(response);
        return objectMapper.readTree(content);
    }

    /**
     * 构建聊天补全请求体
     */
    private ObjectNode buildChatRequest(List<Map<String, String>> messages,
                                        String model,
                                        Double temperature,
                                        boolean jsonMode,
                                        String engine) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model != null ? model : getLlmDefaultModel());
        body.put("temperature", temperature != null ? temperature : 0.0);

        if (jsonMode) {
            ObjectNode responseFormat = body.putObject("response_format");
            responseFormat.put("type", "json_object");
        }

        // DeepSeek 默认开启思考模式，需要禁用以获取正常响应
        if ("deepseek".equals(engine)) {
            ObjectNode thinkingConfig = body.putObject("thinking");
            thinkingConfig.put("type", "disabled");
        }

        ArrayNode messagesArray = body.putArray("messages");
        for (Map<String, String> msg : messages) {
            ObjectNode msgNode = messagesArray.addObject();
            msgNode.put("role", msg.get("role"));
            msgNode.put("content", msg.get("content"));
        }
        return body;
    }

    /**
     * 构建流式聊天补全请求体
     */
    private ObjectNode buildStreamChatRequest(List<Map<String, String>> messages,
                                             String model,
                                             Double temperature,
                                             String engine) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model != null ? model : getLlmDefaultModel());
        body.put("temperature", temperature != null ? temperature : 0.0);
        body.put("stream", true);

        // DeepSeek 默认开启思考模式，需要禁用以获取正常响应
        if ("deepseek".equals(engine)) {
            ObjectNode thinkingConfig = body.putObject("thinking");
            thinkingConfig.put("type", "disabled");
        }

        ArrayNode messagesArray = body.putArray("messages");
        for (Map<String, String> msg : messages) {
            ObjectNode msgNode = messagesArray.addObject();
            msgNode.put("role", msg.get("role"));
            msgNode.put("content", msg.get("content"));
        }
        return body;
    }

    /**
     * 执行流式聊天补全 API 调用
     * 使用流式处理器逐行读取 SSE 响应
     */
    private String executeStreamChatCompletion(ObjectNode requestBody,
                                              String apiKey,
                                              String baseUrl) throws IOException {
        String url = buildChatUrl(baseUrl);
        String bearerToken = apiKey != null ? apiKey : getLlmApiKey();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            // 使用流式处理器逐行读取
            java.net.http.HttpResponse<java.util.stream.Stream<String>> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() != 200) {
                // 读取错误信息
                StringBuilder errorBody = new StringBuilder();
                response.body().forEach(line -> errorBody.append(line).append("\n"));
                log.error("OpenAI API streaming error: status={}, body={}", response.statusCode(), errorBody);
                throw new IOException("OpenAI API failed: " + response.statusCode());
            }

            // 收集所有行并组装成 SSE 格式
            StringBuilder sseData = new StringBuilder();
            response.body().forEach(line -> {
                sseData.append(line).append("\n");
            });

            return sseData.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }

    /**
     * OpenAI TTS 语音合成
     * 来源参考：everyone-can-use-english speech.ts (Speech.generate)
     */
    public byte[] speechCreate(String input,
                               String model,
                               String voice,
                               String apiKey,
                               String baseUrl) throws IOException {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model != null ? model : "tts-1");
        requestBody.put("input", input);
        requestBody.put("voice", voice != null ? voice : "alloy");
        requestBody.put("response_format", "mp3");

        String url = buildTtsUrl(baseUrl);
        String bearerToken = apiKey != null ? apiKey : getTtsApiKey();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                String errorBody = new String(response.body());
                log.error("TTS API error: status={}, body={}", response.statusCode(), errorBody);
                throw new IOException("TTS API failed: " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("TTS request interrupted", e);
        }
    }

    private String buildTtsUrl(String baseUrl) {
        String url = baseUrl != null ? baseUrl : getTtsBaseUrl();
        url = url.replaceAll("/?$", "");
        return url + "/audio/speech";
    }

    /**
     * 执行聊天补全 API 调用
     */
    private String executeChatCompletion(ObjectNode requestBody,
                                         String apiKey,
                                         String baseUrl) throws IOException {
        String url = buildChatUrl(baseUrl);
        String bearerToken = apiKey != null ? apiKey : getLlmApiKey();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("OpenAI API error: status={}, body={}", response.statusCode(), response.body());
                throw new IOException("OpenAI API failed: " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }

    private String buildChatUrl(String baseUrl) {
        String url = baseUrl != null ? baseUrl : getLlmBaseUrl();
        url = url.replaceAll("/?$", "");
        return url + "/chat/completions";
    }

    /**
     * 从 OpenAI 响应中提取文本内容
     */
    private String extractTextContent(String responseJson) throws IOException {
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            
            // 检查是否有错误
            if (root.has("error")) {
                String errorMsg = root.path("error").path("message").asText("Unknown error");
                throw new IOException("API Error: " + errorMsg);
            }
            
            JsonNode choices = root.path("choices");
            if (choices.isMissingNode() || !choices.isArray() || choices.size() == 0) {
                throw new IOException("Invalid response: missing or empty choices array. Response: " + responseJson);
            }
            
            JsonNode message = choices.get(0).path("message");
            if (message.isMissingNode()) {
                throw new IOException("Invalid response: missing message in choices[0]. Response: " + responseJson);
            }
            
            String content = message.path("content").asText("");
            if (content.isEmpty()) {
                log.warn("Empty content in response, possible streaming response or content filter. Response: {}", responseJson);
                return "[No content]";
            }
            
            return content;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse response: {}", responseJson, e);
            throw new IOException("Failed to parse API response: " + e.getMessage());
        }
    }

    // ============ 配置辅助方法 ============

    private String getLlmApiKey() {
        AiConfig.LlmProvider p = aiConfig.getLlm().getOpenai();
        return p != null ? p.getApiKey() : "";
    }

    private String getLlmBaseUrl() {
        AiConfig.LlmProvider p = aiConfig.getLlm().getOpenai();
        return p != null ? p.getBaseUrl() : "https://api.openai.com/v1";
    }

    private String getLlmDefaultModel() {
        AiConfig.LlmProvider p = aiConfig.getLlm().getOpenai();
        return p != null && p.getDefaultModel() != null ? p.getDefaultModel() : "gpt-4o";
    }

    private String getTtsApiKey() {
        AiConfig.TtsProvider p = aiConfig.getTts().getOpenai();
        return p != null ? p.getApiKey() : "";
    }

    private String getTtsBaseUrl() {
        AiConfig.TtsProvider p = aiConfig.getTts().getOpenai();
        return p != null ? p.getBaseUrl() : "https://api.openai.com/v1";
    }

    /**
     * 根据引擎类型获取 LLM 配置
     */
    private AiConfig.LlmProvider getLlmProvider(String engine) {
        if (engine == null) {
            return aiConfig.getLlm().getOpenai();
        }
        return switch (engine.toLowerCase()) {
            case "deepseek" -> aiConfig.getLlm().getDeepseek();
            case "glm" -> aiConfig.getLlm().getGlm();
            case "qianwen" -> aiConfig.getLlm().getQianwen();
            case "doubao" -> aiConfig.getLlm().getDoubao();
            case "enjoyai" -> aiConfig.getLlm().getEnjoyai();
            case "ollama" -> aiConfig.getLlm().getOllama();
            default -> aiConfig.getLlm().getOpenai();
        };
    }
}
