// 配置服务 - 使用 localStorage 保存用户设置

export interface AppConfig {
  // 语音大模型设置
  modelProvider: 'doubao' | 'gpt-4o';
  apiKey: string;
  appId?: string; // 豆包需要 App ID
  secretKey?: string; // 豆包需要 Secret Key（与 Access Token 不同）
  backendUrl: string; // 后端代理地址
  // 音频设置
  sampleRate: number; // 采样率，默认 16000 Hz
  audioChunkSize: number; // 音频块大小（毫秒），默认 200ms
  // VAD 设置
  vadEnabled: boolean;
  vadSilenceDuration: number; // 静音检测持续时间（毫秒），默认 500ms
  vadThreshold: number; // 音量阈值，0-1，默认 0.05
  // 麦克风设备
  audioInputDeviceId: string; // '' 表示默认设备
  // 其他设置
  enableSubtitles: boolean;
  // 模型特定配置
  modelConfig?: {
    // 豆包音色配置
    doubaoSpeaker?: string;
    // OpenAI 语音配置
    openaiVoice?: string;
    // 其他自定义配置
    [key: string]: any;
  };

  // ====== ASR→LLM→TTS 管线设置 (来自 everyone-can-use-english) ======
  enableAiPipeline: boolean;       // 总开关：是否启用 ASR→LLM→TTS 管线
  pipelineUseAsr: boolean;         // ASR 阶段：是否启用语音识别后处理（标点恢复）
  pipelineUseTts: boolean;         // TTS 阶段：AI 回复是否转语音
  pipelineAgentName: string;       // AI 角色名：Ava / Andrew / Emma / Interviewer
  pipelineLlmEngine: string;       // LLM 引擎：openai / deepseek / glm / qianwen / doubao
  pipelineLlmModel: string;        // LLM 模型：根据引擎选择
  pipelineTtsVoice: string;        // TTS 声音：alloy / echo / fable / onyx / nova / shimmer
  pipelineTtsEngine: string;       // TTS 引擎：openai / enjoyai
  // 国产大模型 API 配置
  deepseekApiKey: string;          // DeepSeek API Key
  glmApiKey: string;               // GLM (智谱) API Key
  glmApiUrl: string;               // GLM API 地址
  qianwenApiKey: string;           // 通义千问 API Key
  qianwenApiUrl: string;           // 通义千问 API 地址
}

const DEFAULT_CONFIG: AppConfig = {
  modelProvider: 'doubao',
  apiKey: '',
  backendUrl: 'http://localhost:8080',
  sampleRate: 16000,
  audioChunkSize: 200,
  audioInputDeviceId: '',
  vadEnabled: true,
  vadSilenceDuration: 500,
  vadThreshold: 0.05,
  enableSubtitles: true,
  modelConfig: {
    doubaoSpeaker: 'zh_female_vv_jupiter_bigtts', // 默认豆包音色
    openaiVoice: 'alloy' // 默认 OpenAI 音色
  },
  // ASR→LLM→TTS 管线默认值
  enableAiPipeline: false,
  pipelineUseAsr: false,
  pipelineUseTts: false,
  pipelineAgentName: 'Ava',
  pipelineLlmEngine: 'openai',
  pipelineLlmModel: 'gpt-4o',
  pipelineTtsVoice: 'alloy',
  pipelineTtsEngine: 'openai',
  // 国产大模型 API 配置默认值
  deepseekApiKey: '',
  glmApiKey: '',
  glmApiUrl: 'https://open.bigmodel.cn/api/paas/v4/',
  qianwenApiKey: '',
  qianwenApiUrl: 'https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation'
};

const CONFIG_KEY = 'lingoai_app_config';

class ConfigService {
  private config: AppConfig;

  constructor() {
    this.config = this.loadConfig();
  }

  private loadConfig(): AppConfig {
    try {
      const stored = localStorage.getItem(CONFIG_KEY);
      if (stored) {
        return { ...DEFAULT_CONFIG, ...JSON.parse(stored) };
      }
    } catch (e) {
      console.warn('Failed to load config from localStorage:', e);
    }
    return { ...DEFAULT_CONFIG };
  }

  saveConfig(): void {
    try {
      localStorage.setItem(CONFIG_KEY, JSON.stringify(this.config));
    } catch (e) {
      console.error('Failed to save config to localStorage:', e);
    }
  }

  getConfig(): AppConfig {
    return { ...this.config };
  }

  updateConfig(updates: Partial<AppConfig>): void {
    this.config = { ...this.config, ...updates };
    this.saveConfig();
  }

  resetConfig(): void {
    this.config = { ...DEFAULT_CONFIG };
    this.saveConfig();
  }

  // 便捷方法
  hasApiKey(): boolean {
    return this.config.apiKey.trim().length > 0;
  }

  getApiKey(): string {
    return this.config.apiKey;
  }

  /**
   * 检查当前配置是否完整
   */
  isConfigComplete(): boolean {
    if (!this.hasApiKey()) {
      return false;
    }

    // 豆包需要 App ID
    if (this.config.modelProvider === 'doubao' && !this.config.appId) {
      return false;
    }

    return true;
  }

  /**
   * 获取配置错误信息
   */
  getConfigErrors(): string[] {
    const errors: string[] = [];

    if (!this.hasApiKey()) {
      errors.push('API Key 不能为空');
    }

    if (this.config.modelProvider === 'doubao' && !this.config.appId) {
      errors.push('豆包模型需要配置 App ID');
    }

    return errors;
  }
}

export const configService = new ConfigService();
