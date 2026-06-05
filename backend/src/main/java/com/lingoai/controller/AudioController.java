package com.lingoai.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 音频文件控制器
 * 提供 TTS 生成的音频文件访问接口
 */
@RestController
@RequestMapping("/api/audio")
@Slf4j
public class AudioController {

    private static final String AUDIO_STORAGE_DIR = "./data/audio/tts";

    /**
     * 获取 TTS 音频文件
     * GET /api/audio/tts/{filename}
     */
    @GetMapping("/tts/{filename}")
    public ResponseEntity<Resource> getTtsAudio(@PathVariable String filename) {
        try {
            Path filePath = Paths.get(AUDIO_STORAGE_DIR, filename).normalize();
            if (!Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }

            Resource resource = new FileSystemResource(filePath);
            String contentType = Files.probeContentType(filePath);
            if (contentType == null) {
                contentType = "audio/mpeg";
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"" + filename + "\"")
                    .body(resource);
        } catch (IOException e) {
            log.error("Error serving audio file: {}", filename, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
