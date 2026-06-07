package com.lingoai.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingoai.config.AiConfig;
import com.lingoai.dto.response.PracticeSummaryDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 评测服务
 * 使用已配置的 LLM 大模型分析完整对话记录，生成五维评分、详细文本评价、语法纠正和改进建议。
 *
 * 评分维度：
 *  - pronunciation（发音）：评估语音清晰度、音准、连读等
 *  - grammar（语法）：评估时态、句式结构、介词搭配等
 *  - fluency（流利度）：评估语速、停顿、连贯性
 *  - vocabulary（词汇）：评估词汇丰富度、用词准确性
 *  - interactivity（互动）：评估回应相关性、对话参与度
 *
 * 设计原则：
 *  1. 复用已配置的 LLM 引擎（LlmService/OpenAiClient），不新增外部依赖
 *  2. 使用结构化 Prompt 让 LLM 输出固定 JSON 格式，便于解析
 *  3. 支持混合模式语法纠正：优先提取真实错误，不足时生成示例
 */
@Slf4j
@Service
public class EvaluationService {

    private final OpenAiClient openAiClient;
    private final ObjectMapper objectMapper;
    private final AiConfig aiConfig;
    private final Wav2vec2PronunciationClient wav2vec2PronunciationClient;

    public EvaluationService(OpenAiClient openAiClient, AiConfig aiConfig,
                             Wav2vec2PronunciationClient wav2vec2PronunciationClient) {
        this.openAiClient = openAiClient;
        this.aiConfig = aiConfig;
        this.wav2vec2PronunciationClient = wav2vec2PronunciationClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 评估完整对话记录，生成练习总结
     *
     * @param conversationText 格式化后的完整对话文本（含角色标记）
     * @param scenarioTitle    场景标题（用于上下文理解）
     * @param llmEngine        LLM 引擎（openai/deepseek/glm/qianwen/doubao）
     * @param llmModel         LLM 模型名
     * @param apiKey           API Key（可选，使用默认配置）
     * @param baseUrl          Base URL（可选，使用默认配置）
     * @return 练习总结 DTO
     */
    public PracticeSummaryDTO evaluate(String conversationText,
                                       String scenarioTitle,
                                       String llmEngine,
                                       String llmModel,
                                       String apiKey,
                                       String baseUrl) throws IOException {
        long startTime = System.currentTimeMillis();
        log.info("[Evaluation] 开始评估对话: scenario={}, textLength={}", scenarioTitle, conversationText.length());

        // 构建评测 Prompt
        List<Map<String, String>> messages = buildEvaluationPrompt(conversationText, scenarioTitle);

        // 获取引擎配置作为默认值
        String engine = llmEngine != null ? llmEngine : "openai";
        AiConfig.LlmProvider provider = getProvider(engine);
        String effectiveModel = (llmModel != null && !llmModel.isEmpty()) ? llmModel
                : (provider != null && provider.getDefaultModel() != null ? provider.getDefaultModel() : "gpt-4o");
        String effectiveApiKey = (apiKey != null && !apiKey.isEmpty()) ? apiKey
                : (provider != null ? provider.getApiKey() : "");
        String effectiveBaseUrl = (baseUrl != null && !baseUrl.isEmpty()) ? baseUrl
                : (provider != null ? provider.getBaseUrl() : "https://api.openai.com/v1");
        Double temperature = provider != null ? provider.getTemperature() : 0.3;

        // 调用 LLM 获取结构化 JSON 评价
        log.info("[Evaluation] LLM请求参数: engine={}, model={}, apiKey={}..., baseUrl={}",
                engine, effectiveModel,
                effectiveApiKey != null && effectiveApiKey.length() > 8 ? effectiveApiKey.substring(0, 8) : "N/A",
                effectiveBaseUrl);
        JsonNode resultJson;
        try {
            resultJson = openAiClient.jsonCommand(messages, effectiveModel, temperature, effectiveApiKey, effectiveBaseUrl);
        } catch (Exception e) {
            log.error("[Evaluation] LLM 调用失败 ({}), 直接使用默认评测结果", e.getMessage());
            // 不回退调 textCommand — 同一网络环境同样会超时
            resultJson = buildDefaultResult();
        }

        // 解析 JSON 到 DTO
        PracticeSummaryDTO result = parseEvaluationResult(resultJson);
        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[Evaluation] 评估完成: overall={}, errors={}, suggestions={}, time={}ms",
                result.getOverallScore(),
                result.getErrors() != null ? result.getErrors().size() : 0,
                result.getSuggestions() != null ? result.getSuggestions().size() : 0,
                elapsed);

        return result;
    }

