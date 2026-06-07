package com.lingoai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.lingoai.grpc.asr.AsrServiceProto;
import com.lingoai.service.ai.ConversationPipelineService;
import com.lingoai.service.ai.FasterWhisperGrpcClient;
import com.lingoai.service.ai.SessionCoordinator;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时对话管线 WebSocket 处理器 v4
 *
 * 流式 ASR 升级：
 *  - 前端每 200ms 发送音频块 → WS 二进制
 *  - 后端实时转发到 gRPC StreamRecognize 双向流
 *  - Python 侧累计后一次识别，逐段返回 interim → final
 *  - ASR 结果通过 asr_result 消息实时推回前端
 */
@Slf4j
public class RealTimePipelineWebSocketHandler extends AbstractWebSocketHandler {

    private final SessionCoordinator.Manager coordinatorManager;
    private final ConversationPipelineService pipelineService;
    private final FasterWhisperGrpcClient asrGrpcClient;
    private final ObjectMapper objectMapper;

    /** wsSessionId → WebSocketSession */
    private final Map<String, WebSocketSession> wsSessions = new ConcurrentHashMap<>();
    /** wsSessionId → 关联的业务 sessionId */
    private final Map<String, String> wsToBizSession = new ConcurrentHashMap<>();
    /** wsSessionId → gRPC StreamRecognize 发送端 */
    private final Map<String, StreamObserver<AsrServiceProto.AudioChunk>> asrGrpcStreams = new ConcurrentHashMap<>();

