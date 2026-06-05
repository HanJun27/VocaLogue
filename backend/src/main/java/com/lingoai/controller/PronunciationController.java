package com.lingoai.controller;

import com.lingoai.dto.response.ApiResponse;
import com.lingoai.dto.response.PronunciationScoreDTO;
import com.lingoai.service.PronunciationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 发音评测控制器
 */
@RestController
@RequestMapping("/api/pronunciation")
@RequiredArgsConstructor
@Slf4j
public class PronunciationController {

    private final PronunciationService pronunciationService;

    @PostMapping("/evaluate")
    public ResponseEntity<ApiResponse<PronunciationScoreDTO>> evaluate(
            @RequestBody Map<String, String> request) {
        String audioBase64 = request.get("audioBase64");
        String referenceText = request.get("referenceText");
        
        log.info("发音评测请求, 文本长度={}", referenceText != null ? referenceText.length() : 0);
        
        PronunciationScoreDTO score = pronunciationService.evaluate(audioBase64, referenceText);
        return ResponseEntity.ok(ApiResponse.success(score));
    }

}