    /**
     * 发音评测版 evaluate — 在 LLM 评测基础上，用 wav2vec2 音素评分覆盖发音维度
     * <p>
     * 完全向后兼容：不携带发音数据(null / 空路径)时行为与 evaluate() 完全一致。
     *
     * @param conversationText  对话文本
     * @param scenarioTitle     场景标题
     * @param llmEngine         LLM 引擎
     * @param llmModel          LLM 模型
     * @param apiKey            API Key
     * @param baseUrl           Base URL
     * @param audioFilePath     用户录音文件路径（null=跳过发音评测）
     * @param pronunciationRef  发音参考文本（用户想说的句子）
     * @return 评测结果（发音维度已混合 wav2vec2 数据）
     */
    public PracticeSummaryDTO evaluateWithPronunciation(
            String conversationText,
            String scenarioTitle,
            String llmEngine,
            String llmModel,
            String apiKey,
            String baseUrl,
            java.nio.file.Path audioFilePath,
            String pronunciationRef) throws IOException {

        // 1. 先走原有的 LLM 评测
        PracticeSummaryDTO result = evaluate(conversationText, scenarioTitle,
                llmEngine, llmModel, apiKey, baseUrl);

        // 2. 如果有发音数据，覆盖发音维度
        if (audioFilePath != null && pronunciationRef != null
                && java.nio.file.Files.exists(audioFilePath)) {
            String language = aiConfig.getPronunciation().getDefaultLanguage();
            try {
                com.fasterxml.jackson.databind.JsonNode pronResult =
                        wav2vec2PronunciationClient.evaluate(audioFilePath, pronunciationRef, language);

                int pronScore = pronResult.path("overall_pronunciation_score").asInt(80);
                double accuracy = pronResult.path("accuracy_score").asDouble(78.0);
                double fluency = pronResult.path("fluency_score").asDouble(80.0);
                double completeness = pronResult.path("completeness_score").asDouble(85.0);

                String evaluation = String.format(
                        "基于 wav2vec2 音素级分析：准确度 %.1f，流利度 %.1f，完整度 %.1f。%s",
                        accuracy, fluency, completeness,
                        accuracy >= 80 ? "发音整体清晰，继续保持。" :
                                accuracy >= 60 ? "部分音素发音不够标准，建议针对性练习。" :
                                        "需要从基础音素开始加强练习。"
                );

                // 替换发音维度
                com.lingoai.dto.response.PracticeSummaryDTO.DimensionsDTO dims = result.getDimensions();
                if (dims != null) {
                    dims.setPronunciation(
                            new com.lingoai.dto.response.PracticeSummaryDTO.DimensionDetail(pronScore, evaluation));

                    // 重新计算 overallScore（发音维度替换后的加权平均）
                    int g = dims.getGrammar() != null ? dims.getGrammar().getScore() : 80;
                    int f = dims.getFluency() != null ? dims.getFluency().getScore() : 80;
                    int v = dims.getVocabulary() != null ? dims.getVocabulary().getScore() : 80;
                    int i = dims.getInteractivity() != null ? dims.getInteractivity().getScore() : 80;
                    int newOverall = (int) Math.round((pronScore + g + f + v + i) / 5.0);
                    result.setOverallScore(newOverall);
                }

                log.info("[Evaluation] 发音维度已由 wav2vec2 覆盖: pronScore={}, newOverall={}",
                        pronScore, result.getOverallScore());

            } catch (Exception e) {
                log.warn("[Evaluation] 调用 wav2vec2 发音评测失败，保留 LLM 发音结果: {}", e.getMessage());
            }
        }

        return result;
    }

