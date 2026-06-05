// 配置服务 - 使用 localStorage 保存用户设置

export interface AppConfig {
  // 语音大模型设置
  modelProvider: 'doubao' | 'gpt-4o';
  apiKey: string;
  appId?: string; // 豆包需要 App ID
  // 音频设置
  sampleRate: number; // 采样率，默认 16000 Hz
  audioChunkSize: number; // 音频块大小（毫秒），默认 200ms
  // VAD 设置
  vadEnabled: boolean;
  vadSilenceDuration: number; // 静音检测持续时间（毫秒），默认 500ms
  vadThreshold: number; // 音量阈值，0-1，默认 0.05
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
}

const DEFAULT_CONFIG: AppConfig = {
  modelProvider: 'doubao',
  apiKey: '',
  sampleRate: 16000,
  audioChunkSize: 200,
  vadEnabled: true,
  vadSilenceDuration: 500,
  vadThreshold: 0.05,
  enableSubtitles: true,
  modelConfig: {
    doubaoSpeaker: 'zh_female_vv_jupiter_bigtts', // 默认豆包音色
    openaiVoice: 'alloy' // 默认 OpenAI 音色
  }
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
