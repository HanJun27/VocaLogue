/**
 * 语音服务模块统一导出
 */

// 核心接口
export { IVoiceService } from './IVoiceService'
export type { 
  VoiceServiceConfig, 
  VoiceServiceEventHandlers, 
  TranscriptEvent, 
  ConnectionTestResult 
} from './IVoiceService'

// 适配器
export { OpenAIVoiceServiceAdapter } from './adapters/OpenAIVoiceServiceAdapter'
export { DoubaoVoiceServiceAdapter } from './adapters/DoubaoVoiceServiceAdapter'

// 工厂
export { VoiceServiceFactory } from './VoiceServiceFactory'

// 门面类
export { VoiceService, voiceService } from './VoiceService'