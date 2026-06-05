package com.lingoai.service.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * AI 管线设置服务
 * 管理每个用户/会话的 ASR→LLM→TTS 管线配置
 * 通过 Redis 存储，支持设置界面实时切换
 */
@Service
public class AiPipelineSettings {

    private final StringRedisTemplate redisTemplate;

    private static final String SETTINGS_KEY_PREFIX = "ai:pipeline:settings:";
    private static final String USER_SETTINGS_PREFIX = "ai:user:settings:";
    private static final long TTL_HOURS = 72;

    public AiPipelineSettings(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 获取用户的管线配置
     */
    public ConversationPipelineService.PipelineConfig getUserPipelineConfig(String userId) {
        String key = USER_SETTINGS_PREFIX + userId;
        String json = redisTemplate.opsForValue().get(key);
        if (json != null && !json.isEmpty()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                return mapper.readValue(json, ConversationPipelineService.PipelineConfig.class);
            } catch (Exception e) {
                // fallback to default
            }
        }
        return ConversationPipelineService.PipelineConfig.defaultConfig();
    }

    /**
     * 保存用户的管线配置
     */
    public void saveUserPipelineConfig(String userId,
                                        ConversationPipelineService.PipelineConfig config) {
        String key = USER_SETTINGS_PREFIX + userId;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String json = mapper.writeValueAsString(config);
            redisTemplate.opsForValue().set(key, json, TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save pipeline config", e);
        }
    }

    /**
     * 获取会话级管线配置（覆盖用户级设置）
     */
    public ConversationPipelineService.PipelineConfig getSessionPipelineConfig(String sessionId) {
        String key = SETTINGS_KEY_PREFIX + sessionId;
        String json = redisTemplate.opsForValue().get(key);
        if (json != null && !json.isEmpty()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                return mapper.readValue(json, ConversationPipelineService.PipelineConfig.class);
            } catch (Exception e) {
                // fallback to user config
            }
        }
        return null; // 使用用户级默认配置
    }

    /**
     * 保存会话级管线配置
     */
    public void saveSessionPipelineConfig(String sessionId,
                                           ConversationPipelineService.PipelineConfig config) {
        String key = SETTINGS_KEY_PREFIX + sessionId;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String json = mapper.writeValueAsString(config);
            redisTemplate.opsForValue().set(key, json, TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save session pipeline config", e);
        }
    }
}
