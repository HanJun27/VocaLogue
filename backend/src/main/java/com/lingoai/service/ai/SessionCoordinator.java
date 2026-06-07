package com.lingoai.service.ai;

import com.lingoai.constants.AgentTemplates;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static java.util.Collections.unmodifiableMap;

/**
 * 会话协调器 — 状态机 v2
 *
 * 管理每个会话的 LISTENING → THINKING → SPEAKING 状态转换，
 * 协调 ASR → LLM → TTS 管线的流式执行，支持随时打断、推测性生成、token 级流式 TTS。
 *
 * 核心优化：
 *  1. Token 级流式 TTS：LLM token 边收边积累，按 标点+长度双阈值 即时触发 Piper TTS 合成
 *  2. TTS 句柄级中断：跟踪当前 TTS Future，打断时取消
 *  3. 推测性生成：ASR interim 到达时用小模型预生成
 *  4. 首 token 超时：500ms 超时检测
 *  5. 对话历史支持
 *
 * 状态转换图：
 *   LISTENING ──(用户说完)──→ THINKING
 *   THINKING  ──(首token)──→ SPEAKING (TTS 开始流式输出)
 *   SPEAKING  ──(TTS结束)──→ LISTENING
 *   任意状态 ─(interrupt)──→ LISTENING
 */
@Slf4j
public class SessionCoordinator {

    public enum State {
        LISTENING, THINKING, SPEAKING
    }

    @Getter
    private final String sessionId;
    @Getter
    private volatile State state = State.LISTENING;

    // 依赖
    private final ConversationPipelineService.PipelineConfig config;
    private final LlmService llmService;
    private final TtsService ttsService;
    private final ConversationPipelineService pipelineService;

    // 打断控制
    private volatile boolean cancelled = false;
    private volatile Future<?> llmFuture;
    private volatile Future<?> ttsFuture;
    private final Object ttsLock = new Object();

    // LLM 积累
    private final StringBuilder llmAccumulator = new StringBuilder();
    // 用于 TTS 的"待合成"缓冲区
    private final StringBuilder ttsBuffer = new StringBuilder();

    // 推测性生成
    private volatile String speculativeResponse = null;
    private volatile boolean speculativeReady = false;

    // 回调
    private final Consumer<String> onTextMessage;
    private final Consumer<byte[]> onAudioChunk;
    private final Runnable onSpeakingStarted;
    private final Runnable onSpeakingFinished;

    @Getter
    private final PipelineMetrics metrics = new PipelineMetrics();

