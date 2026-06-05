package com.lingoai.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 语音服务连接测试控制器
 * 使用真正的WebSocket握手测试不同认证组合
 */
@RestController
@RequestMapping("/api/voice")
public class VoiceTestController {

    private static final Logger logger = LoggerFactory.getLogger(VoiceTestController.class);
    private static final String DOUBAO_WS_URL = "wss://openspeech.bytedance.com/api/v3/realtime/dialogue";
    private static final String DOUBAO_RESOURCE_ID = "volc.speech.dialog";
    private static final String DOUBAO_APP_KEY = "PlgvMymc7f3tQnJ6";

    @PostMapping("/test-auth")
    public ResponseEntity<Map<String, Object>> testAuth(@RequestBody Map<String, String> request) {
        String appId = request.get("appId");
        String accessToken = request.get("accessToken");
        String secretKey = request.get("secretKey");

        logger.info("=== 开始测试不同的认证组合 ===");
        logger.info("App ID: {}", appId);

        List<Map<String, Object>> results = new ArrayList<>();

        // 组合1: Access Token 作为 X-Api-Access-Key (使用WebSocket builder)
        results.add(testWsCombination("组合1-A: Access-Token → Header (WS Builder)", appId, accessToken, false));
        
        // 组合2: Secret Key 作为 X-Api-Access-Key (使用WebSocket builder)
        results.add(testWsCombination("组合2-A: Secret-Key → Header (WS Builder)", appId, secretKey, false));

        // 组合3: Access Token 放在 URL 查询参数 access-token
        results.add(testWsCombination("组合3: Access Token → URL ?access-token=", appId, accessToken, true));
        
        // 组合4: Secret Key 放在 URL 查询参数 access-token
        results.add(testWsCombination("组合4: Secret Key → URL ?access-token=", appId, secretKey, true));

        Map<String, Object> response = new HashMap<>();
        response.put("results", results);

        boolean anySuccess = results.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("success")));
        response.put("anySuccess", anySuccess);

        logger.info("=== 测试完成，{}成功 ===", anySuccess ? "有" : "无");
        
        return ResponseEntity.ok(response);
    }

    /**
     * 使用真正的WebSocket握手测试认证
     */
    private Map<String, Object> testWsCombination(String name, String appId, String apiKey, boolean useUrlParam) {
        Map<String, Object> result = new HashMap<>();
        result.put("name", name);
        result.put("appId", appId);
        result.put("apiKeyPrefix", apiKey != null ? apiKey.substring(0, Math.min(10, apiKey.length())) + "..." : "null");

        if (appId == null || apiKey == null) {
            result.put("success", false);
            result.put("statusCode", -1);
            result.put("error", "缺少必填参数");
            return result;
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // 构建URL
        String url;
        if (useUrlParam) {
            url = DOUBAO_WS_URL + "?access-token=" + apiKey;
        } else {
            url = DOUBAO_WS_URL;
        }

        // 使用CompletableFuture来同步处理异步WebSocket结果
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();

        try {
            var wsBuilder = client.newWebSocketBuilder()
                    .header("X-Api-App-ID", appId)
                    .header("X-Api-Resource-Id", DOUBAO_RESOURCE_ID)
                    .header("X-Api-App-Key", DOUBAO_APP_KEY);

            // 如果不使用URL参数，则在header中传递apiKey
            if (!useUrlParam) {
                wsBuilder = wsBuilder.header("X-Api-Access-Key", apiKey);
            }

            long startTime = System.currentTimeMillis();

            wsBuilder.buildAsync(URI.create(url), new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket webSocket) {
                    long latency = System.currentTimeMillis() - startTime;
                    logger.info("{} - 连接成功!", name);
                    
                    Map<String, Object> r = new HashMap<>();
                    r.put("success", true);
                    r.put("statusCode", 101);
                    r.put("latency", latency);
                    r.put("error", null);
                    
                    // 立即关闭连接
                    webSocket.sendClose(1000, "测试完成");
                    future.complete(r);
                }

                @Override
                public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    logger.info("{} - 收到消息: {}", name, data);
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    if (!future.isDone()) {
                        long latency = System.currentTimeMillis() - startTime;
                        logger.info("{} - 连接关闭: {} {}", name, statusCode, reason);
                        Map<String, Object> r = new HashMap<>();
                        r.put("success", statusCode == 1000);
                        r.put("statusCode", statusCode);
                        r.put("latency", latency);
                        r.put("error", reason != null && !reason.isEmpty() ? reason : null);
                        future.complete(r);
                    }
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public void onError(WebSocket webSocket, Throwable error) {
                    if (!future.isDone()) {
                        long latency = System.currentTimeMillis() - startTime;
                        logger.error("{} - 错误: {}", name, error.getMessage());
                        Map<String, Object> r = new HashMap<>();
                        r.put("success", false);
                        r.put("statusCode", -1);
                        r.put("latency", latency);
                        
                        // 提取HTTP状态码
                        String errorMsg = error.getMessage();
                        Throwable cause = error.getCause();
                        while (cause != null) {
                            if (cause.getMessage() != null && cause.getMessage().contains("401")) {
                                errorMsg = "401 Unauthorized - " + cause.getMessage();
                                r.put("statusCode", 401);
                                break;
                            } else if (cause.getMessage() != null && cause.getMessage().contains("403")) {
                                errorMsg = "403 Forbidden - " + cause.getMessage();
                                r.put("statusCode", 403);
                                break;
                            }
                            cause = cause.getCause();
                        }
                        
                        if (error instanceof java.net.http.WebSocketHandshakeException) {
                            var handshakeEx = (java.net.http.WebSocketHandshakeException) error;
                            try {
                                var response = handshakeEx.getResponse();
                                r.put("statusCode", response.statusCode());
                                r.put("responseHeaders", response.headers().map());
                                errorMsg = "HTTP " + response.statusCode();
                            } catch (Exception ex) {
                                // ignore
                            }
                        }
                        
                        r.put("error", errorMsg);
                        future.complete(r);
                    }
                }
            });

            // 等待结果，最多15秒
            Map<String, Object> wsResult = future.get(15, TimeUnit.SECONDS);
            result.putAll(wsResult);

        } catch (java.util.concurrent.TimeoutException e) {
            result.put("success", false);
            result.put("statusCode", -1);
            result.put("error", "连接超时(15秒)");
            result.put("latency", 15000);
            logger.info("{} - 超时", name);
        } catch (Exception e) {
            result.put("success", false);
            result.put("statusCode", -1);
            result.put("error", e.getClass().getName() + ": " + e.getMessage());
            logger.info("{} - 异常: {}", name, e.getMessage());
        }

        return result;
    }
}
