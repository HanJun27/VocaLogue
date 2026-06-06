package com.lingoai.config;

import com.lingoai.service.ai.FasterWhisperGrpcClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final FasterWhisperGrpcClient grpcClient;

    public WebSocketConfig(FasterWhisperGrpcClient grpcClient) {
        this.grpcClient = grpcClient;
    }

    @Bean
    public DoubaoWebSocketHandler doubaoWebSocketHandler() {
        return new DoubaoWebSocketHandler();
    }

    @Bean
    public FasterWhisperWebSocketHandler fasterWhisperWebSocketHandler() {
        return new FasterWhisperWebSocketHandler(grpcClient);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 豆包实时语音 WebSocket
        registry.addHandler(doubaoWebSocketHandler(), "/api/voice/doubao")
                .setAllowedOrigins("*");

        // Faster-Whisper 本地 ASR WebSocket
        registry.addHandler(fasterWhisperWebSocketHandler(), "/api/voice/asr")
                .setAllowedOrigins("*");
    }
}
