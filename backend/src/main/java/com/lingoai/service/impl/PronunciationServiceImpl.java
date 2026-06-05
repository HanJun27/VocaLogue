package com.lingoai.service.impl;

import com.lingoai.dto.response.PronunciationScoreDTO;
import com.lingoai.service.PronunciationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Random;

/**
 * 发音评测服务实现类
 * 注意：当前为模拟实现，实际使用时需要接入第三方发音评测服务（如驰声、Azure等）
 */
@Service
@Slf4j
public class PronunciationServiceImpl implements PronunciationService {

    @Value("${pronunciation.api-key}")
    private String apiKey;

    @Value("${pronunciation.api-url}")
    private String apiUrl;

    private final Random random = new Random();

    @Override
    public PronunciationScoreDTO evaluate(String audioBase64, String referenceText) {
        log.debug("调用发音评测, 文本长度={}", referenceText.length());
        
        // 模拟发音评测结果
        // 实际实现时应调用第三方API（如驰声、Azure Speech Assessment）
        
        int accuracy = 75 + random.nextInt(25);      // 75-99
        int fluency = 70 + random.nextInt(30);       // 70-99
        int grammar = 78 + random.nextInt(22);       // 78-99
        int overall = (accuracy + fluency + grammar) / 3;
        
        String feedbackSummary = generateFeedback(accuracy, fluency, grammar);
        
        return PronunciationScoreDTO.builder()
                .accuracy(accuracy)
                .fluency(fluency)
                .grammar(grammar)
                .overall(overall)
                .feedbackSummary(feedbackSummary)
                .build();
    }

    private String generateFeedback(int accuracy, int fluency, int grammar) {
        StringBuilder feedback = new StringBuilder();
        
        if (accuracy >= 90) {
            feedback.append("发音非常标准！");
        } else if (accuracy >= 80) {
            feedback.append("发音清晰，但有一些单词需要注意重音。");
        } else {
            feedback.append("建议多练习发音，可以跟读标准音频。");
        }
        
        if (fluency >= 90) {
            feedback.append("语流流畅自然。");
        } else if (fluency >= 80) {
            feedback.append("表达较流畅，可以适当减少停顿。");
        } else {
            feedback.append("建议提高语速，增强表达流畅度。");
        }
        
        if (grammar >= 90) {
            feedback.append("语法使用准确。");
        } else {
            feedback.append("注意语法结构的正确性。");
        }
        
        return feedback.toString();
    }

}
