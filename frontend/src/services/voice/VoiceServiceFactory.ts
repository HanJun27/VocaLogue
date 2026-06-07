/**
 * 语音服务工厂
 * 根据配置创建对应的语音服务适配器
 */

import type { IVoiceService } from './IVoiceService'
import type { VoiceServiceConfig } from './IVoiceService'
import { OpenAIVoiceServiceAdapter } from './adapters/OpenAIVoiceServiceAdapter'
import { DoubaoVoiceServiceAdapter } from './adapters/DoubaoVoiceServiceAdapter'
import { PipelineWebSocketAdapter } from './adapters/PipelineWebSocketAdapter'

/**
 * 语音服务工厂类
 */
export class VoiceServiceFactory {
  /**
   * 创建语音服务实例
   * @param config 语音服务配置
   * @returns 语音服务实例
   */
  static createVoiceService(config: VoiceServiceConfig): IVoiceService {
    console.log('[VoiceServiceFactory] 创建语音服务适配器，模型提供商:', config.modelProvider)
    
    switch (config.modelProvider) {
      case 'openai':
        console.log('[VoiceServiceFactory] 创建 OpenAI 适配器')
        return new OpenAIVoiceServiceAdapter(config)
      
      case 'doubao':
        console.log('[VoiceServiceFactory] 创建豆包适配器')
        return new DoubaoVoiceServiceAdapter(config)
      
      case 'pipeline':
        console.log('[VoiceServiceFactory] 创建管线 WebSocket 适配器')
        return new PipelineWebSocketAdapter(config)

      case 'custom':
        throw new Error('自定义适配器暂未实现')
      
      default:
        throw new Error(`不支持的模型提供商: ${config.modelProvider}`)
    }
  }
  
  /**
   * 获取支持的模型提供商列表
   */
  static getSupportedProviders(): Array<{
    id: string
    name: string
    description: string
    requiresAppId: boolean
  }> {
    return [
      {
        id: 'openai',
        name: 'OpenAI GPT-4o Realtime',
        description: 'OpenAI 的实时语音对话模型，支持低延迟语音交互',
        requiresAppId: false
      },
      {
        id: 'doubao',
        name: '豆包 Realtime',
        description: '字节跳动的端到端实时语音大模型，支持中英文',
        requiresAppId: true
      },
      {
        id: 'pipeline',
        name: 'ASR→LLM→TTS 管线',
        description: '自定义管线模式，通过后端 WebSocket 协调 ASR/LLM/TTS',
        requiresAppId: false
      },
      {
        id: 'custom',
        name: '自定义模型',
        description: '自定义语音服务适配器',
        requiresAppId: false
      }
    ]
  }
  
  /**
   * 验证配置是否完整
   */
  static validateConfig(config: VoiceServiceConfig): {
    valid: boolean
    errors: string[]
  } {
    const errors: string[] = []
    
    // Pipeline 模式不需要前端 API Key（后端管理密钥）
    if (config.modelProvider !== 'pipeline') {
      // 验证 API Key
      if (!config.apiKey || config.apiKey.trim().length === 0) {
        errors.push('API Key 不能为空')
      }
    }
    
    // 验证 App ID（豆包需要）
    if (config.modelProvider === 'doubao') {
      if (!config.appId || config.appId.trim().length === 0) {
        errors.push('豆包模型需要配置 App ID')
      }
    }
    
    // 验证采样率
    if (config.sampleRate < 8000 || config.sampleRate > 48000) {
      errors.push('采样率必须在 8000-48000 之间')
    }
    
    // 验证音频块大小
    if (config.audioChunkSize < 10 || config.audioChunkSize > 1000) {
      errors.push('音频块大小必须在 10-1000ms 之间')
    }
    
    // 验证 VAD 阈值
    if (config.vadThreshold < 0 || config.vadThreshold > 1) {
      errors.push('VAD 阈值必须在 0-1 之间')
    }
    
    // 验证 VAD 静音持续时间
    if (config.vadSilenceDuration < 100 || config.vadSilenceDuration > 5000) {
      errors.push('VAD 静音持续时间必须在 100-5000ms 之间')
    }
    
    return {
      valid: errors.length === 0,
      errors
    }
  }
}