    /**
     * 构建评测 Prompt
     * 使用角色设定 + 严格 JSON Schema 指导 LLM 输出结构化结果
     */
    private List<Map<String, String>> buildEvaluationPrompt(String conversationText, String scenarioTitle) {
        String systemPrompt = """
                You are LingoAI, an expert English speaking coach and evaluator. Your task is to analyze an English conversation practice session and provide a comprehensive evaluation.

                Analyze the conversation carefully and return a JSON object with the following structure:

                {
                  "overallScore": <integer 0-100>,
                  "dimensions": {
                    "pronunciation": {
                      "score": <integer 0-100>,
                      "evaluation": "<detailed text analysis in Chinese, e.g., '辅音 /θ/ 发音不够清晰，建议舌尖轻触上齿。元音发音整体准确。'>"
                    },
                    "grammar": {
                      "score": <integer 0-100>,
                      "evaluation": "<detailed text analysis in Chinese, e.g., '现在完成时使用偏少，过去时与现在时混用现象出现X次。'>"
                    },
                    "fluency": {
                      "score": <integer 0-100>,
                      "evaluation": "<detailed text analysis in Chinese>"
                    },
                    "vocabulary": {
                      "score": <integer 0-100>,
                      "evaluation": "<detailed text analysis in Chinese>"
                    },
                    "interactivity": {
                      "score": <integer 0-100>,
                      "evaluation": "<detailed text analysis in Chinese>"
                    }
                  },
                  "errors": [
                    {
                      "original": "<user's original incorrect sentence>",
                      "corrected": "<corrected version>",
                      "type": "<error type in Chinese, e.g., '时态 - 过去式', '词汇 - 词性区分', '词法 - 介词搭配'>"
                    }
                  ],
                  "suggestions": [
                    {
                      "title": "<short suggestion title in Chinese, e.g., '注意时态的连贯一致性'>",
                      "description": "<detailed suggestion in Chinese>"
                    }
                  ]
                }

                ### Scoring Guidelines:
                - pronunciation: Evaluate based on the TEXT input only (since you cannot hear audio). Infer likely pronunciation issues from written patterns (e.g., common spelling-based pronunciation errors, word choice that suggests phonetic confusion). If the text is typed input, note this and score based on general language proficiency indicators.
                - grammar: Evaluate sentence structure, tense usage, subject-verb agreement, prepositions, articles.
                - fluency: Evaluate from text patterns — sentence length variety, use of filler words, paragraph coherence, flow of ideas.
                - vocabulary: Evaluate word choice, range of expressions, appropriateness of vocabulary for the scenario (%s).
                - interactivity: Evaluate how well the user responds to the AI's questions, relevance of answers, engagement level.

                ### Error Extraction Rules (混合模式):
                1. First, identify REAL errors from the user's actual sentences. Only include genuine mistakes.
                2. If there are fewer than 3 real errors, supplement with COMMON mistakes that learners at this level typically make in the "%s" scenario. Mark supplemented items with type prefix "示例 - ".
                3. Each error must include the original sentence, the corrected version, and the error type.
                4. Error types should be specific: "时态 - 现在完成时", "词法 - 介词搭配", "词汇 - 词性区分", "句法 - 语序", etc.

                ### Suggestion Guidelines:
                Provide 3 actionable suggestions. Each should have a concise title (8-15 Chinese characters) and a detailed description (2-4 sentences in Chinese) with specific examples.

                ### IMPORTANT:
                - Return ONLY valid JSON. No markdown, no code blocks, no additional text.
                - Scores must be integers between 0 and 100.
                - All "evaluation", "title", "description" fields must be in Chinese.
                - "original" and "corrected" fields in errors must be in English.
                """;

        String formattedSystemPrompt = String.format(systemPrompt, scenarioTitle, scenarioTitle);

        String userPrompt = "Here is the conversation transcript. Please evaluate it:\n\n" + conversationText;

        return List.of(
                Map.of("role", "system", "content", formattedSystemPrompt),
                Map.of("role", "user", "content", userPrompt)
        );
    }

    /**
     * 解析 LLM 返回的 JSON 到 PracticeSummaryDTO
     */
    private PracticeSummaryDTO parseEvaluationResult(JsonNode json) {
        PracticeSummaryDTO.PracticeSummaryDTOBuilder builder = PracticeSummaryDTO.builder();

        // overall score
        JsonNode overallNode = json.get("overallScore");
        builder.overallScore(overallNode != null ? overallNode.asInt(80) : 80);

        // dimensions
        JsonNode dims = json.get("dimensions");
        if (dims != null) {
            PracticeSummaryDTO.DimensionsDTO.DimensionsDTOBuilder dimBuilder = PracticeSummaryDTO.DimensionsDTO.builder();
            dimBuilder.pronunciation(parseDimensionDetail(dims.get("pronunciation")));
            dimBuilder.grammar(parseDimensionDetail(dims.get("grammar")));
            dimBuilder.fluency(parseDimensionDetail(dims.get("fluency")));
            dimBuilder.vocabulary(parseDimensionDetail(dims.get("vocabulary")));
            dimBuilder.interactivity(parseDimensionDetail(dims.get("interactivity")));
            builder.dimensions(dimBuilder.build());
        }

        // errors
        JsonNode errorsNode = json.get("errors");
        if (errorsNode != null && errorsNode.isArray()) {
            List<PracticeSummaryDTO.ErrorItemDTO> errors = new ArrayList<>();
            for (JsonNode err : errorsNode) {
                errors.add(PracticeSummaryDTO.ErrorItemDTO.builder()
                        .original(err.get("original") != null ? err.get("original").asText() : "")
                        .corrected(err.get("corrected") != null ? err.get("corrected").asText() : "")
                        .type(err.get("type") != null ? err.get("type").asText() : "")
                        .build());
            }
            builder.errors(errors);
        }

        // suggestions
        JsonNode suggestionsNode = json.get("suggestions");
        if (suggestionsNode != null && suggestionsNode.isArray()) {
            List<PracticeSummaryDTO.SuggestionDTO> suggestions = new ArrayList<>();
            for (JsonNode sug : suggestionsNode) {
                suggestions.add(PracticeSummaryDTO.SuggestionDTO.builder()
                        .title(sug.get("title") != null ? sug.get("title").asText() : "")
                        .description(sug.get("description") != null ? sug.get("description").asText() : "")
                        .build());
            }
            builder.suggestions(suggestions);
        }

        return builder.build();
    }

