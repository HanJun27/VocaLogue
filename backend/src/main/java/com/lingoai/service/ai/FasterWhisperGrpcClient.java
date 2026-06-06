package com.lingoai.service.ai;

import com.lingoai.config.AiConfig;
import com.lingoai.grpc.asr.AsrServiceProto;
import com.lingoai.grpc.asr.FasterWhisperASRGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Faster-Whisper gRPC 客户端
 * 与 Python ASR 微服务通信
 *
 * 支持三种调用模式：
 *  1. 非流式识别 (recognizeFile) - 完整音频文件
 *  2. 流式识别 (streamRecognize) - 双向流式音频/结果
 *  3. 管理接口 (getStatus / updateSettings)
 */
@Slf4j
@Service
public class FasterWhisperGrpcClient {

    private final AiConfig aiConfig;
    private ManagedChannel channel;
    private FasterWhisperASRGrpc.FasterWhisperASRStub asyncStub;
    private FasterWhisperASRGrpc.FasterWhisperASRBlockingStub blockingStub;

    public FasterWhisperGrpcClient(AiConfig aiConfig) {
        this.aiConfig = aiConfig;
    }

    @PostConstruct
    public void init() {
        var asrConfig = aiConfig.getAsr();
        if (asrConfig == null || asrConfig.getFasterWhisper() == null) {
            log.warn("Faster-Whisper 配置未设置，gRPC 客户端未初始化");
            return;
        }

        String host = asrConfig.getFasterWhisper().getHost();
        int port = asrConfig.getFasterWhisper().getPort();

        log.info("初始化 Faster-Whisper gRPC 客户端: {}:{}", host, port);

        channel = ManagedChannelBuilder
                .forAddress(host, port)
                .usePlaintext()
                .maxInboundMessageSize(50 * 1024 * 1024)
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .build();

        asyncStub = FasterWhisperASRGrpc.newStub(channel);
        blockingStub = FasterWhisperASRGrpc.newBlockingStub(channel);

        log.info("Faster-Whisper gRPC 客户端初始化完成");
    }

    @PreDestroy
    public void shutdown() {
        if (channel != null && !channel.isShutdown()) {
            log.info("关闭 Faster-Whisper gRPC 客户端");
            channel.shutdown();
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    // ==================== 非流式识别 ====================

    /**
     * 识别完整音频（阻塞调用）
     */
    public AsrServiceProto.RecognizeResult recognizeFile(byte[] audioData, String language, boolean enableVad) {
        if (blockingStub == null) {
            log.error("gRPC stub 未初始化");
            return null;
        }

        AsrServiceProto.AudioFile request = AsrServiceProto.AudioFile.newBuilder()
                .setAudioData(com.google.protobuf.ByteString.copyFrom(audioData))
                .setLanguage(language != null ? language : "")
                .setEnableVad(enableVad)
                .build();

        try {
            AsrServiceProto.RecognizeResult result = blockingStub
                    .withDeadlineAfter(120, TimeUnit.SECONDS)
                    .recognizeFile(request);
            log.info("非流式识别完成: text={}", result.getText());
            return result;
        } catch (Exception e) {
            log.error("非流式识别失败: {}", e.getMessage());
            throw new RuntimeException("ASR 识别失败: " + e.getMessage(), e);
        }
    }

    // ==================== 流式识别 ====================

    /**
     * 开始流式识别
     *
     * @param sessionId 会话 ID
     * @param onResult 识别结果回调
     * @param onError 错误回调
     * @param onComplete 完成回调
     * @return StreamObserver 用于发送音频数据
     */
    public StreamObserver<AsrServiceProto.AudioChunk> streamRecognize(
            String sessionId,
            Consumer<AsrServiceProto.AsrResult> onResult,
            Consumer<Throwable> onError,
            Runnable onComplete) {

        if (asyncStub == null) {
            throw new IllegalStateException("gRPC stub 未初始化");
        }

        return asyncStub.withDeadlineAfter(300, TimeUnit.SECONDS)
                .streamRecognize(new StreamObserver<>() {
                    @Override
                    public void onNext(AsrServiceProto.AsrResult result) {
                        log.debug("流式识别结果: text={}, final={}",
                                result.getText(), result.getIsFinal());
                        if (onResult != null) {
                            onResult.accept(result);
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        log.error("流式识别出错: {}", t.getMessage());
                        if (onError != null) {
                            onError.accept(t);
                        }
                    }

                    @Override
                    public void onCompleted() {
                        log.info("流式识别完成");
                        if (onComplete != null) {
                            onComplete.run();
                        }
                    }
                });
    }

    // ==================== 管理接口 ====================

    /**
     * 获取 ASR 服务状态
     */
    public AsrServiceProto.StatusResponse getStatus() {
        if (blockingStub == null) {
            return AsrServiceProto.StatusResponse.newBuilder()
                    .setModelLoaded(false)
                    .setMessage("gRPC 客户端未初始化")
                    .build();
        }

        try {
            return blockingStub
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .getStatus(AsrServiceProto.StatusRequest.newBuilder().build());
        } catch (Exception e) {
            log.error("获取 ASR 状态失败: {}", e.getMessage());
            return AsrServiceProto.StatusResponse.newBuilder()
                    .setModelLoaded(false)
                    .setMessage("连接失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 更新 ASR 设置
     */
    public AsrServiceProto.StatusResponse updateSettings(
            String modelName, String device, int computeType,
            boolean enableVad, int vadThresholdMs, int windowSizeMs, String language) {

        if (blockingStub == null) {
            throw new IllegalStateException("gRPC 客户端未初始化");
        }

        AsrServiceProto.AsrSettings request = AsrServiceProto.AsrSettings.newBuilder()
                .setModelName(modelName)
                .setDevice(device)
                .setComputeType(computeType)
                .setEnableVad(enableVad)
                .setVadThresholdMs(vadThresholdMs)
                .setWindowSizeMs(windowSizeMs)
                .setLanguage(language != null ? language : "")
                .build();

        try {
            return blockingStub
                    .withDeadlineAfter(30, TimeUnit.SECONDS)
                    .updateSettings(request);
        } catch (Exception e) {
            log.error("更新 ASR 设置失败: {}", e.getMessage());
            throw new RuntimeException("更新 ASR 设置失败: " + e.getMessage(), e);
        }
    }

    /**
     * gRPC 客户端是否就绪
     */
    public boolean isReady() {
        return channel != null && !channel.isShutdown()
                && blockingStub != null && asyncStub != null;
    }
}