    public RealTimePipelineWebSocketHandler(
            SessionCoordinator.Manager coordinatorManager,
            ConversationPipelineService pipelineService,
            FasterWhisperGrpcClient asrGrpcClient) {
        this.coordinatorManager = coordinatorManager;
        this.pipelineService = pipelineService;
        this.asrGrpcClient = asrGrpcClient;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String id = session.getId();
        log.info("实时管线 WS 连接: session={}", id);
        wsSessions.put(id, session);
        sendJson(session, Map.of("type", "ready", "message", "实时管线服务就绪"));
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("收到文本: {}", payload);

        try {
            Map<String, Object> msg = objectMapper.readValue(payload, Map.class);
            String type = (String) msg.getOrDefault("type", "");

            switch (type) {
                case "start_session" -> handleStartSession(session, msg);
                case "user_input" -> handleUserInput(session, msg);
                case "asr_interim" -> handleAsrInterim(session, msg);
                case "asr_end" -> handleAsrEnd(session);
                case "interrupt" -> handleInterrupt(session);
                case "end_session" -> handleEndSession(session);
                default -> sendJson(session, Map.of("type", "error", "message", "未知类型: " + type));
            }
        } catch (Exception e) {
            log.error("处理消息失败: {}", e.getMessage());
            sendJson(session, Map.of("type", "error", "message", "解析失败: " + e.getMessage()));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        String wsId = session.getId();

        // 获取或创建 gRPC 流式发送端
        StreamObserver<AsrServiceProto.AudioChunk> sender = asrGrpcStreams.get(wsId);
        if (sender == null) {
            log.info("[{}] 首个音频块，创建 gRPC StreamRecognize", wsId);
            sender = asrGrpcClient.streamRecognize(
                    wsId,
                    // onResult — gRPC 识别结果推回前端
                    result -> {
                        log.debug("[{}] ASR 流式结果: text='{}', final={}",
                                wsId, truncate(result.getText()), result.getIsFinal());
                        sendJson(wsSessions.get(wsId), Map.of(
                                "type", "asr_result",
                                "text", result.getText(),
                                "final", result.getIsFinal()
                        ));
                    },
                    // onError
                    error -> {
                        log.error("[{}] gRPC ASR 错误: {}", wsId, error.getMessage());
                        sendJson(wsSessions.get(wsId), Map.of(
                                "type", "error", "message", "ASR 错误: " + error.getMessage()
                        ));
                        asrGrpcStreams.remove(wsId);
                    },
                    // onComplete
                    () -> {
                        log.info("[{}] gRPC ASR 完成", wsId);
                        asrGrpcStreams.remove(wsId);
                    }
            );
            asrGrpcStreams.put(wsId, sender);
        }

        // 发送音频块
        try {
            byte[] data = new byte[message.getPayloadLength()];
            message.getPayload().get(data);

            synchronized (sender) {
                sender.onNext(AsrServiceProto.AudioChunk.newBuilder()
                        .setAudioData(ByteString.copyFrom(data))
                        .setIsEnd(false)
                        .build());
            }
            log.debug("[{}] 转发音频块: {} bytes", wsId, data.length);
        } catch (Exception e) {
            log.error("[{}] 转发音频块失败: {}", wsId, e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String id = session.getId();
        log.info("WS 关闭: session={}, status={}", id, status);
        cleanupSession(id);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        String id = session.getId();
        log.error("WS 传输错误: session={}, error={}", id, exception.getMessage());
        cleanupSession(id);
    }

    private void cleanupSession(String wsSessionId) {
        // 关闭 gRPC 流
        StreamObserver<AsrServiceProto.AudioChunk> sender = asrGrpcStreams.remove(wsSessionId);
        if (sender != null) {
            try {
                synchronized (sender) {
                    sender.onCompleted();
                }
            } catch (Exception ignored) {}
        }
        // 清理业务会话
        String bizSessionId = wsToBizSession.remove(wsSessionId);
        if (bizSessionId != null) {
            coordinatorManager.remove(bizSessionId);
        }
        wsSessions.remove(wsSessionId);
    }

    // ==================== 消息处理器 ====================

    private void handleStartSession(WebSocketSession wsSession, Map<String, Object> msg) {
        String sessionId = (String) msg.getOrDefault("sessionId", wsSession.getId());
        wsToBizSession.put(wsSession.getId(), sessionId);

        var builder = ConversationPipelineService.PipelineConfig.builder()
                .agentName((String) msg.getOrDefault("agentName", "Ava"))
                .llmEngine((String) msg.getOrDefault("llmEngine", "openai"))
                .llmModel((String) msg.getOrDefault("llmModel", "gpt-4o"))
                .useTts((Boolean) msg.getOrDefault("useTts", true))
                .ttsEngine((String) msg.getOrDefault("ttsEngine", "piper"))
                .ttsVoice((String) msg.getOrDefault("ttsVoice", "en_US-amy-medium"));

        if (msg.containsKey("llmApiKey")) builder.llmApiKey((String) msg.get("llmApiKey"));
        if (msg.containsKey("llmBaseUrl")) builder.llmBaseUrl((String) msg.get("llmBaseUrl"));
        if (msg.containsKey("llmTemperature")) {
            builder.llmTemperature(((Number) msg.get("llmTemperature")).doubleValue());
        }

        ConversationPipelineService.PipelineConfig config = builder.build();

        SessionCoordinator coordinator = coordinatorManager.getOrCreate(
                sessionId, config,
                text -> sendText(wsSession, text),
                audioData -> sendBinaryData(wsSession, audioData),
                () -> sendJson(wsSession, Map.of("type", "tts_start")),
                () -> sendJson(wsSession, Map.of("type", "tts_done"))
        );

        sendJson(wsSession, Map.of(
                "type", "session_started", "sessionId", sessionId,
                "agentName", coordinator.getAgentName(),
                "state", coordinator.getState().name()
        ));

        log.info("会话启动: sessionId={}, agent={}", sessionId, config.getAgentName());
    }

    private void handleUserInput(WebSocketSession wsSession, Map<String, Object> msg) {
        String sessionId = resolveSessionId(wsSession, msg);
        String text = (String) msg.get("text");

        if (text == null || text.trim().isEmpty()) {
            sendJson(wsSession, Map.of("type", "error", "message", "输入文本不能为空"));
            return;
        }

        SessionCoordinator coordinator = coordinatorManager.get(sessionId);
        if (coordinator == null) {
            sendJson(wsSession, Map.of("type", "error", "message", "会话未启动"));
            return;
        }

        // 解析对话历史
        List<Map<String, String>> history = new ArrayList<>();
        Object historyRaw = msg.get("history");
        if (historyRaw instanceof List) {
            for (Object item : (List<Object>) historyRaw) {
                if (item instanceof Map) {
                    Map<String, Object> entry = (Map<String, Object>) item;
                    Map<String, String> clean = new HashMap<>();
                    if (entry.get("role") != null) clean.put("role", entry.get("role").toString());
                    if (entry.get("content") != null) clean.put("content", entry.get("content").toString());
                    if (clean.containsKey("role") && clean.containsKey("content")) {
                        history.add(clean);
                    }
                }
            }
        }

        log.info("[{}] 用户输入: text={}, history={}条", sessionId, truncate(text), history.size());
        coordinator.onUserInput(text.trim(), history);
    }

    private void handleAsrInterim(WebSocketSession wsSession, Map<String, Object> msg) {
        String sessionId = resolveSessionId(wsSession, msg);
        String interimText = (String) msg.get("text");
        if (interimText == null || interimText.trim().isEmpty()) return;

        SessionCoordinator coordinator = coordinatorManager.get(sessionId);
        if (coordinator == null) return;

        coordinator.onAsrInterim(interimText.trim());
        log.debug("[{}] ASR interim: {}", sessionId, truncate(interimText));
    }

    /**
     * ASR 音频结束 → 发送 is_end=true 关闭 gRPC 流
     */
    private void handleAsrEnd(WebSocketSession wsSession) {
        String wsId = wsSession.getId();
        StreamObserver<AsrServiceProto.AudioChunk> sender = asrGrpcStreams.get(wsId);
        if (sender == null) {
            log.warn("[{}] ASR 结束但无活跃 gRPC 流", wsId);
            sendJson(wsSession, Map.of("type", "error", "message", "未收到音频数据"));
            return;
        }

        log.info("[{}] ASR 结束，发送 is_end=true 到 gRPC", wsId);
        try {
            synchronized (sender) {
                sender.onNext(AsrServiceProto.AudioChunk.newBuilder()
                        .setIsEnd(true)
                        .build());
                sender.onCompleted();
            }
        } catch (Exception e) {
            log.error("[{}] 关闭 gRPC 流失败: {}", wsId, e.getMessage());
        }
        asrGrpcStreams.remove(wsId);
    }

    private void handleInterrupt(WebSocketSession wsSession) {
        String sessionId = resolveSessionId(wsSession, Map.of());
        SessionCoordinator coordinator = coordinatorManager.get(sessionId);
        if (coordinator == null) {
            sendJson(wsSession, Map.of("type", "error", "message", "会话未启动"));
            return;
        }
        coordinator.interrupt();
    }

    private void handleEndSession(WebSocketSession wsSession) {
        String wsId = wsSession.getId();
        String sessionId = wsToBizSession.get(wsId);
        coordinatorManager.remove(sessionId);
        wsToBizSession.remove(wsId);
        cleanupSession(wsId);
        sendJson(wsSession, Map.of("type", "session_ended", "sessionId", sessionId));
    }

    // ==================== 辅助方法 ====================

    private String resolveSessionId(WebSocketSession wsSession, Map<String, Object> msg) {
        if (msg.containsKey("sessionId")) return (String) msg.get("sessionId");
        String bizId = wsToBizSession.get(wsSession.getId());
        return bizId != null ? bizId : wsSession.getId();
    }

    private void sendJson(WebSocketSession session, Object data) {
        if (session == null || !session.isOpen()) return;
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(data)));
            }
        } catch (IOException e) {
            log.error("发送 JSON 失败: {}", e.getMessage());
        }
    }

    private void sendText(WebSocketSession session, String text) {
        if (session == null || !session.isOpen()) return;
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(text));
            }
        } catch (IOException e) {
            log.error("发送文本失败: {}", e.getMessage());
        }
    }

    private void sendBinaryData(WebSocketSession session, byte[] data) {
        if (session == null || !session.isOpen()) {
            log.warn("sendBinaryData: 会话已关闭或为空, data={} bytes", data != null ? data.length : 0);
            return;
        }
        log.info("发送 TTS 音频: {} bytes", data.length);
        try {
            synchronized (session) {
                session.sendMessage(new BinaryMessage(ByteBuffer.wrap(data)));
            }
        } catch (IOException e) {
            log.error("发送二进制失败: {}", e.getMessage());
        }
    }

    private String truncate(String s) {
        return s != null && s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}
