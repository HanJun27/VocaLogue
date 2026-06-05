/**
 * 语音服务门面类
 * 对外提供统一的语音服务接口，内部使用适配器模式支持多种模型
 */

import { IVoiceService, VoiceServiceConfig, VoiceServiceEventHandlers } from './IVoiceService'
import { VoiceServiceFactory } from './VoiceServiceFactory'
import { configService } from '@/config'
import type { Scenario } from '@/types'

/**
 * 语音服务类（门面模式）
 */
export class VoiceService {
  private adapter: IVoiceService | null = null
  private config: VoiceServiceConfig
  
  constructor() {
    this.config = this.buildConfigFromAppConfig()
  }
  
  /**
   * 从应用配置构建语音服务配置
   */
  private buildConfigFromAppConfig(): VoiceServiceConfig {
    const appConfig = configService.getConfig()
    
    return {
      apiKey: appConfig.apiKey,
      appId: appConfig.appId,
      modelProvider: appConfig.modelProvider === 'gpt-4o' ? 'openai' : 'doubao',
      sampleRate: appConfig.sampleRate,
      audioChunkSize: appConfig.audioChunkSize,
      vadEnabled: appConfig.vadEnabled,
      vadThreshold: appConfig.vadThreshold,
      vadSilenceDuration: appConfig.vadSilenceDuration,
      modelConfig: appConfig.modelConfig
    }
  }
  
  /**
   * 更新配置
   */
  updateConfig(): void {
    this.config = this.buildConfigFromAppConfig()
    if (this.adapter) {
      this.adapter.updateConfig(this.config)
    }
  }
  
  /**
   * 连接到语音服务
   */
  async connect(scenario: Scenario): Promise<void> {
    // 验证配置
    const validation = VoiceServiceFactory.validateConfig(this.config)
    if (!validation.valid) {
      throw new Error(`配置验证失败: ${validation.errors.join(', ')}`)
    }
    
    // 创建适配器
    this.adapter = VoiceServiceFactory.createVoiceService(this.config)
    
    // 注册事件处理器
    this.setupEventHandlers()
    
    // 连接
    await this.adapter.connect(scenario)
  }
  
  /**
   * 断开连接
   */
  disconnect(): void {
    if (this.adapter) {
      this.adapter.disconnect()
      this.adapter = null
    }
  }
  
  /**
   * 开始录音
   */
  async startRecording(): Promise<void> {
    if (!this.adapter) {
      throw new Error('请先连接到语音服务')
    }
    
    await this.adapter.startRecording()
  }
  
  /**
   * 停止录音
   */
  async stopRecording(): Promise<void> {
    if (!this.adapter) {
      return
    }
    
    await this.adapter.stopRecording()
  }
  
  /**
   * 打断当前播放/录音
   */
  async interrupt(): Promise<void> {
    if (!this.adapter) {
      return
    }
    
    await this.adapter.interrupt()
  }
  
  /**
   * 停止音频播放
   */
  stopAudioPlayback(): void {
    if (!this.adapter) {
      return
    }
    
    this.adapter.stopAudioPlayback()
  }
  
  /**
   * 测试连接
   */
  async testConnection(): Promise<{ success: boolean; message: string; latency?: number }> {
    // 临时创建适配器进行测试
    const testAdapter = VoiceServiceFactory.createVoiceService(this.config)
    const result = await testAdapter.testConnection()
    
    return result
  }
  
  /**
   * 注册事件处理器
   */
  on(event: keyof VoiceServiceEventHandlers, handler: any): void {
    // 保存处理器，在创建适配器后设置
    this.eventHandlers[event] = handler
    
    // 如果适配器已存在，立即设置
    if (this.adapter) {
      this.adapter.on(event, handler)
    }
  }
  
  /**
   * 获取服务能力
   */
  getCapabilities() {
    if (!this.adapter) {
      return {
        supportsRealtime: false,
        supportsInterimTranscript: false,
        supportsInterruption: false,
        maxAudioChunkSize: 0,
        recommendedSampleRate: 16000
      }
    }
    
    return this.adapter.getCapabilities()
  }
  
  /**
   * 获取浏览器能力信息
   */
  getBrowserCapabilities() {
    // 这里可以复用之前的 browserCompat 工具
    // 为了简化，暂时返回 null
    return null
  }
  
  /**
   * 检查是否可以使用语音功能
   */
  canUseVoice(): boolean {
    // 检查浏览器能力
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      return false
    }
    
    if (typeof MediaRecorder === 'undefined') {
      return false
    }
    
    if (typeof WebSocket === 'undefined') {
      return false
    }
    
    return true
  }
  
  // === 私有属性和方法 ===
  
  private eventHandlers: Partial<VoiceServiceEventHandlers> = {}
  
  /**
   * 设置事件处理器
   */
  private setupEventHandlers(): void {
    if (!this.adapter) return
    
    Object.entries(this.eventHandlers).forEach(([event, handler]) => {
      this.adapter!.on(event as keyof VoiceServiceEventHandlers, handler)
    })
  }
}

// 导出单例
export const voiceService = new VoiceService()