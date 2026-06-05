package com.lingoai.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM 命令服务
 * 来源参考：everyone-can-use-english 的 commands/ 目录下所有命令模块
 *
 * 集成的命令（来自原项目 commands/ 目录）：
 *  - punctuate:   标点恢复（punctuate.command.ts）—— ASR 后处理关键步骤
 *  - translate:   翻译（translate.command.ts）
 *  - analyze:     句子结构/语法/词汇分析（analyze.command.ts）
 *  - extractStory: 提取生词和习语（extract-story.command.ts）
 *  - lookup:      词典查询（lookup.command.ts）—— 含 IPA、词性、定义
 *  - ipa:         IPA 音标生成（ipa.command.ts）
 *  - refine:      表达润色（refine.command.ts）
 *  - summarizeTopic: 主题摘要（summarize-topic.command.ts）
 *  - chatSuggestion: 聊天建议（chat-suggestion.command.ts）
 *  - chat:        AI 自由对话（text.command.ts 基础能力）
 */
@Slf4j
@Service
public class LlmService {

    private final OpenAiClient openAiClient;
    private final ObjectMapper objectMapper;

    public LlmService(OpenAiClient openAiClient) {
        this.openAiClient = openAiClient;
        this.objectMapper = new ObjectMapper();
    }

    // ==================== 基础能力 ====================

    /**
     * AI 自由对话 — 核心口语陪练能力
     */
    public String chat(List<Map<String, String>> conversationHistory,
                       String systemPrompt,
                       String model,
                       Double temperature,
                       String apiKey,
                       String baseUrl) throws IOException {
        return chat(conversationHistory, systemPrompt, model, temperature, apiKey, baseUrl, "openai");
    }

    /**
     * AI 自由对话 — 核心口语陪练能力（支持多引擎）
     * @param engine 引擎类型：openai | deepseek | glm | qianwen | doubao
     */
    public String chat(List<Map<String, String>> conversationHistory,
                       String systemPrompt,
                       String model,
                       Double temperature,
                       String apiKey,
                       String baseUrl,
                       String engine) throws IOException {
        List<Map<String, String>> messages = new ArrayList<>();
        // system prompt
        if (systemPrompt != null) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.addAll(conversationHistory);

        return openAiClient.textCommand(messages, model, temperature, apiKey, baseUrl, engine);
    }

    /**
     * AI 流式对话 — 返回 SSE 格式的流
     * @param engine 引擎类型：openai | deepseek | glm | qianwen | doubao
     */
    public String streamChat(List<Map<String, String>> conversationHistory,
                            String systemPrompt,
                            String model,
                            Double temperature,
                            String apiKey,
                            String baseUrl,
                            String engine) throws IOException {
        List<Map<String, String>> messages = new ArrayList<>();
        // system prompt
        if (systemPrompt != null) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.addAll(conversationHistory);

        return openAiClient.streamTextCommand(messages, model, temperature, apiKey, baseUrl, engine);
    }

    // ==================== punctuate.command.ts — 标点恢复 ====================

