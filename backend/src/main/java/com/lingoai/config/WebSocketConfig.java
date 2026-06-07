package com.lingoai.config;

import com.lingoai.service.ai.ConversationPipelineService;
import com.lingoai.service.ai.FasterWhisperGrpcClient;
import com.lingoai.service.ai.SessionCoordinator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final FasterWhisperGrpcClient grpcClient;
    private final SessionCoordinator.Manager coordinatorManager;
    private final ConversationPipelineService pipelineService;

    public WebSocketConfig(FasterWhisperGrpcClient grpcClient,
                           SessionCoordinator.Manager coordinatorManager,
                           ConversationPipelineService pipelineService) {
        this.grpcClient = grpcClient;
        this.coordinatorManager = coordinatorManager;
        this.pipelineService = pipelineService;
    }

    @Bean
    public DoubaoWebSocketHandler doubaoWebSocketHandler() {
        return new DoubaoWebSocketHandler();
    }

    @Bean
    public FasterWhisperWebSocketHandler fasterWhisperWebSocketHandler() {
        return new FasterWhisperWebSocketHandler(grpcClient);
    }

    @Bean
    public RealTimePipelineWebSocketHandler realTimePipelineWebSocketHandler() {
        return new RealTimePipelineWebSocketHandler(coordinatorManager, pipelineService, grpcClient);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 豆包实时语音 WebSocket
        registry.addHandler(doubaoWebSocketHandler(), "/api/voice/doubao")
                .setAllowedOrigins("*");

        // Faster-Whisper 本地 ASR WebSocket
        registry.addHandler(fasterWhisperWebSocketHandler(), "/api/voice/asr")
                .setAllowedOrigins("*");

        // 实时对话管线 WebSocket（支持打断）
        registry.addHandler(realTimePipelineWebSocketHandler(), "/api/pipeline/realtime")
                .setAllowedOrigins("*");
    }
}
