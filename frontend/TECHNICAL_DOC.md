# LingoAI 英语口语陪练 - 前端技术文档

## 1. 项目概述

### 1.1 项目简介

LingoAI 是一款 AI 英语口语陪练应用，旨在帮助用户通过模拟对话场景提升英语口语能力。应用提供多种练习场景（面试准备、餐厅点餐、国际会议），支持语音识别和合成功能，实时评测发音质量并提供改进建议。

### 1.2 技术栈

| 分类 | 技术 | 版本 |
|------|------|------|
| 框架 | Vue | 3.5.x |
| 构建工具 | Vite | 6.4.x |
| 语言 | TypeScript | 5.8.x |
| 样式 | Tailwind CSS | 4.3.x |
| 图标 | Lucide Vue | 1.0.x |
| 语音 API | Web Speech API | - |

### 1.3 项目结构

```
frontend/
├── src/
│   ├── components/           # Vue 组件
│   │   ├── Header.vue          # 顶部导航栏
│   │   ├── ChatArea.vue        # 聊天消息区域
│   │   ├── ControlPanel.vue    # 底部控制面板
│   │   ├── ScenarioSelection.vue # 场景选择页
│   │   ├── PracticeSummary.vue  # 练习总结页
│   │   └── PronunciationModal.vue # 发音评测弹窗
│   ├── types.ts              # TypeScript 类型定义
│   ├── scenariosData.ts      # 场景数据配置
│   ├── App.vue               # 主应用组件
│   ├── main.ts               # 入口文件
│   └── style.css             # 全局样式
├── public/                   # 静态资源
├── dist/                     # 构建产物
├── vite.config.ts            # Vite 配置
├── tsconfig.app.json         # TypeScript 配置
└── package.json              # 依赖配置
```

---

## 2. 前端元素分析

### 2.1 组件架构

| 组件 | 职责 | 状态管理 | 交互方式 |
|------|------|----------|----------|
| **Header** | 导航、场景切换、状态显示 | 无状态（纯展示） | 点击导航、下拉选择 |
| **ChatArea** | 消息展示、自动滚动、播放控制 | 消息列表、播放状态 | 点击播放、切换翻译 |
| **ControlPanel** | 录音/打字模式切换、字幕开关 | 录音状态、输入内容 | 麦克风按钮、输入框 |
| **ScenarioSelection** | 场景卡片展示、难度标识 | 无状态 | 点击卡片选择 |
| **PracticeSummary** | 成绩展示、雷达图、语法纠正 | 计算属性 | 按钮操作 |
| **PronunciationModal** | 发音得分详情 | 无状态 | 关闭弹窗 |

### 2.2 核心状态管理（App.vue）

```typescript
// 视图状态
currentView: 'scenarios' | 'practice' | 'summary'  // 当前页面
currentScenario: Scenario                           // 当前选中场景

// 对话状态
messages: DialectMessage[]                          // 消息列表
currentQuestionIndex: number                        // 当前问题索引

// 语音状态
isPlayingAudio: boolean                             // 是否正在播放
activeVoiceMessageId: string | null                 // 当前播放的消息ID
isRecording: boolean                                // 是否正在录音
currentTranscript: string                           // 实时转写内容

// 配置状态
subtitlesOn: boolean                               // 字幕开关
isTypingMode: boolean                               // 打字模式

// 评测状态
currentRating: PronunciationScore | null            // 当前评测分数
ratingOpen: boolean                                 // 评测弹窗状态
```

### 2.3 数据模型

#### 2.3.1 DialectMessage（消息）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | string | 消息唯一标识 |
| role | 'ai' \| 'user' | 消息角色 |
| text | string | 消息内容 |
| translation | string | 中文翻译（AI消息） |
| timestamp | string | 时间戳 |
| showTranslation | boolean | 是否显示翻译 |
| feedback | GrammarFeedback | 语法反馈（用户消息） |

#### 2.3.2 Scenario（场景）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | string | 场景唯一标识 |
| title | string | 场景标题 |
| tag | string | 场景标签 |
| emoji | string | 场景图标 |
| difficulty | number | 难度等级（1-3） |
| description | string | 场景描述 |
| welcomeMessage | string | 欢迎语（英文） |
| welcomeTranslation | string | 欢迎语（中文） |
| questions | InterviewQuestion[] | 问题列表 |

