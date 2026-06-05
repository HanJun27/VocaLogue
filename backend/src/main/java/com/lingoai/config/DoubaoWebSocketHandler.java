package com.lingoai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

public class DoubaoWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(DoubaoWebSocketHandler.class);
    private static final String DOUBAO_WS_URL = "wss://openspeech.bytedance.com/api/v3/realtime/dialogue";
    // 豆包API固定参数
    private static final String DOUBAO_RESOURCE_ID = "volc.speech.dialog";
    private static final String DOUBAO_APP_KEY = "PlgvMymc7f3tQnJ6";

    private WebSocketSession clientSession;
    private WebSocket doubaoWs;
    private String currentApiKey;
    private String currentAppId;
    private String currentSecretKey;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        logger.info("前端 WebSocket 连接已建立：{}", session.getId());
        this.clientSession = session;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        logger.info("收到前端文本消息：{}", payload);

        try {
            if (payload.contains("apiKey") && payload.contains("appId")) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                java.util.Map<String, String> initData = mapper.readValue(payload, java.util.Map.class);
                String apiKey = initData.get("apiKey");
                String appId = initData.get("appId");
                String secretKey = initData.get("secretKey");
                
                if (apiKey != null && appId != null) {
                    this.currentApiKey = apiKey;
                    this.currentAppId = appId;
                    this.currentSecretKey = secretKey;
                    // 先尝试用 apiKey (Access Token) 连接
                    connectToDoubao(apiKey, appId, "Access Token");
                }
            } else {
                if (doubaoWs != null) {
                    doubaoWs.sendText(payload, true);
                }
            }
        } catch (Exception e) {
            logger.error("处理文本消息失败：{}", e.getMessage());
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        logger.debug("收到前端二进制消息，长度：{} bytes", message.getPayloadLength());
        
        if (doubaoWs != null) {
            ByteBuffer buffer = message.getPayload().asReadOnlyBuffer();
            doubaoWs.sendBinary(buffer, true);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        logger.info("前端 WebSocket 连接已关闭：{}, 状态：{}", session.getId(), status);
        
        if (doubaoWs != null) {
            doubaoWs.sendClose(1000, "客户端断开连接").join();
            doubaoWs = null;
        }
        
        this.clientSession = null;
    }

    private void connectToDoubao(String apiKey, String appId, String credentialType) {
        if (doubaoWs != null) {
            logger.warn("已存在到豆包的连接，先关闭旧连接");
            try {
                doubaoWs.sendClose(1000, "重新连接").join();
            } catch (Exception e) {
                logger.error("关闭旧连接失败：{}", e.getMessage());
            }
        }

        logger.info("开始建立到豆包的 WebSocket 连接 (使用{})...", credentialType);
        logger.info("  目标URL: {}", DOUBAO_WS_URL);
        logger.info("  X-Api-App-ID: {}", appId);
        logger.info("  X-Api-Access-Key: {}", apiKey != null ? apiKey.substring(0, Math.min(10, apiKey.length())) + "..." : "null");
        logger.info("  X-Api-Resource-Id: {}", DOUBAO_RESOURCE_ID);
        logger.info("  X-Api-App-Key: {}", DOUBAO_APP_KEY);
        
        HttpClient client = HttpClient.newHttpClient();
        
        // 保存credentialType到局部变量供lambda使用
        final String currentCredentialType = credentialType;
        final String currentApiKey = apiKey;
        
        CompletableFuture<WebSocket> wsFuture = client.newWebSocketBuilder()
                .header("X-Api-App-ID", appId)
                .header("X-Api-Access-Key", apiKey)
                .header("X-Api-Resource-Id", DOUBAO_RESOURCE_ID)
                .header("X-Api-App-Key", DOUBAO_APP_KEY)
                .buildAsync(URI.create(DOUBAO_WS_URL), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        logger.info("成功连接到豆包服务器");
                        doubaoWs = webSocket;
                        // 通知前端连接成功
                        try {
                            if (clientSession != null && clientSession.isOpen()) {
                                clientSession.sendMessage(new TextMessage(
                                    "{\"type\": \"success\", \"message\": \"豆包WebSocket连接成功\", \"credentialType\": \"" + currentCredentialType + "\"}"
                                ));
                            }
                        } catch (IOException e) {
                            logger.error("发送成功消息到前端失败：{}", e.getMessage());
                        }
                    }

                    @Override
                    public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        logger.debug("收到豆包文本消息：{}", data);
                        try {
                            if (clientSession != null && clientSession.isOpen()) {
                                clientSession.sendMessage(new TextMessage(data.toString()));
                            }
                        } catch (IOException e) {
                            logger.error("发送消息到前端失败：{}", e.getMessage());
                        }
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public CompletableFuture<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                        logger.debug("收到豆包二进制消息，长度：{} bytes", data.remaining());
                        try {
                            if (clientSession != null && clientSession.isOpen()) {
                                clientSession.sendMessage(new BinaryMessage(data));
                            }
                        } catch (IOException e) {
                            logger.error("发送二进制消息到前端失败：{}", e.getMessage());
                        }
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        logger.info("豆包 WebSocket 连接关闭：{} - {}", statusCode, reason);
                        doubaoWs = null;
                        
                        try {
                            if (clientSession != null && clientSession.isOpen()) {
                                clientSession.sendMessage(new TextMessage("{\"type\": \"disconnect\", \"message\": \"豆包服务器连接关闭\"}"));
                            }
                        } catch (IOException e) {
                            logger.error("发送断开连接通知失败：{}", e.getMessage());
                        }
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        logger.error("豆包 WebSocket 连接错误 ({}): {}", currentCredentialType, error.getMessage());
                        doubaoWs = null;
                        
                        // 401 Unauthorized + 有secretKey + 当前使用的不是secretKey → 尝试用secretKey重试
                        boolean is401 = false;
                        if (error instanceof java.net.http.WebSocketHandshakeException) {
                            var handshakeEx = (java.net.http.WebSocketHandshakeException) error;
                            try {
                                var response = handshakeEx.getResponse();
                                is401 = response.statusCode() == 401;
                            } catch (Exception ex) {
                                // ignore
                            }
                        }
                        if (error.getMessage() != null && error.getMessage().contains("401")) {
                            is401 = true;
                        }
                        
                        if (is401 && currentSecretKey != null && !currentSecretKey.isEmpty() 
                            && !currentCredentialType.equals("Secret Key")) {
                            logger.info("使用 Access Token 连接失败(401)，尝试用 Secret Key 重试...");
                            connectToDoubao(currentSecretKey, currentAppId, "Secret Key");
                            return;
                        }
                        
                        try {
                            if (clientSession != null && clientSession.isOpen()) {
                                String errorMsg = "连接豆包服务器失败(" + currentCredentialType + ")";
                                // Try to extract HTTP status code
                                if (error instanceof java.net.http.WebSocketHandshakeException) {
                                    var handshakeEx = (java.net.http.WebSocketHandshakeException) error;
                                    try {
                                        var response = handshakeEx.getResponse();
                                        errorMsg += ": HTTP " + response.statusCode();
                                    } catch (Exception ex) {
                                        errorMsg += ": " + error.getMessage();
                                    }
                                } else {
                                    errorMsg += ": " + error.getMessage();
                                }
                                clientSession.sendMessage(new TextMessage("{\"type\": \"error\", \"message\": \"" + errorMsg.replace("\"", "'") + "\"}"));
                            }
                        } catch (IOException ex) {
                            logger.error("发送错误通知失败：{}", ex.getMessage());
                        }
                    }
                });

        wsFuture.exceptionally(e -> {
            // 检查是否是401错误，尝试用Secret Key重试
            boolean is401 = false;
            if (e instanceof java.net.http.WebSocketHandshakeException) {
                var handshakeEx = (java.net.http.WebSocketHandshakeException) e;
                try {
                    is401 = handshakeEx.getResponse().statusCode() == 401;
                } catch (Exception ex) {
                    // ignore
                }
            }
            if (e.getMessage() != null && e.getMessage().contains("401")) {
                is401 = true;
            }
            
            if (is401 && currentSecretKey != null && !currentSecretKey.isEmpty() 
                && !currentCredentialType.equals("Secret Key")) {
                logger.info("[exceptionally] 使用 Access Token 连接失败(401)，尝试用 Secret Key 重试...");
                connectToDoubao(currentSecretKey, currentAppId, "Secret Key");
                return null;
            }
            
            // 详细记录所有异常链
            logger.error("=== 豆包WebSocket连接失败 ({}) ===", currentCredentialType);
            Throwable cause = e;
            int level = 0;
            while (cause != null) {
                logger.error("  异常层级[{}]: {} - {}", level, cause.getClass().getName(), cause.getMessage());
                
                // WebSocketHandshakeException 包含HTTP响应信息
                if (cause instanceof java.net.http.WebSocketHandshakeException) {
                    java.net.http.WebSocketHandshakeException handshakeEx = 
                        (java.net.http.WebSocketHandshakeException) cause;
                    try {
                        java.net.http.HttpResponse<?> response = handshakeEx.getResponse();
                        logger.error("  HTTP响应状态码: {}", response.statusCode());
                        logger.error("  HTTP响应Body: {}", response.body());
                        // 打印响应头
                        response.headers().map().forEach((key, values) -> {
                            logger.error("  响应头 {}: {}", key, String.join(", ", values));
                        });
                    } catch (Exception ex) {
                        logger.error("  无法获取HTTP响应详情: {}", ex.getMessage());
                    }
                }
                cause = cause.getCause();
                level++;
            }
            logger.error("=== 完整堆栈 ===", e);
            
            String detailMsg = "连接豆包服务器失败(" + currentCredentialType + ")";
            if (e.getCause() != null) {
                detailMsg += ": " + e.getCause().getMessage();
            }
            try {
                if (clientSession != null && clientSession.isOpen()) {
                    clientSession.sendMessage(new TextMessage("{\"type\": \"error\", \"message\": \"" + detailMsg.replace("\"", "'") + "\"}"));
                }
            } catch (IOException ex) {
                logger.error("发送连接失败通知失败：{}", ex.getMessage());
            }
            return null;
        });
    }
}
