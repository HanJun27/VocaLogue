package com.lingoai.config;

import com.lingoai.grpc.asr.AsrServiceProto;
import com.lingoai.service.ai.FasterWhisperGrpcClient;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Faster-Whisper ASR WebSocket 转发处理器
 *
 * 架构：
 *   前端 WebSocket → Spring Boot → gRPC → Python ASR 服务
 *
 * 流程：
 *   1. 前端通过 WebSocket 发送音频二进制数据
 *   2. 后端通过 gRPC 流式转发到 Python 服务
 *   3. Python 服务识别后通过 gRPC 流式返回结果
 *   4. 后端将结果通过 WebSocket 返回给前端
 */
@Slf4j
public class FasterWhisperWebSocketHandler extends AbstractWebSocketHandler {

    private final FasterWhisperGrpcClient grpcClient;

    public FasterWhisperWebSocketHandler(FasterWhisperGrpcClient grpcClient) {
        this.grpcClient = grpcClient;
    }

    /** session_id → gRPC 发送端 */
    private final Map<String, StreamObserver<AsrServiceProto.AudioChunk>> grpcSenders = new ConcurrentHashMap<>();

    /** session_id → 原始 WebSocket session */
    private final Map<String, WebSocketSession> wsSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        log.info("ASR WebSocket 连接建立: session={}", sessionId);
        wsSessions.put(sessionId, session);

        // 创建 gRPC 流式识别连接
        StreamObserver<AsrServiceProto.AudioChunk> sender = grpcClient.streamRecognize(
                sessionId,
                // onResult: 识别结果 → 发给前端
                result -> sendToClient(sessionId, buildResultJson(result)),
                // onError
                error -> sendToClient(sessionId, buildErrorJson(error.getMessage())),
                // onComplete
                () -> {
                    sendToClient(sessionId, "{\"type\":\"complete\",\"message\":\"识别完成\"}");
                    cleanup(sessionId);
                }
        );

        grpcSenders.put(sessionId, sender);
        sendToClient(sessionId, "{\"type\":\"ready\",\"message\":\"ASR 服务就绪\"}");
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        String sessionId = session.getId();
        ByteBuffer buffer = message.getPayload().asReadOnlyBuffer();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);

        StreamObserver<AsrServiceProto.AudioChunk> sender = grpcSenders.get(sessionId);
        if (sender != null) {
            AsrServiceProto.AudioChunk chunk = AsrServiceProto.AudioChunk.newBuilder()
                    .setAudioData(ByteString.copyFrom(data))
                    .setIsEnd(false)
                    .setSessionId(sessionId)
                    .build();
            sender.onNext(chunk);
        } else {
            log.warn("未找到 session 的 gRPC 发送端: {}", sessionId);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        String sessionId = session.getId();

        log.debug("收到前端文本消息: {}", payload);

        // 处理结束信号
        if (payload.contains("\"end\"")) {
            StreamObserver<AsrServiceProto.AudioChunk> sender = grpcSenders.get(sessionId);
            if (sender != null) {
                AsrServiceProto.AudioChunk chunk = AsrServiceProto.AudioChunk.newBuilder()
                        .setIsEnd(true)
                        .setSessionId(sessionId)
                        .build();
                sender.onNext(chunk);
                sender.onCompleted();
                grpcSenders.remove(sessionId);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        log.info("ASR WebSocket 连接关闭: session={}, status={}", sessionId, status);
        cleanup(sessionId);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        String sessionId = session.getId();
        log.error("WebSocket 传输错误: session={}, error={}", sessionId, exception.getMessage());
        cleanup(sessionId);
    }

    // ==================== 辅助方法 ====================

    private void sendToClient(String sessionId, String json) {
        WebSocketSession ws = wsSessions.get(sessionId);
        if (ws != null && ws.isOpen()) {
            try {
                synchronized (ws) {
                    ws.sendMessage(new TextMessage(json));
                }
            } catch (IOException e) {
                log.error("发送消息到前端失败: session={}, error={}", sessionId, e.getMessage());
            }
        }
    }

    private String buildResultJson(AsrServiceProto.AsrResult result) {
        return String.format(
                "{\"type\":\"result\",\"text\":%s,\"isFinal\":%s,\"confidence\":%.3f,\"language\":\"%s\"}",
                jsonEscape(result.getText()),
                result.getIsFinal(),
                result.getConfidence(),
                result.getLanguage()
        );
    }

    private String buildErrorJson(String message) {
        return String.format("{\"type\":\"error\",\"message\":\"%s\"}", message.replace("\"", "'"));
    }

    private String jsonEscape(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    private void cleanup(String sessionId) {
        StreamObserver<AsrServiceProto.AudioChunk> sender = grpcSenders.remove(sessionId);
        if (sender != null) {
            try {
                sender.onCompleted();
            } catch (Exception ignored) {}
        }
        wsSessions.remove(sessionId);
    }
}