    /**
     * 解析单个维度的评分和文本评价
     */
    private PracticeSummaryDTO.DimensionDetail parseDimensionDetail(JsonNode node) {
        if (node == null) {
            return PracticeSummaryDTO.DimensionDetail.builder()
                    .score(80)
                    .evaluation("暂未获取到详细评价。")
                    .build();
        }
        return PracticeSummaryDTO.DimensionDetail.builder()
                .score(node.get("score") != null ? node.get("score").asInt(80) : 80)
                .evaluation(node.get("evaluation") != null ? node.get("evaluation").asText("") : "")
                .build();
    }

    /**
     * 从文本响应中提取 JSON（textCommand 回退方案）
     */
    private JsonNode extractJsonFromText(String text) {
        try {
            // 尝试直接解析
            return objectMapper.readTree(text);
        } catch (Exception e) {
            // 尝试提取 JSON 片段（去除可能的 markdown 标记）
            try {
                String cleaned = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
                return objectMapper.readTree(cleaned);
            } catch (Exception ex) {
                log.error("[Evaluation] 无法从 LLM 响应中提取 JSON: {}", text);
                // 返回默认值
                return buildDefaultResult();
            }
        }
    }

    /**
     * 构建默认评估结果（LLM 完全失败时的回退）
     * 包级可访问，供单元测试直接验证
     */
    JsonNode buildDefaultResult() {
        try {
            String defaultJson = """
                    {
                      "overallScore": 80,
                      "dimensions": {
                        "pronunciation": { "score": 80, "evaluation": "基于文本分析，整体表达清晰。建议在口语练习中注意连读和弱读现象。" },
                        "grammar": { "score": 78, "evaluation": "基础语法结构基本正确，建议加强对时态一致性的练习。" },
                        "fluency": { "score": 85, "evaluation": "表达较为连贯，建议尝试使用更多逻辑连接词来增强语篇衔接。" },
                        "vocabulary": { "score": 82, "evaluation": "词汇使用基本得当，可进一步扩展场景相关的高级表达。" },
                        "interactivity": { "score": 85, "evaluation": "能够较好地回应提问，建议尝试展开更多细节来丰富对话。" }
                      },
                      "errors": [
                        { "original": "I go to the store yesterday.", "corrected": "I went to the store yesterday.", "type": "时态 - 过去式" }
                      ],
                      "suggestions": [
                        { "title": "注意时态的连贯一致性", "description": "在表达已发生的事情时，注意动词过去式的变化规则。建议进行专项的时态转换练习。" },
                        { "title": "丰富句型逻辑连接词", "description": "避免重复使用简单的连接词。尝试使用 'furthermore', 'moreover', 'nevertheless' 等过渡词来增强逻辑层次。" },
                        { "title": "保持稳步调，注重连读练习", "description": "注意辅元音连读现象，如 'depends on' 等常见搭配的自然过渡。" }
                      ]
                    }
                    """;
            return objectMapper.readTree(defaultJson);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    /**
     * 格式化对话记录为 LLM 可读的文本
     */
    public static String formatConversation(List<? extends Map<String, String>> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- Conversation Start ---\n");
        for (Map<String, String> msg : messages) {
            String role = msg.get("role");
            String content = msg.get("content");
            if ("user".equals(role)) {
                sb.append("[User]: ").append(content).append("\n");
            } else if ("assistant".equals(role) || "ai".equals(role)) {
                sb.append("[AI Coach]: ").append(content).append("\n");
            } else {
                sb.append("[").append(role).append("]: ").append(content).append("\n");
            }
            sb.append("\n");
        }
        sb.append("--- Conversation End ---");
        return sb.toString();
    }

    /**
     * 获取 LLM 引擎配置
     */
    private AiConfig.LlmProvider getProvider(String engine) {
        if (engine == null) return null;
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
