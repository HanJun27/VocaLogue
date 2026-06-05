package com.lingoai.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ASR 语音识别服务抽象层
 * 来源参考：everyone-can-use-english 的 echogarden.ts + azure-speech-sdk.ts
 *
 * 当前系统已通过 DoubaoWebSocketHandler 实现了豆包实时语音识别，
 * 此服务提供：
 *  1. 统一 ASR 结果封装
 *  2. 发音评估接口（参考 azure-speech-sdk.ts 的 pronunciationAssessment）
 *  3. 预留 Whisper 本地识别的集成点
 *
 * 注意：实时 ASR 由前端 WebSocket 直连豆包服务处理，
 * 后端主要负责：
 *  - 管理 ASR 结果数据的持久化
 *  - 提供发音评估接口
 *  - 管理 ASR 引擎切换配置
 */
@Slf4j
@Service
public class AsrService {

    /**
     * ASR 识别结果
     */
    @lombok.Builder
    @lombok.Data
    public static class AsrResult {
        private String text;
        private String language;
        private double duration;
        private boolean isFinal;
        private String rawJson;
    }

    /**
     * 发音评估结果
     * 来源：everyone-can-use-english azure-speech-sdk.ts pronunciationAssessment()
     */
    @lombok.Builder
    @lombok.Data
    public static class PronunciationAssessment {
        private int accuracyScore;
        private int fluencyScore;
        private int completenessScore;
        private int overallScore;
        private String phonemeLevel;
        private String wordLevel;
        private String detailResult;
    }

    /**
     * 处理 ASR 中间结果（用于流式显示）
     */
    public AsrResult processIntermediateResult(String text, String language) {
        return AsrResult.builder()
                .text(text)
                .language(language)
                .isFinal(false)
                .build();
    }

    /**
     * 处理 ASR 最终结果
     */
    public AsrResult processFinalResult(String text, String language, double duration) {
        log.debug("ASR final result: text={}, language={}, duration={}",
                text, language, duration);
        return AsrResult.builder()
                .text(text)
                .language(language)
                .duration(duration)
                .isFinal(true)
                .build();
    }
}
