/**
 * 语音服务统一接口
 * 定义所有语音服务必须实现的标准接口
 */

import type { Scenario } from '@/types'

/**
 * 语音服务事件类型
 */
export type VoiceServiceEventType = 
  | 'connected'
  | 'disconnected'
  | 'error'
  | 'transcript'
  | 'audio_start'
  | 'audio_chunk'
  | 'audio_end'
  | 'recording_state_change'
  | 'interim_transcript'

/**
 * 转写事件
 */
export interface TranscriptEvent {
  type: 'transcript'
  role: 'user' | 'ai'
  text: string
  translation?: string
  final: boolean
}

/**
 * 音频事件
 */
export interface AudioEvent {
  type: 'audio'
  data: Uint8Array
}

/**
 * 语音服务事件处理器
 */
export interface VoiceServiceEventHandlers {
  onConnected?: () => void
  onDisconnected?: (error?: Error) => void
  onError?: (error: Error) => void
  onTranscript?: (event: TranscriptEvent) => void
  onAudioStart?: () => void
  onAudioChunk?: (chunk: Uint8Array) => void
  onAudioEnd?: () => void
  onRecordingStateChange?: (isRecording: boolean) => void
  onInterimTranscript?: (text: string) => void
}

/**
 * 语音服务配置
 */
export interface VoiceServiceConfig {
  // API 配置
  apiKey: string
  appId?: string
  modelProvider: 'openai' | 'doubao' | 'custom'
  
  // 音频配置
  sampleRate: number
  audioChunkSize: number
  
  // VAD 配置
  vadEnabled: boolean
  vadThreshold: number
  vadSilenceDuration: number
  
  // 模型特定配置
  modelConfig?: Record<string, any>
}

/**
 * 连接测试结果
 */
export interface ConnectionTestResult {
  success: boolean
  message: string
  latency?: number
}

/**
 * 语音服务统一接口
 * 所有语音服务适配器必须实现此接口
 */
export interface IVoiceService {
  /**
   * 连接到语音服务
   * @param scenario 场景配置
   */
  connect(scenario: Scenario): Promise<void>
  
  /**
   * 断开连接
   */
  disconnect(): void
  
  /**
   * 开始录音
   */
  startRecording(): Promise<void>
  
  /**
   * 停止录音
   */
  stopRecording(): Promise<void>
  
  /**
   * 打断当前播放/录音
   */
  interrupt(): Promise<void>
  
  /**
   * 停止音频播放
   */
  stopAudioPlayback(): void
  
  /**
   * 测试连接
   */
  testConnection(): Promise<ConnectionTestResult>
  
  /**
   * 注册事件处理器
   */
  on(event: VoiceServiceEventType, handler: (...args: any[]) => void): void
  
  /**
   * 获取服务配置
   */
  getConfig(): VoiceServiceConfig
  
  /**
   * 更新服务配置
   */
  updateConfig(config: Partial<VoiceServiceConfig>): void
  
  /**
   * 获取服务能力
   */
  getCapabilities(): {
    supportsRealtime: boolean
    supportsInterimTranscript: boolean
    supportsInterruption: boolean
    maxAudioChunkSize: number
    recommendedSampleRate: number
  }
}