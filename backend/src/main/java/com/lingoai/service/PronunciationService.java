package com.lingoai.service;

import com.lingoai.dto.response.PronunciationScoreDTO;

/**
 * 发音评测服务接口
 */
public interface PronunciationService {

    PronunciationScoreDTO evaluate(String audioBase64, String referenceText);

}
