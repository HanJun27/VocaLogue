# LingoAI 后端 API 接口文档

**版本**: v2.0  
**更新日期**: 2026-06-07  
**基础路径**: `http://localhost:8080/api`

---

## 目录

1. [通用说明](#1-通用说明)
2. [场景管理](#2-场景管理)
3. [会话管理](#3-会话管理)
4. [AI 口语练习管线](#4-ai-口语练习管线)
5. [发音评测](#5-发音评测)
6. [ASR 语音识别](#6-asr-语音识别)
7. [ASR 设置](#7-asr-设置)
8. [AI 管线设置](#8-ai-管线设置)
9. [管线状态监控](#9-管线状态监控)
10. [音频文件服务](#10-音频文件服务)
11. [语音连接测试](#11-语音连接测试)

---

## 1. 通用说明

### 1.1 响应格式

所有 API 响应使用统一的 `ApiResponse` 包装：

```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

### 1.2 错误响应

```json
{
  "code": 400,
  "message": "错误描述信息",
  "data": null
}
```

### 1.3 公共请求头

| Header | 说明 | 是否必须 |
|--------|------|----------|
| `Content-Type` | `application/json`（POST/PUT 请求） | 是 |
| `Authorization` | `Bearer <token>`（认证后） | 可选 |

---

## 2. 场景管理

**Controller**: `ScenarioController`  
**基础路径**: `/api/scenarios`

### 2.1 获取所有场景列表

```
GET /api/scenarios
```

**响应示例**:
```json
{
  "code": 200,
  "data": [
    {
      "id": "frontend",
      "title": "技术面试",
      "tag": "面试",
      "emoji": "💻",
      "difficulty": 4,
      "description": "模拟前端技术面试",
      "welcomeMessage": "Hello, I'm the interviewer. Please introduce yourself.",
      "welcomeTranslation": "你好，我是面试官，请做自我介绍。",
      "questions": [
        {
          "id": "int_q1",
          "text": "That is wonderful...",
          "translation": "太赞了...",
          "orderIndex": 1,
          "keywords": []
        }
      ]
    }
  ]
}
```

### 2.2 获取单个场景详情

```
GET /api/scenarios/{id}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `id` | path | 场景 ID（如 `frontend`） |

---

## 3. 会话管理

**Controller**: `ConversationController`  
**基础路径**: `/api/conversations`

### 3.1 创建新会话

```
POST /api/conversations
```

**请求体**:
```json
{
  "scenarioId": "frontend",
  "userId": "anonymous",
  "useAiPractice": true,
  "pipelineConfig": {
    "useAsr": false,
    "useTts": false,
    "agentName": "Ava",
    "llmEngine": "openai",
    "llmModel": "gpt-4o",
    "llmApiKey": "sk-xxx",
    "llmBaseUrl": "https://api.openai.com/v1",
    "llmTemperature": 0.8,
    "ttsEngine": "piper",
    "ttsVoice": "en_US-amy-medium"
  }
}
```

**响应**:
```json
{
  "code": 200,
  "data": {
    "sessionId": "conv_xxx",
    "scenarioId": "frontend",
    "scenarioTitle": "技术面试",
    "scenarioEmoji": "💻",
    "scenarioTag": "面试",
    "startTime": "2026-06-07T10:00:00",
    "endTime": null,
    "overallScore": null,
    "messageCount": 0
  }
}
```

### 3.2 发送消息（存储模式）

```
POST /api/conversations/{sessionId}/messages
```

**请求体**:
```json
{
  "text": "I have three years of experience.",
  "useVoice": false,
  "role": "user",
  "pronunciationScore": {
    "accuracy": 85,
    "fluency": 90,
    "overall": 88
  },
  "grammarFeedback": [
    {
      "original": "I have do",
      "suggested": "I have done",
      "title": "时态错误",
      "explanation": "现在完成时使用 have + 过去分词"
    }
  ]
}
```

### 3.3 AI 口语陪练对话

```
POST /api/conversations/{sessionId}/ai-practice
```

发送用户消息并获取 AI 实时回复（调用 LLM）。

**请求体**:
```json
{
  "text": "I have three years of experience in full-stack development.",
  "useVoice": false
}
```

**响应**:
```json
{
  "code": 200,
  "data": {
    "userText": "I have three years...",
    "aiResponseText": "That's impressive! Could you tell me about...",
    "translatedText": "太棒了！能说说你做过的一个有挑战的项目吗？",
    "analysisText": "语法分析文本（可选）",
    "ttsUrl": "http://localhost:8080/api/audio/tts/xxx.mp3",
    "agentName": "Ava",
    "agentDescription": "Friendly English tutor",
    "pipelineConfig": {
      "useAsr": false,
      "useTts": false,
      "asrEngine": "doubao",
      "llmModel": "gpt-4o",
      "ttsEngine": "piper",
      "ttsModel": "tts-1",
      "ttsVoice": "en_US-amy-medium"
    }
  }
}
```

### 3.4 获取对话历史

```
GET /api/conversations/{sessionId}
```

**响应**: 返回消息列表 `List<MessageDTO>`

### 3.5 获取用户会话列表

```
GET /api/conversations?userId=anonymous
```

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `userId` | query | `anonymous` | 用户标识 |

### 3.6 获取练习总结

```
GET /api/conversations/{sessionId}/summary
```

可选查询参数（用于指定 LLM 生成总结）：
| 参数 | 类型 | 说明 |
|------|------|------|
| `llmEngine` | query | LLM 引擎 |
| `llmModel` | query | LLM 模型 |
| `llmApiKey` | query | API Key |
| `llmBaseUrl` | query | Base URL |

**响应** — `PracticeSummaryDTO`:
```json
{
  "overallScore": 86,
  "dimensions": {
    "pronunciation": { "score": 85, "evaluation": "发音清晰，个别音素需加强" },
    "grammar": { "score": 78, "evaluation": "注意时态一致性" },
    "fluency": { "score": 90, "evaluation": "表达流畅" },
    "vocabulary": { "score": 82, "evaluation": "词汇量较丰富" },
    "interactivity": { "score": 88, "evaluation": "主动对话" }
  },
  "errors": [
    { "original": "I have do", "corrected": "I have done", "type": "verb_tense" }
  ],
  "suggestions": [
    { "title": "发音练习", "description": "练习 /θ/ 音" }
  ]
}
```

### 3.7 结束会话（生成总结）

```
DELETE /api/conversations/{sessionId}
```

可选查询参数与 3.6 相同。触发后端生成总结并保存。

### 3.8 删除会话记录

```
DELETE /api/conversations/{sessionId}/records
```

硬删除该会话及所有消息记录。

---

## 4. AI 口语练习管线

**Controller**: `PracticeController`  
**基础路径**: `/api/practice`

### 4.1 获取可用 AI 角色列表

```
GET /api/practice/agents
```

**响应**:
```json
{
  "code": 200,
  "data": [
    {
      "name": "Ava",
      "description": "Friendly English tutor",
      "language": "en",
      "ttsVoice": "en_US-amy-medium",
      "ttsModel": "tts-1"
    }
  ]
}
```

### 4.2 AI 口语练习聊天

```
POST /api/practice/chat
```

执行 ASR→LLM→TTS 完整管线。

**请求体**:
```json
{
  "sessionId": "conv_xxx",
  "text": "Hello, how are you?",
  "useAsr": false,
  "useTts": false,
  "pipelineConfig": {
    "useAsr": false,
    "useTts": true,
    "agentName": "Ava",
    "asrEngine": "doubao",
    "llmEngine": "openai",
    "llmModel": "gpt-4o",
    "llmApiKey": "sk-xxx",
    "llmBaseUrl": "https://api.openai.com/v1",
    "llmTemperature": 0.8,
    "ttsEngine": "piper",
    "ttsModel": "tts-1",
    "ttsVoice": "en_US-amy-medium"
  }
}
```

**响应**: 同 3.3 的 `AiChatResponse`。

### 4.3 流式 AI 聊天（Server-Sent Events）

```
GET /api/practice/chat/stream?sessionId=xxx&text=hello&llmEngine=openai&llmModel=gpt-4o
```

**查询参数**:

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `sessionId` | query | 必填 | 会话 ID |
| `text` | query | 必填 | 用户消息文本 |
| `useAsr` | query | false | 是否启用 ASR |
| `useTts` | query | false | 是否启用 TTS |
| `agentName` | query | - | AI 角色名 |
| `llmEngine` | query | openai | LLM 引擎 |
| `llmModel` | query | gpt-4o | LLM 模型 |
| `llmApiKey` | query | - | API Key |
| `llmBaseUrl` | query | - | Base URL |
| `ttsVoice` | query | - | TTS 音色 |
| `ttsEngine` | query | - | TTS 引擎 |

**响应格式**: `text/event-stream`（SSE）

```
event: message
data: {"choices":[{"delta":{"content":"That's"}}]}

event: message
data: {"choices":[{"delta":{"content":" impressive!"}}]}

event: message
data: [DONE]
```

### 4.4 测试 LLM 连接

```
POST /api/practice/test-llm
```

**请求体**:
```json
{
  "engine": "openai",
  "apiKey": "sk-xxx",
  "baseUrl": "https://api.openai.com/v1",
  "model": "gpt-4o"
}
```

**响应**:
```json
{
  "code": 200,
  "data": {
    "success": true,
    "message": "连接成功"
  }
}
```

---

## 5. 发音评测

**Controller**: `PronunciationController`  
**基础路径**: `/api/pronunciation`

### 5.1 发音评测

```
POST /api/pronunciation/evaluate
```

**请求格式**: `multipart/form-data`

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `file` | file | 必填 | 用户录音文件 (wav/webm/opus) |
| `reference_text` | string | 必填 | 参考文本 |
| `language` | string | `en` | 语言代码 (en/zh/ja/fr/de/ko) |

**响应**:
```json
{
  "code": 200,
  "data": {
    "accuracy_score": 85.3,
    "fluency_score": 90.1,
    "completeness_score": 78.5,
    "overall_pronunciation_score": 84.6,
    "word_scores": [
      {
        "word": "experience",
        "accuracy_score": 82.0,
        "expected_phonemes": "ɪkˈspɪriəns",
        "phoneme_scores": [90.0, 75.0, 85.0, 78.0, 82.0]
      }
    ]
  }
}
```

### 5.2 发音评测服务健康检查

```
GET /api/pronunciation/health
```

---

## 6. ASR 语音识别

**Controller**: `WhisperController`  
**基础路径**: `/api/asr`

### 6.1 转录音频文件

```
POST /api/asr/transcribe
```

**请求格式**: `multipart/form-data`

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `file` | file | 必填 | 音频文件（mp3/wav/m4a/ogg/webm） |
| `language` | string | 自动检测 | 语言代码（如 `en`, `zh`） |

**响应**:
```json
{
  "code": 200,
  "data": {
    "text": "Transcribed text here...",
    "language": "en",
    "duration": 5.2,
    "engine": "faster-whisper"
  }
}
```

> **说明**: 优先使用本地 Faster-Whisper（gRPC），不可用时自动回退到云端 Whisper API。

---

## 7. ASR 设置

**Controller**: `AsrSettingsController`  
**基础路径**: `/api/asr/settings`

### 7.1 获取 ASR 服务状态

```
GET /api/asr/settings/status
```

### 7.2 更新 ASR 设置

```
POST /api/asr/settings/update
```

**请求体**:
```json
{
  "model": "large-v2",
  "device": "cuda",
  "computeType": 5,
  "enableVad": true,
  "vadThresholdMs": 500,
  "windowSizeMs": 500,
  "language": ""
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `model` | string | 模型名称（large-v3/large-v2/medium/small/base/tiny） |
| `device` | string | 运行设备（cuda/cpu） |
| `computeType` | int | 计算类型编码 |
| `enableVad` | bool | 是否启用 VAD |
| `vadThresholdMs` | int | VAD 阈值（ms） |
| `windowSizeMs` | int | 滑动窗口大小（ms） |
| `language` | string | 语言代码（空为自动检测） |

### 7.3 获取可用模型列表

```
GET /api/asr/settings/models
```

---

## 8. AI 管线设置

**Controller**: `AiSettingsController`  
**基础路径**: `/api/settings/ai-pipeline`

### 8.1 获取用户管线配置

```
GET /api/settings/ai-pipeline?userId=xxx
```

### 8.2 保存用户管线配置

```
POST /api/settings/ai-pipeline?userId=xxx
```

### 8.3 获取会话级管线配置

```
GET /api/settings/ai-pipeline/session?sessionId=xxx
```

### 8.4 保存会话级管线配置

```
POST /api/settings/ai-pipeline/session?sessionId=xxx
```

---

## 9. 管线状态监控

**Controller**: `PipelineStatusController`  
**基础路径**: `/api/pipeline/status`

### 9.1 整体健康检查

```
GET /api/pipeline/status/health
```

### 9.2 TTS 服务状态

```
GET /api/pipeline/status/tts
```

### 9.3 活跃会话指标

```
GET /api/pipeline/status/sessions
```

返回所有活跃会话的延迟指标（LLM 首 token 延迟、TTS 首音频延迟、端到端延迟等）。

---

## 10. 音频文件服务

**Controller**: `AudioController`  
**基础路径**: `/api/audio`

### 10.1 获取 TTS 音频文件

```
GET /api/audio/tts/{filename}
```

返回 TTS 生成的音频文件（支持 mp3/wav），`Content-Type` 自动检测。

---

## 11. 语音连接测试

**Controller**: `VoiceTestController`  
**基础路径**: `/api/voice`

### 11.1 测试豆包 WebSocket 认证

```
POST /api/voice/test-auth
```

**请求体**:
```json
{
  "appId": "12345678",
  "accessToken": "your-access-token",
  "secretKey": "your-secret-key"
}
```

测试不同的认证组合（Access Token/Secret Key、Header/URL 参数），返回各组合的 WebSocket 握手结果。