    // 线程池
    private static final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "coordinator-");
        t.setDaemon(true);
        return t;
    });
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "coordinator-timer-");
        t.setDaemon(true);
        return t;
    });

    // ==================== TTS 流式阈值（针对 Piper 本地优化） ====================
    /** 最小积累字符数：积累到这个数量且遇到标点立即触发 TTS */
    private static final int TTS_FLUSH_MIN_CHARS = 15;
    /** 最大积累字符数：无论是否遇到标点都强制触发 TTS */
    private static final int TTS_FLUSH_MAX_CHARS = 100;
    /** 首 token 超时 (ms) */
    private static final long FIRST_TOKEN_TIMEOUT_MS = 500;

    // ==================== 推测性生成配置 ====================
    private static final boolean SPECULATIVE_ENABLED = true;
    private static final double SPECULATIVE_SIMILARITY_THRESHOLD = 0.6;

    public SessionCoordinator(
            String sessionId,
            ConversationPipelineService.PipelineConfig config,
            LlmService llmService,
            TtsService ttsService,
            ConversationPipelineService pipelineService,
            Consumer<String> onTextMessage,
            Consumer<byte[]> onAudioChunk,
            Runnable onSpeakingStarted,
            Runnable onSpeakingFinished) {
        this.sessionId = sessionId;
        this.config = config;
        this.llmService = llmService;
        this.ttsService = ttsService;
        this.pipelineService = pipelineService;
        this.onTextMessage = onTextMessage;
        this.onAudioChunk = onAudioChunk;
        this.onSpeakingStarted = onSpeakingStarted;
        this.onSpeakingFinished = onSpeakingFinished;
    }

    // ==================== 公共方法 ====================

    /**
     * 用户输入完成（ASR final 文本或键盘输入），进入 THINKING
     *
     * @param text      ASR 最终识别文本
     * @param history   本次会话的对话历史（用于 LLM 上下文）
     */
    public synchronized void onUserInput(String text, List<Map<String, String>> history) {
        if (state == State.THINKING || state == State.SPEAKING) {
            log.warn("[{}] onUserInput 被阻止，状态={}", sessionId, state);
            return;
        }
        log.info("[{}] ===== onUserInput: text='{}', history={}条 =====", sessionId, truncate(text), history.size());
        state = State.THINKING;
        cancelled = false;
        llmAccumulator.setLength(0);
        ttsBuffer.setLength(0);
        speculativeReady = false;
        speculativeResponse = null;

        // 如果推测性结果可用且与最终文本匹配，直接使用
        if (speculativeReady && speculativeResponse != null && isSimilar(text, speculativeResponse)) {
            log.info("[{}] 使用推测性生成结果，跳过 LLM 调用", sessionId);
            onTextMessage.accept("{\"type\":\"llm_start\",\"speculative\":true}");
            onTextMessage.accept("{\"type\":\"llm_done\",\"fullText\":\"" +
                    speculativeResponse.replace("\"", "'").replace("\n", "\\n") + "\"}");
            if (config.isUseTts() && !speculativeResponse.isEmpty()) {
                synthesizeAndPlay(speculativeResponse);
            }
            state = State.LISTENING;
            onTextMessage.accept("{\"type\":\"state_change\",\"state\":\"LISTENING\"}");
            return;
        }

        // 异步启动 LLM 流式生成
        List<Map<String, String>> historyCopy = history != null ? new ArrayList<>(history) : new ArrayList<>();
        llmFuture = executor.submit(() -> {
            try {
                streamLlm(text, historyCopy);
            } catch (Exception e) {
                if (!cancelled) {
                    log.error("[{}] LLM 流式生成失败", sessionId, e);
                    onTextMessage.accept("{\"type\":\"error\",\"message\":\"" +
                            e.getMessage().replace("\"", "'") + "\"}");
                }
            }
        });
    }

    /**
     * 兼容旧 API：不带 history
     */
    public synchronized void onUserInput(String text) {
        onUserInput(text, new ArrayList<>());
    }

    /**
     * ASR 中间结果到达 — 触发推测性生成
     *
     * @param interimText ASR 当前识别的中间文本
     */
    public synchronized void onAsrInterim(String interimText) {
        if (!SPECULATIVE_ENABLED || state != State.LISTENING) return;
        if (interimText == null || interimText.trim().length() < 5) return;
        if (speculativeReady) return; // 已有推测结果

        log.info("[{}] ASR interim 到达，启动推测性生成: text={}", sessionId, truncate(interimText));

        // 用 interim 文本调小模型快速生成
        String interimSnapshot = interimText;
        executor.submit(() -> {
            try {
                String speculative = speculativeGenerate(interimSnapshot);
                if (speculative != null && !speculative.isEmpty() && !cancelled) {
                    speculativeResponse = speculative;
                    speculativeReady = true;
                    log.info("[{}] 推测性生成完成: len={}", sessionId, speculative.length());
                }
            } catch (Exception e) {
                log.warn("[{}] 推测性生成失败: {}", sessionId, e.getMessage());
            }
        });
    }

    /**
     * 用户打断 — 从任意状态回到 LISTENING
     */
    public synchronized void interrupt() {
        if (state == State.LISTENING) return;
        log.info("[{}] 打断，当前状态: {}", sessionId, state);
        cancelled = true;
        state = State.LISTENING;
        ttsBuffer.setLength(0);

        cancelLlm();
        cancelTts();

        onTextMessage.accept("{\"type\":\"interrupted\",\"state\":\"LISTENING\"}");
    }

    public void destroy() {
        log.info("[{}] 销毁", sessionId);
        cancelled = true;
        cancelLlm();
        cancelTts();
    }

    public String getAgentName() {
        AgentTemplates agent = findAgent(config.getAgentName());
        return agent != null ? agent.getName() : config.getAgentName();
    }

    public String getAgentSystemPrompt() {
        AgentTemplates agent = findAgent(config.getAgentName());
        return agent != null ? agent.getSystemPrompt() : null;
    }

    // ==================== LLM 流式生成（带 Token 级 TTS 触发） ====================

    private void streamLlm(String userText, List<Map<String, String>> history) {
        llmStreaming = true;

        AgentTemplates agent = findAgent(config.getAgentName());
        String systemPrompt = agent != null ? agent.getSystemPrompt() : null;

        // 完整消息列表：history + 当前用户消息
        List<Map<String, String>> messages = new ArrayList<>(history);
        messages.add(Map.of("role", "user", "content", userText));

        String engine = config.getLlmEngine() != null ? config.getLlmEngine() : "openai";
        String model = config.getLlmModel() != null ? config.getLlmModel() : getDefaultModelForEngine(engine);
        Double temperature = config.getLlmTemperature() != null ? config.getLlmTemperature()
                : (agent != null ? agent.getTemperature() : 0.8);

        log.info("[{}] ===== LLM 流式开始: engine={}, model={} =====", sessionId, engine, model);
        metrics.setLlmEngine(engine);
        metrics.setLlmModel(model);
        metrics.setCurrentState("THINKING");
        metrics.setLastUpdatedAt(System.currentTimeMillis());

        onTextMessage.accept("{\"type\":\"llm_start\"}");
        llmStartTime = System.currentTimeMillis();

        // 首 token 超时检测
        final ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            if (llmAccumulator.length() == 0 && !cancelled) {
                log.warn("[{}] LLM 首 token 超时 ({}ms)", sessionId, FIRST_TOKEN_TIMEOUT_MS);
                onTextMessage.accept("{\"type\":\"llm_timeout\"}");
            }
        }, FIRST_TOKEN_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        llmFuture = llmService.streamChatCancellable(
                messages, systemPrompt, model, temperature,
                config.getLlmApiKey(), config.getLlmBaseUrl(), engine,
                line -> processLlmToken(line, timeoutTask)
        );

        try {
            llmFuture.get();
            timeoutTask.cancel(false);
        } catch (CancellationException e) {
            log.info("[{}] LLM 流取消", sessionId);
            timeoutTask.cancel(false);
            llmStreaming = false;
            return;
        } catch (Exception e) {
            timeoutTask.cancel(false);
            if (!cancelled) log.error("[{}] LLM 流异常", sessionId, e);
            llmStreaming = false;
            return;
        }

        if (cancelled) {
            llmStreaming = false;
            return;
        }

        // LLM 流结束 → 把 ttsBuffer 中剩余文本全部刷给 TTS
        String fullResponse = llmAccumulator.toString();
        log.info("[{}] ===== LLM 完成: text='{}', ttsBuffer={} chars =====", sessionId, truncate(fullResponse), ttsBuffer.length());

        if (fullResponse.isEmpty()) {
            log.warn("[{}] LLM 返回空内容，可能是 API Key 无效或网络错误", sessionId);
            onTextMessage.accept("{\"type\":\"error\",\"message\":\"LLM returned empty response. Please check your API key and network.\"}");
        }

        onTextMessage.accept("{\"type\":\"llm_done\",\"fullText\":\"" +
                fullResponse.replace("\"", "'").replace("\n", "\\n") + "\"}");
        llmStreaming = false;

        // 刷新 TTS 剩余缓冲区
        flushTtsBuffer(true);

        // 更新端到端延迟指标
        if (llmStartTime > 0) {
            metrics.setEndToEndLatency(System.currentTimeMillis() - llmStartTime);
        }
        metrics.setCurrentState("LISTENING");
        metrics.setLastUpdatedAt(System.currentTimeMillis());

        // 回到 LISTENING
        state = State.LISTENING;
        onTextMessage.accept("{\"type\":\"state_change\",\"state\":\"LISTENING\"}");
        llmStartTime = 0;
    }

    // volatile 标记
    private volatile boolean llmStreaming = false;
    private volatile long llmStartTime = 0;

    /**
     * 处理单个 LLM token
     */
    private void processLlmToken(String line, ScheduledFuture<?> timeoutTask) {
        if (cancelled) return;

        String content = parseSseContent(line);
        if (content == null || content.isEmpty()) return;

        // 取消首 token 超时
        if (llmAccumulator.length() == 0) {
            timeoutTask.cancel(false);
            log.debug("[{}] 首 token 到达", sessionId);
            // 记录首 token 延迟
            if (llmStartTime > 0) {
                metrics.setLlmFirstTokenLatency(System.currentTimeMillis() - llmStartTime);
                metrics.setCurrentState("SPEAKING");
                metrics.setLastUpdatedAt(System.currentTimeMillis());
            }
        }

        llmAccumulator.append(content);
        ttsBuffer.append(content);

        // 实时推送文本到前端
        String safeContent = content.replace("\"", "'").replace("\n", "\\n");
        onTextMessage.accept("{\"type\":\"llm_token\",\"content\":\"" + safeContent + "\"}");

        // === Token 级流式 TTS 触发逻辑 ===
        // 双阈值：遇到句子结束标点 或者 积累足够多字符
        boolean hasSentenceEnd = content.contains(".") || content.contains("!") || content.contains("?") || content.contains("\n");
        boolean hasClauseBreak = content.contains(",") || content.contains(";") || content.contains(":") || content.contains("—");

        if (hasSentenceEnd && ttsBuffer.length() >= TTS_FLUSH_MIN_CHARS) {
            flushTtsBuffer(false);
        } else if (hasClauseBreak && ttsBuffer.length() >= TTS_FLUSH_MIN_CHARS + 10) {
            flushTtsBuffer(false);
        } else if (ttsBuffer.length() >= TTS_FLUSH_MAX_CHARS) {
            // 强制刷新（找最后标点切分，避免截断单词）
            flushTtsBuffer(false);
        }
    }

    // ==================== Token 级流式 TTS — Piper 优化版 ====================

    /**
     * 刷新 TTS 缓冲区：把 ttsBuffer 的文本发送给 Piper 合成
     *
     * @param force 是否强制 flush（LLM 结束时 = true，此时不管有没有标点都合成）
     */
    private void flushTtsBuffer(boolean force) {
        if (!config.isUseTts()) return;
        if (ttsBuffer.length() == 0) return;

        String textToSynthesize;
        if (force) {
            // 强制：整段合成
            textToSynthesize = ttsBuffer.toString();
            ttsBuffer.setLength(0);
        } else {
            // 非强制：找到最后一个句号/换行处切分
            textToSynthesize = extractFlushText();
            if (textToSynthesize == null || textToSynthesize.trim().isEmpty()) return;
        }

        if (textToSynthesize.trim().isEmpty()) return;

        final String segment = textToSynthesize.trim();
        log.debug("[{}] TTS flush: len={}, text={}", sessionId, segment.length(), truncate(segment));

        // 在锁外等待上一个 TTS 完成（避免持锁等待导致打断死锁）
        // 用户说话时 VAD → interruptPlayback() → WS interrupt → cancelTts() 需要 ttsLock
        // 如果我们在锁内 ttsFuture.get() 阻塞，interrupt 就进不来
        Future<?> prevFuture;
        synchronized (ttsLock) {
            if (cancelled) return;
            prevFuture = ttsFuture;
        }
        if (prevFuture != null && !prevFuture.isDone()) {
            log.debug("[{}] 等待上一个 TTS 完成（锁外等待，可打断）", sessionId);
            try {
                prevFuture.get();
            } catch (Exception ignored) {
                // interrupted / cancelled — 下一个 segment 会被 cancelled 检查跳过
            }
        }

        synchronized (ttsLock) {
            if (cancelled) return;

            ttsFuture = executor.submit(() -> {
                try {
                    String ttsEngine = config.getTtsEngine() != null ? config.getTtsEngine() : "piper";
                    String ttsVoice = config.getTtsVoice() != null ? config.getTtsVoice() : "en_US-amy-medium";

                    log.info("[{}] ===== TTS 合成请求: engine={}, voice={}, segment={} =====",
                            sessionId, ttsEngine, ttsVoice, truncate(segment));

                    TtsService.TtsResult result = ttsService.generateSpeech(
                            segment, ttsEngine, "tts-1", ttsVoice, null, null);

                    if (cancelled) return; // 打断后丢弃

                    if (result.getAudioData() != null && result.getAudioData().length > 0) {
                        log.info("[{}] TTS 合成成功: engine={}, audio={} bytes, segment='{}'",
                                sessionId, ttsEngine, result.getAudioData().length, truncate(segment));
                        state = State.SPEAKING;
                        onSpeakingStarted.run();
                        onAudioChunk.accept(result.getAudioData());
                        onSpeakingFinished.run();
                        state = State.LISTENING;
                    } else {
                        log.warn("[{}] TTS 合成返回空音频: engine={}, segment='{}'",
                                sessionId, ttsEngine, truncate(segment));
                    }
                } catch (Exception e) {
                    if (!cancelled) {
                        log.error("[{}] TTS flush 失败: {} (segment='{}')", sessionId, e.getMessage(), truncate(segment));
                    }
                }
            });
        }
    }

    /**
     * 在 ttsBuffer 中从末尾往前找最后一个句子/子句边界，返回之前部分
     */
    private String extractFlushText() {
        String buf = ttsBuffer.toString();
        int len = buf.length();

        // 1. 找最后的句号/问号/感叹号/换行
        int lastSentenceEnd = -1;
        for (int i = len - 1; i >= 0; i--) {
            char c = buf.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                lastSentenceEnd = i + 1; // 包含这个标点
                break;
            }
        }
        if (lastSentenceEnd > TTS_FLUSH_MIN_CHARS) {
            String result = buf.substring(0, lastSentenceEnd);
            ttsBuffer.delete(0, lastSentenceEnd);
            return result;
        }

        // 2. 找最后的逗号/分号/冒号
        int lastClauseEnd = -1;
        for (int i = len - 1; i >= 0; i--) {
            char c = buf.charAt(i);
            if (c == ',' || c == ';' || c == ':') {
                lastClauseEnd = i + 1;
                break;
            }
        }
        if (lastClauseEnd > TTS_FLUSH_MIN_CHARS + 10) {
            String result = buf.substring(0, lastClauseEnd);
            ttsBuffer.delete(0, lastClauseEnd);
            return result;
        }

        // 3. 强制截断：在最近空格处切分（避免截断单词）
        if (len >= TTS_FLUSH_MAX_CHARS) {
            int lastSpace = buf.lastIndexOf(' ', TTS_FLUSH_MAX_CHARS - 1);
            if (lastSpace > TTS_FLUSH_MIN_CHARS) {
                String result = buf.substring(0, lastSpace);
                ttsBuffer.delete(0, lastSpace + 1);
                return result;
            }
        }

        return null; // 不到 flush 时机
    }

    // ==================== 完整回复 TTS（fallback，当流式 TTS 未启用时） ====================

    private void synthesizeAndPlay(String text) {
        try {
            state = State.SPEAKING;
            onSpeakingStarted.run();
            onTextMessage.accept("{\"type\":\"tts_start\"}");

            String ttsEngine = config.getTtsEngine() != null ? config.getTtsEngine() : "piper";
            String ttsVoice = config.getTtsVoice() != null ? config.getTtsVoice() : "en_US-amy-medium";

            // Piper 本地直接合成整段（对于短文本可以）
            TtsService.TtsResult result = ttsService.generateSpeech(
                    text, ttsEngine, "tts-1", ttsVoice, null, null);

            if (result.getAudioData() != null && result.getAudioData().length > 0) {
                onAudioChunk.accept(result.getAudioData());
            }

            onSpeakingFinished.run();
            onTextMessage.accept("{\"type\":\"tts_done\"}");
        } catch (Exception e) {
            log.error("[{}] TTS 失败: {}", sessionId, e.getMessage());
            onTextMessage.accept("{\"type\":\"error\",\"message\":\"TTS: " +
                    e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    // ==================== 推测性生成 ====================

    /**
     * 用 ASR interim 文本调小模型快速生成回复
     * 使用更小的模型（如 deepseek-chat 或 gpt-4o-mini）以换取速度
     */
    private String speculativeGenerate(String interimText) throws IOException {
        // 使用轻量模型：优先 deepseek（速度快），fallback gpt-4o-mini
        String speculativeEngine = "deepseek";
        String speculativeModel = "deepseek-chat";
        String speculativeApiKey = config.getLlmApiKey();
        String speculativeBaseUrl = config.getLlmBaseUrl();

        // 如果 deepseek 未配置，用原模型的 mini 版本
        if (speculativeApiKey == null || speculativeApiKey.isEmpty()) {
            speculativeEngine = "openai";
            speculativeModel = "gpt-4o-mini";
            speculativeApiKey = config.getLlmApiKey();
        }

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", interimText));

        // 设置低 temperature 以获取更确定的回复
        try {
            String response = llmService.chat(
                    messages, "You are a helpful assistant. Keep your response concise and natural.",
                    speculativeModel, 0.3, speculativeApiKey, speculativeBaseUrl, speculativeEngine);

            if (response != null && response.length() > 10 && !"[No content]".equals(response)) {
                return response;
            }
        } catch (Exception e) {
            log.warn("[{}] 推测性生成引擎 {} 失败: {}", sessionId, speculativeEngine, e.getMessage());
        }

        return null;
    }

    /**
     * 判断推测结果是否与最终结果"足够相似"，决定是否复用
     * 简单实现：检查编辑距离比例
     */
    private boolean isSimilar(String userText, String speculative) {
        if (speculative == null) return false;
        // 简化的相似度判断：如果用户说的文本相似度较高则复用
        // 实际可以使用 word-level 编辑距离或向量相似度
        String[] userWords = userText.toLowerCase().split("\\s+");
        String[] specWords = speculative.toLowerCase().split("\\s+");
        if (userWords.length < 3 || specWords.length < 3) return false;

        // 计算 Jaccard 相似度
        Set<String> userSet = new HashSet<>(Arrays.asList(userWords));
        Set<String> specSet = new HashSet<>(Arrays.asList(specWords));
        Set<String> intersection = new HashSet<>(userSet);
        intersection.retainAll(specSet);
        Set<String> union = new HashSet<>(userSet);
        union.addAll(specSet);

        double jaccard = (double) intersection.size() / (double) union.size();
        return jaccard >= SPECULATIVE_SIMILARITY_THRESHOLD;
    }

    // ==================== 辅助方法 ====================

    private void cancelLlm() {
        if (llmFuture != null && !llmFuture.isDone()) {
            llmFuture.cancel(true);
            llmFuture = null;
        }
        llmStreaming = false;
    }

    private void cancelTts() {
        synchronized (ttsLock) {
            if (ttsFuture != null && !ttsFuture.isDone()) {
                ttsFuture.cancel(true);
                ttsFuture = null;
            }
        }
    }

    private String parseSseContent(String line) {
        if (line == null || line.isEmpty()) return null;
        if (!line.startsWith("data: ")) return null;
        String data = line.substring(6).trim();
        if ("[DONE]".equals(data)) return null;
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var json = mapper.readTree(data);
            var choices = json.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                return choices.get(0).path("delta").path("content").asText("");
            }
        } catch (Exception ignored) {}
        return null;
    }

    private AgentTemplates findAgent(String agentName) {
        if (agentName == null) return null;
        for (AgentTemplates a : AgentTemplates.values()) {
            if (a.getName().equalsIgnoreCase(agentName)) return a;
        }
        return null;
    }

    private String truncate(String s) {
        return s != null && s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }

    private String getDefaultModelForEngine(String engine) {
        if (engine == null) return "gpt-4o";
        return switch (engine.toLowerCase()) {
            case "deepseek" -> "deepseek-chat";
            case "glm" -> "glm-4-flash";
            case "qianwen" -> "qwen-turbo";
            case "doubao" -> "doubao-pro-32k";
            case "enjoyai" -> "openai/gpt-4o";
            case "ollama" -> "llama3";
            default -> "gpt-4o";
        };
    }

    /**
     * 管线延迟指标
     */
    @lombok.Data
    public static class PipelineMetrics {
        /** ASR final → LLM 首 token (ms) */
        private long llmFirstTokenLatency = -1;
        /** LLM 首 token → TTS 首音频 (ms) */
        private long ttsFirstAudioLatency = -1;
        /** 完整一轮端到端 (ms) */
        private long endToEndLatency = -1;
        /** 当前 TTS 引擎 */
        private String ttsEngine = "piper";
        /** 当前 LLM 引擎 */
        private String llmEngine = "";
        /** 当前 LLM 模型 */
        private String llmModel = "";
        /** 当前状态 */
        private String currentState = "LISTENING";
        /** 最后更新时间 */
        private long lastUpdatedAt = System.currentTimeMillis();
    }

    // ==================== 管理器 ====================

    @Slf4j
    @org.springframework.stereotype.Service
    public static class Manager {

        private final Map<String, SessionCoordinator> coordinators = new ConcurrentHashMap<>();
        private final LlmService llmService;
        private final TtsService ttsService;
        private final ConversationPipelineService pipelineService;

        public Manager(LlmService llmService, TtsService ttsService,
                       ConversationPipelineService pipelineService) {
            this.llmService = llmService;
            this.ttsService = ttsService;
            this.pipelineService = pipelineService;
        }

        public SessionCoordinator getOrCreate(
                String sessionId,
                ConversationPipelineService.PipelineConfig config,
                Consumer<String> onTextMessage,
                Consumer<byte[]> onAudioChunk,
                Runnable onSpeakingStarted,
                Runnable onSpeakingFinished) {

            return coordinators.computeIfAbsent(sessionId, id -> {
                log.info("创建协调器: sessionId={}", id);
                return new SessionCoordinator(
                        id, config, llmService, ttsService, pipelineService,
                        onTextMessage, onAudioChunk, onSpeakingStarted, onSpeakingFinished
                );
            });
        }

        public SessionCoordinator get(String sessionId) {
            return coordinators.get(sessionId);
        }

        public void remove(String sessionId) {
            SessionCoordinator c = coordinators.remove(sessionId);
            if (c != null) { c.destroy(); log.info("移除协调器: {}", sessionId); }
        }

        public Map<String, SessionCoordinator> getCoordinators() {
            return unmodifiableMap(coordinators);
        }

        public int getActiveCount() { return coordinators.size(); }

        public void clear() {
            coordinators.values().forEach(SessionCoordinator::destroy);
            coordinators.clear();
            log.info("清理所有协调器");
        }
    }
}