#### 2.3.3 PronunciationScore（发音评分）

| 字段 | 类型 | 说明 |
|------|------|------|
| accuracy | number | 发音精准度（0-100） |
| fluency | number | 语流连贯度（0-100） |
| grammar | number | 词汇语法深度（0-100） |
| overall | number | 综合得分（0-100） |
| feedbackSummary | string | 诊断建议 |

---

## 3. 功能需求分析

### 3.1 功能模块划分

| 模块 | 功能点 | 优先级 | 状态 |
|------|--------|--------|------|
| **场景管理** | 场景列表展示、场景选择、难度标识 | 高 | 已实现 |
| **对话练习** | AI提问、语音回答、文字回答、消息展示 | 高 | 已实现 |
| **语音合成** | TTS播放、播放控制、自动播放 | 高 | 已实现 |
| **语音识别** | ASR录音、实时转写、录音控制 | 高 | 已实现 |
| **发音评测** | 发音评分、详细指标、诊断建议 | 高 | 已实现（模拟） |
| **练习总结** | 成绩展示、雷达图、语法纠正 | 高 | 已实现 |
| **字幕翻译** | 中英双语切换、单条消息翻译切换 | 中 | 已实现 |

### 3.2 核心业务流程

#### 3.2.1 场景选择流程

```
用户进入首页 → 浏览场景卡片 → 选择场景 → 进入练习页面 → AI自动播放欢迎语
```

#### 3.2.2 对话练习流程

```
AI提问 → 用户语音/文字回答 → 系统生成评测 → AI继续提问/结束 → 进入总结
```

#### 3.2.3 语音交互流程

```
点击麦克风 → 请求权限 → 开始录音 → 实时转写 → 停止录音 → 提交回答
```

### 3.3 用户故事

| ID | 用户故事 | 验收标准 |
|----|----------|----------|
| US001 | 作为用户，我想选择不同场景练习 | 能看到场景列表，点击进入对应场景 |
| US002 | 作为用户，我想用语音回答问题 | 麦克风按钮可用，支持录音和转写 |
| US003 | 作为用户，我想用文字回答问题 | 可以切换打字模式，输入框可用 |
| US004 | 作为用户，我想听到AI的提问 | 自动播放或点击播放按钮 |
| US005 | 作为用户，我想看到中文翻译 | 字幕开关控制，显示翻译内容 |
| US006 | 作为用户，我想查看发音评测 | 回答后自动生成评分，可查看详情 |
| US007 | 作为用户，我想看到练习总结 | 完成练习后进入总结页面 |

---

## 4. 接口需求分析

### 4.1 接口概述

当前版本为纯前端模拟实现，后端接口待开发。以下为预期的后端接口设计：

### 4.2 接口设计

#### 4.2.1 场景管理接口

| API路径 | HTTP方法 | 功能描述 |
|---------|----------|----------|
| `/api/scenarios` | GET | 获取场景列表 |
| `/api/scenarios/{id}` | GET | 获取单个场景详情 |
| `/api/scenarios` | POST | 创建新场景（管理员） |
| `/api/scenarios/{id}` | PUT | 更新场景（管理员） |
| `/api/scenarios/{id}` | DELETE | 删除场景（管理员） |

**GET /api/scenarios 响应示例**：
```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": "frontend",
      "title": "面试准备",
      "tag": "Web Dev",
      "emoji": "💼",
      "difficulty": 3,
      "description": "技术面试练习"
    }
  ]
}
```

#### 4.2.2 对话接口

| API路径 | HTTP方法 | 功能描述 |
|---------|----------|----------|
| `/api/conversation/start` | POST | 开始新对话 |
| `/api/conversation/{id}/message` | POST | 发送消息，获取AI回复 |
| `/api/conversation/{id}` | GET | 获取对话历史 |
| `/api/conversation/{id}` | DELETE | 结束对话 |

**POST /api/conversation/start 请求**：
```json
{
  "scenarioId": "frontend"
}
```

**POST /api/conversation/{id}/message 请求**：
```json
{
  "text": "I have three years of experience...",
  "isVoice": true
}
```