    /**
     * ASR 后处理：为裸文本添加标点
     * 来源：everyone-can-use-english/src/commands/punctuate.command.ts
     */
    public String punctuate(String text,
                            String model,
                            Double temperature,
                            String apiKey,
                            String baseUrl) throws IOException {
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content",
                        "Please add proper punctuation to the text I provide you. Return the corrected text only."),
                Map.of("role", "user", "content", text)
        );
        return openAiClient.textCommand(messages, model, temperature, apiKey, baseUrl);
    }

    // ==================== translate.command.ts — 翻译 ====================

    /**
     * 将文本翻译成目标语言
     * 来源：everyone-can-use-english/src/commands/translate.command.ts
     */
    public String translate(String text,
                            String targetLanguage,
                            String model,
                            Double temperature,
                            String apiKey,
                            String baseUrl) throws IOException {
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content",
                        "You are a professional, authentic translation engine, only returns translations."),
                Map.of("role", "user", "content",
                        "Translate the text to " + targetLanguage + " Language, do not explain my original text:\n\n" + text)
        );
        return openAiClient.textCommand(messages, model, temperature, apiKey, baseUrl);
    }

    // ==================== analyze.command.ts — 语法分析 ====================

    /**
     * 句子结构/语法/词汇分析
     * 来源：everyone-can-use-english/src/commands/analyze.command.ts
     */
    public String analyze(String text,
                          String learningLanguage,
                          String nativeLanguage,
                          String model,
                          Double temperature,
                          String apiKey,
                          String baseUrl) throws IOException {
        String systemPrompt = String.format("""
                I speak %s. You're my %s coach, I'll provide %s text, you'll help me analyze the sentence structure, grammar, and vocabulary/phrases, and provide a detailed explanation of the text. Please return the results in the following format(but in %s):
                
                ### Sentence Structure
                (Explain each element of the sentence)
                
                ### Grammar
                (Explain the grammar of the sentence)
                
                ### Vocabulary/Phrases
                (Explain the key vocabulary and phrases used)
                """, nativeLanguage, learningLanguage, learningLanguage, nativeLanguage);

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", text)
        );
        return openAiClient.textCommand(messages, model, temperature, apiKey, baseUrl);
    }

    // ==================== extract-story.command.ts — 生词提取 ====================

    /**
     * 从文章中提取生词和习语
     * 来源：everyone-can-use-english/src/commands/extract-story.command.ts
     */
    public JsonNode extractStory(String text,
                                 String learningLanguage,
                                 String model,
                                 Double temperature,
                                 String apiKey,
                                 String baseUrl) throws IOException {
        String systemPrompt = String.format("""
                I am an %s beginner and only have a grasp of 500 high-frequency basic words. You are an %s learning assistant robot, and your task is to analyze the article I provide and extract all the meaningful words and idioms that I may not be familiar with. Specifically, it should include common words used in uncommon ways. Return in JSON format like following:
                
                { "words": ["word1", "word2", ...], "idioms": ["idiom1", "idiom2", ...] }
                """, learningLanguage, learningLanguage);

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", text)
        );
        return openAiClient.jsonCommand(messages, model, temperature, apiKey, baseUrl);
    }

    // ==================== lookup.command.ts — 词典查询 ====================

    /**
     * 单词查询（含 IPA 音标、词性、定义、上下文翻译）
     * 来源：everyone-can-use-english/src/commands/lookup.command.ts
     */
    public JsonNode lookup(String word,
                           String context,
                           String learningLanguage,
                           String nativeLanguage,
                           String model,
                           Double temperature,
                           String apiKey,
                           String baseUrl) throws IOException {
        String systemPrompt = String.format("""
                You are an %s-%s dictionary.
                I will provide "word(it also maybe a phrase)" and "context" as input, you should return the "word", "lemma", "pronunciation", "pos", "definition", "translation" and "context_translation" as output.
                If no context is provided, return the most common definition.
                Always return the output in JSON format.
                """, learningLanguage, nativeLanguage);

        String input = objectMapper.createObjectNode()
                .put("word", word)
                .put("context", context)
                .toString();

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", input)
        );
        return openAiClient.jsonCommand(messages, model, temperature, apiKey, baseUrl);
    }

    // ==================== ipa.command.ts — IPA 音标 ====================

    /**
     * 为文本中的每个单词生成 IPA 音标
     * 来源：everyone-can-use-english/src/commands/ipa.command.ts
     */
    public JsonNode generateIpa(String text,
                                String model,
                                Double temperature,
                                String apiKey,
                                String baseUrl) throws IOException {
        String systemPrompt = """
                Generate an array of JSON objects for each English word in the given text, with each object containing two keys: 'word' and 'ipa', where 'ipa' is the International Phonetic Alphabet (IPA) representation of the word. Return the array in JSON format only.
                The output should be structured like this:
                { "words": [ { "word": "hello", "ipa": "/həˈloʊ/" }, ... ] }
                """;

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", text)
        );
        return openAiClient.jsonCommand(messages, model, temperature, apiKey, baseUrl);
    }

    // ==================== refine.command.ts — 表达润色 ====================

    /**
     * 表达润色：提供更地道的改写建议
     * 来源：everyone-can-use-english/src/commands/refine.command.ts
     */
    public String refine(String text,
                         String learningLanguage,
                         String nativeLanguage,
                         String context,
                         String model,
                         Double temperature,
                         String apiKey,
                         String baseUrl) throws IOException {
        String safeContext = context != null ? context.replace("{", "{{").replace("}", "}}") : "None";
        String systemPrompt = String.format("""
                I speak %s. You're my %s coach. I'll give you my expression in %s. And I may also provide some context about my expression.
                Please try to understand my true meaning and provide several refined expressions in the native way. And explain them in %s.
                
                [Context]
                %s
                """, nativeLanguage, learningLanguage, learningLanguage, nativeLanguage, safeContext);

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", text)
        );
        return openAiClient.textCommand(messages, model, temperature, apiKey, baseUrl);
    }

    // ==================== chat-suggestion.command.ts — 聊天建议 ====================

    /**
     * 根据对话上下文生成聊天回复建议
     * 来源：everyone-can-use-english/src/commands/chat-suggestion.command.ts
     */
    public JsonNode chatSuggestion(String context,
                                   String learningLanguage,
                                   String nativeLanguage,
                                   String model,
                                   Double temperature,
                                   String apiKey,
                                   String baseUrl) throws IOException {
        String systemPrompt = String.format("""
                I speak %s. You're my %s coach. I'm chatting with foreign friends.
                I'll provide you with the context of the chat. Please provide me with at least 5 suggestions for what could I say in %s and explain them in %s.
                Reply in JSON format only.
                """, nativeLanguage, learningLanguage, learningLanguage, nativeLanguage);

        String prompt = """
                Output should be: { "suggestions": [ { "text": "suggestion in learning language", "explanation": "explanation in native language" } ] }
                Context: """ + context;

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", prompt)
        );
        return openAiClient.jsonCommand(messages, model, temperature, apiKey, baseUrl);
    }

    // ==================== summarize-topic.command.ts — 主题摘要 ====================

    /**
     * 对话主题摘要
     * 来源：everyone-can-use-english/src/commands/summarize-topic.command.ts
     */
    public String summarizeTopic(String text,
                                 String learningLanguage,
                                 String model,
                                 Double temperature,
                                 String apiKey,
                                 String baseUrl) throws IOException {
        String systemPrompt = String.format("""
                Please generate a four to five words title summarizing our conversation without any lead-in, punctuation, quotation marks, periods, symbols, bold text, or additional text. Remove enclosing quotation marks. Please use the main language of the text: %s.
                """, learningLanguage);

        String safeText = text.replace("{", "{{").replace("}", "}}");
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", safeText)
        );
        return openAiClient.textCommand(messages, model, temperature, apiKey, baseUrl);
    }
}
