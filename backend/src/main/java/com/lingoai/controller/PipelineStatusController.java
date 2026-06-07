package com.lingoai.controller;

import com.lingoai.config.AiConfig;
import com.lingoai.service.ai.LocalTtsClient;
import com.lingoai.service.ai.SessionCoordinator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 管线状态监控控制器
 *
 * 提供：
 *  - TTS 服务健康检查
 *  - 活跃会话状态和延迟指标
 *  - 管线配置信息
 */
@RestController
@RequestMapping("/api/pipeline/status")
@Slf4j
public class PipelineStatusController {

    private final LocalTtsClient localTtsClient;
    private final SessionCoordinator.Manager coordinatorManager;
    private final AiConfig aiConfig;

    public PipelineStatusController(LocalTtsClient localTtsClient,
                                     SessionCoordinator.Manager coordinatorManager,
                                     AiConfig aiConfig) {
        this.localTtsClient = localTtsClient;
        this.coordinatorManager = coordinatorManager;
        this.aiConfig = aiConfig;
    }

    /**
     * 整体健康检查
     * GET /api/pipeline/status/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean piperHealthy = localTtsClient.isHealthy();
        String ttsEngine = aiConfig.getTts().getDefaultEngine();

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", piperHealthy ? "UP" : "DEGRADED");
        status.put("ttsEngine", ttsEngine);
        status.put("piperHealthy", piperHealthy);

        if (!piperHealthy) {
            status.put("ttsFallback", "edge-tts");
            status.put("message", "Piper TTS 不可用，将回退到 Edge TTS");
        }

        status.put("activeSessions", coordinatorManager.getActiveCount());

        return ResponseEntity.ok(status);
    }

    /**
     * 获取 TTS 服务状态
     * GET /api/pipeline/status/tts
     */
    @GetMapping("/tts")
    public ResponseEntity<Map<String, Object>> ttsStatus() {
        boolean piperOk = localTtsClient.isHealthy();

        Map<String, Object> tts = new LinkedHashMap<>();
        tts.put("piper", Map.of(
                "healthy", piperOk,
                "baseUrl", aiConfig.getTts().getLocal().getBaseUrl(),
                "defaultVoice", aiConfig.getTts().getLocal().getPiperVoice()
        ));
        tts.put("edgeTts", Map.of(
                "defaultVoice", aiConfig.getTts().getLocal().getEdgeVoice()
        ));

        return ResponseEntity.ok(tts);
    }

    /**
     * 获取所有活跃会话的指标
     * GET /api/pipeline/status/sessions
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<Map<String, Object>>> sessions() {
        List<Map<String, Object>> sessionList = new ArrayList<>();

        for (SessionCoordinator coord : coordinatorManager.getCoordinators().values()) {
            SessionCoordinator.PipelineMetrics m = coord.getMetrics();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sessionId", coord.getSessionId());
            entry.put("state", coord.getState().name());
            entry.put("llmEngine", m.getLlmEngine());
            entry.put("llmModel", m.getLlmModel());
            entry.put("llmFirstTokenLatency", m.getLlmFirstTokenLatency());
            entry.put("ttsFirstAudioLatency", m.getTtsFirstAudioLatency());
            entry.put("endToEndLatency", m.getEndToEndLatency());
            entry.put("lastUpdatedAt", m.getLastUpdatedAt());
            sessionList.add(entry);
        }

        return ResponseEntity.ok(sessionList);
    }

    /**
     * 获取管线配置信息
     * GET /api/pipeline/status/config
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> config() {
        var ttsConfig = aiConfig.getTts();
        var asrConfig = aiConfig.getAsr();

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("defaultTtsEngine", ttsConfig.getDefaultEngine());
        config.put("defaultPiperVoice", ttsConfig.getLocal().getPiperVoice());
        config.put("defaultEdgeVoice", ttsConfig.getLocal().getEdgeVoice());
        config.put("piperSpeed", ttsConfig.getLocal().getPiperSpeed());
        config.put("piperTimeoutMs", ttsConfig.getLocal().getTimeoutMs());
        config.put("asrService", asrConfig != null && asrConfig.getFasterWhisper() != null
                ? asrConfig.getFasterWhisper().getHost() + ":" + asrConfig.getFasterWhisper().getPort()
                : "not configured");

        return ResponseEntity.ok(config);
    }
}
