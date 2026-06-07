export interface GrammarFeedback {
  original: string;
  suggested: string;
  title: string;
  explanation: string;
}

export interface DialectMessage {
  id: string;
  role: 'ai' | 'user';
  text: string;
  translation?: string;
  timestamp: string;
  showTranslation?: boolean;
  feedback?: GrammarFeedback;
  isPlaying?: boolean;
}

export interface InterviewQuestion {
  text: string;
  translation: string;
  keywords: {
    phrase: string;
    suggested: string;
    explanation: string;
  }[];
}

export interface Scenario {
  id: string;
  title: string;
  tag: string;
  welcomeMessage: string;
  welcomeTranslation: string;
  systemPrompt: string;
  questions: InterviewQuestion[];
  emoji?: string;
  difficulty?: number;
  description?: string;
}

export interface PronunciationScore {
  accuracy: number;
  fluency: number;
  grammar: number;
  overall: number;
  feedbackSummary: string;
}

// ====== ASR→LLM→TTS 管线相关类型 (来自 everyone-can-use-english) ======

/** AI 角色信息 */
export interface AgentInfo {
  name: string;
  description: string;
  language: string;
  ttsVoice: string;
  ttsModel: string;
}

/** LLM 引擎类型 */
export type LlmEngine = 'openai' | 'deepseek' | 'glm' | 'qianwen' | 'doubao';

/** AI 管线配置 */
export interface PipelineConfig {
  useAsr: boolean;
  useTts: boolean;
  agentName: string;
  asrEngine: string;
  llmEngine: LlmEngine;
  llmModel: string;
  llmApiKey?: string;       // LLM API Key（可选，不持久化存储）
  llmBaseUrl?: string;      // LLM Base URL（可选）
  llmTemperature: number;
  ttsEngine: string;
  ttsModel: string;
  ttsVoice: string;
}

/** AI 对话请求 */
export interface AiChatRequest {
  sessionId: string;
  text: string;
  userId?: string;
  useAsr?: boolean;
  useTts?: boolean;
  pipelineConfig?: Partial<PipelineConfig>;
}

/** 会话摘要（用于历史列表） */
export interface ConversationSummary {
  sessionId: string;
  userId: string;
  scenarioId: string;
  scenarioTitle: string;
  scenarioEmoji: string;
  scenarioTag: string;
  startTime: string;
  endTime: string | null;
  overallScore: number | null;
  messageCount: number;
}

/** AI 对话响应 */
export interface AiChatResponse {
  userText: string;
  aiResponseText: string;
  translatedText?: string;
  analysisText?: string;
  ttsUrl?: string;
  agentName: string;
  agentDescription: string;
  pipelineConfig: {
    useAsr: boolean;
    useTts: boolean;
    asrEngine: string;
    llmModel: string;
    ttsEngine: string;
    ttsModel: string;
    ttsVoice: string;
  };
}

// ====== LLM 评测总结相关类型 (由 EvaluationService 生成) ======

/** 单个维度的评分 + 详细文本评价 */
export interface DimensionDetail {
  score: number;
  evaluation: string;
}

/** 五维能力评估 */
export interface PracticeDimensions {
  pronunciation: DimensionDetail;
  grammar: DimensionDetail;
  fluency: DimensionDetail;
  vocabulary: DimensionDetail;
  interactivity: DimensionDetail;
}

/** 语法/表达错误纠正条目 */
export interface PracticeErrorItem {
  original: string;
  corrected: string;
  type: string;
}

/** 行动力提升建议 */
export interface PracticeSuggestion {
  title: string;
  description: string;
}

/** 练习总结（匹配后端 PracticeSummaryDTO） */
export interface PracticeSummaryResult {
  overallScore: number;
  dimensions: PracticeDimensions;
  errors: PracticeErrorItem[];
  suggestions: PracticeSuggestion[];
}

// ====== 发音评测相关类型 (wav2vec2 Python 微服务返回) ======

/** 词级发音评分 */
export interface WordPronunciationScore {
  word: string;
  accuracy_score: number;
  expected_phonemes: string;
  phoneme_scores: number[];
}

/** 发音评测结果（wav2vec2 Python 微服务返回） */
export interface PronunciationResult {
  accuracy_score: number;
  fluency_score: number;
  completeness_score: number;
  overall_pronunciation_score: number;
  word_scores: WordPronunciationScore[];
}