**POST /api/conversation/{id}/message 响应**：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "aiResponse": "That's great! Can you explain...",
    "rating": {
      "accuracy": 85,
      "fluency": 90,
      "grammar": 82,
      "overall": 88,
      "feedbackSummary": "Your pronunciation is clear..."
    },
    "grammarFeedback": {
      "original": "I have been working...",
      "suggested": "I bring three years...",
      "title": "建议优化表达",
      "explanation": "..."
    }
  }
}
```

#### 4.2.3 发音评测接口

| API路径 | HTTP方法 | 功能描述 |
|---------|----------|----------|
| `/api/pronunciation/evaluate` | POST | 语音评测（接收音频） |

**POST /api/pronunciation/evaluate 请求**：
```json
{
  "audioBase64": "data:audio/wav;base64,...",
  "text": "Hello world"
}
```

**响应示例**：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "accuracy": 85,
    "fluency": 90,
    "grammar": 82,
    "overall": 88,
    "feedbackSummary": "Your pronunciation is clear..."
  }
}
```

#### 4.2.4 用户进度接口

| API路径 | HTTP方法 | 功能描述 |
|---------|----------|----------|
| `/api/user/progress` | GET | 获取用户学习进度 |
| `/api/user/progress/scenario/{id}` | GET | 获取特定场景进度 |
| `/api/user/history` | GET | 获取历史练习记录 |

---

## 5. 非功能需求

### 5.1 性能需求

| 指标 | 要求 |
|------|------|
| 首屏加载时间 | < 2s |
| 页面切换时间 | < 500ms |
| 消息发送响应 | < 1s（前端），< 3s（含后端） |
| 语音识别延迟 | < 100ms（实时转写） |

### 5.2 兼容性需求

| 浏览器 | 版本 | 状态 |
|--------|------|------|
| Chrome | >= 90 | 支持 |
| Safari | >= 14 | 支持 |
| Firefox | >= 89 | 支持 |
| Edge | >= 90 | 支持 |

### 5.3 安全需求

| 需求 | 说明 |
|------|------|
| HTTPS | 全站HTTPS加密 |
| 用户认证 | JWT Token认证 |
| 权限控制 | 用户数据隔离 |
| 音频数据 | 传输加密，定期清理 |

---

## 6. 部署与集成

### 6.1 环境配置

**开发环境**：
```bash
npm install
npm run dev    # 启动开发服务器
```

**生产环境**：
```bash
npm install
npm run build  # 构建生产版本
```

### 6.2 环境变量

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| VITE_API_URL | 后端API地址 | http://localhost:3000 |
| VITE_APP_TITLE | 应用标题 | LingoAI |

---

## 7. 代码规范

### 7.1 命名规范

- **组件名**：大驼峰，如 `Header.vue`
- **变量名**：小驼峰，如 `currentScenario`
- **常量名**：全大写下划线，如 `SCENARIOS`
- **文件命名**：小写连字符，如 `scenarios-data.ts`

### 7.2 组件开发规范

1. 使用 `<script setup lang="ts">` 语法
2. Props 定义使用 `defineProps<Props>()`
3. Emits 定义使用 `defineEmits<{...}>()`
4. 组件职责单一，避免过度嵌套
5. 使用 TypeScript 严格类型检查

---

## 8. 后续开发建议

### 8.1 待实现功能

| 功能 | 描述 | 优先级 |
|------|------|--------|
| 后端集成 | 连接真实后端服务 | 高 |
| 用户认证 | 登录/注册功能 | 高 |
| 数据持久化 | 保存学习进度 | 高 |
| 多语言支持 | 支持英语/中文界面 | 中 |
| 社交分享 | 分享成绩到社交平台 | 中 |
| 错题本 | 收集用户常见错误 | 中 |

### 8.2 技术优化

| 优化项 | 描述 |
|--------|------|
| 音频缓存 | 缓存TTS音频，减少重复请求 |
| 离线支持 | PWA离线模式 |
| 性能监控 | 集成监控工具 |
| 错误边界 | 添加全局错误处理 |

---

**文档版本**: v1.0  
**创建日期**: 2026-06-05  
**适用项目**: LingoAI 英语口语陪练