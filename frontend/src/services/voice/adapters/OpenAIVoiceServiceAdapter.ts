/**
 * OpenAI Realtime API 适配器
 * 实现 OpenAI GPT-4o Realtime API
 */

import type { IVoiceService } from '../IVoiceService'
import type { 
  VoiceServiceConfig, 
  ConnectionTestResult,
  VoiceServiceEventHandlers 
} from '../IVoiceService'
import type { Scenario } from '@/types'

/**
 * OpenAI 语音服务适配器
 */
export class OpenAIVoiceServiceAdapter implements IVoiceService {
  private ws: WebSocket | null = null
  private mediaStream: MediaStream | null = null
  private mediaRecorder: MediaRecorder | null = null
  private audioContext: AudioContext | null = null
  private playbackContext: AudioContext | null = null
  private playbackQueue: AudioBuffer[] = []
  private isPlayingAudio = false
  private currentSource: AudioBufferSourceNode | null = null
  
  private isConnected = false
  private isRecording = false
  
  private config: VoiceServiceConfig
  private handlers: Partial<VoiceServiceEventHandlers> = {}
  
  // OpenAI 特定配置
  private readonly OPENAI_WS_URL = 'wss://api.openai.com/v1/realtime?model=gpt-4o-realtime-preview-2024-10-01'
  
  constructor(config: VoiceServiceConfig) {
    this.config = config
  }
  
  // === IVoiceService 实现 ===
  
  async connect(scenario: Scenario): Promise<void> {
    if (!this.config.apiKey) {
      throw new Error('请先配置 API Key')
    }
    
    try {
      // 建立 WebSocket 连接
      await this.connectWebSocket()
      
      // 发送会话配置
      this.sendSessionConfig(scenario)
      
      // 发送欢迎消息
      this.sendWelcomeMessage(scenario)
      
      this.isConnected = true
      this.handlers.onConnected?.()
    } catch (error) {
      console.error('[OpenAIAdapter] 连接失败:', error)
      this.handlers.onError?.(error as Error)
      throw error
    }
  }
  
  disconnect(): void {
    this.stopRecording()
    this.stopAudioPlayback()
    
    if (this.ws) {
      this.ws.close()
      this.ws = null
    }
    
    this.isConnected = false
  }
  
  async startRecording(): Promise<void> {
    if (!this.isConnected) {
      throw new Error('请先连接到语音服务')
    }
    
    if (this.isRecording) {
      console.warn('[OpenAIAdapter] 已经在录音中')
      return
    }
    
    try {
      // 获取麦克风权限
      const audioConstraints: MediaTrackConstraints = {
        sampleRate: this.config.sampleRate,
        channelCount: 1,
        echoCancellation: true,
        noiseSuppression: true
      }
      if (this.config.audioInputDeviceId) {
        audioConstraints.deviceId = { exact: this.config.audioInputDeviceId }
      }
      this.mediaStream = await navigator.mediaDevices.getUserMedia({
        audio: audioConstraints
      })
      
      // 使用 MediaRecorder 录制
      this.mediaRecorder = new MediaRecorder(this.mediaStream, {
        mimeType: 'audio/webm;codecs=opus',
        audioBitsPerSecond: 128000
      })
      
      this.mediaRecorder.ondataavailable = async (event) => {
        if (event.data.size > 0) {
          await this.processAndSendAudioChunk(event.data)
        }
      }
      
      // 每 100ms 发送一次音频数据
      this.mediaRecorder.start(100)
      
      // 发送开始录音信号
      this.sendAudioBufferClear()
      
      this.isRecording = true
      this.handlers.onRecordingStateChange?.(true)
      
    } catch (error) {
      console.error('[OpenAIAdapter] 获取麦克风权限失败:', error)
      throw new Error('无法获取麦克风权限，请检查浏览器设置')
    }
  }
  
  async stopRecording(): Promise<void> {
    if (!this.isRecording) return
    
    this.isRecording = false
    this.handlers.onRecordingStateChange?.(false)
    
    // 停止 MediaRecorder
    if (this.mediaRecorder && this.mediaRecorder.state !== 'inactive') {
      this.mediaRecorder.stop()
      this.mediaRecorder = null
    }
    
    // 关闭媒体流
    if (this.mediaStream) {
      this.mediaStream.getTracks().forEach(track => track.stop())
      this.mediaStream = null
    }
    
    // 发送结束信号
    this.sendAudioBufferCommit()
    this.sendResponseCreate()
  }
  
  async interrupt(): Promise<void> {
    // 停止录音
    await this.stopRecording()
    
    // 停止音频播放
    this.stopAudioPlayback()
    
    // 发送取消信号
    this.sendResponseCancel()
  }
  
  stopAudioPlayback(): void {
    if (this.currentSource) {
      try {
        this.currentSource.stop()
      } catch (e) {
        // 忽略已停止的错误
      }
      this.currentSource = null
    }
    
    this.playbackQueue = []
    this.isPlayingAudio = false
  }
  
  async testConnection(): Promise<ConnectionTestResult> {
    const startTime = Date.now()
    
    console.log('[OpenAIAdapter] 开始测试连接...')
    console.log('[OpenAIAdapter] API Key:', this.config.apiKey ? `${this.config.apiKey.substring(0, 10)}...` : '未设置')
    
    try {
      console.log('[OpenAIAdapter] 尝试请求 OpenAI API...')
      const response = await fetch('https://api.openai.com/v1/models', {
        headers: {
          'Authorization': `Bearer ${this.config.apiKey}`
        }
      })
      
      const latency = Date.now() - startTime
      console.log('[OpenAIAdapter] 响应状态:', response.status, response.statusText)
      
      if (response.ok) {
        console.log('[OpenAIAdapter] 测试成功')
        return {
          success: true,
          message: 'OpenAI API Key 有效',
          latency
        }
      } else {
        const errorData = await response.json().catch(() => null)
        console.error('[OpenAIAdapter] 测试失败:', errorData)
        return {
          success: false,
          message: errorData?.error?.message || `API Key 无效 (状态码: ${response.status})`,
          latency
        }
      }
    } catch (error) {
      console.error('[OpenAIAdapter] 网络错误:', error)
      return {
        success: false,
        message: `网络连接失败: ${error instanceof Error ? error.message : '未知错误'}`,
        latency: Date.now() - startTime
      }
    }
  }
  
  on(event: string, handler: (...args: any[]) => void): void {
    this.handlers[event as keyof VoiceServiceEventHandlers] = handler as any
  }
  
  getConfig(): VoiceServiceConfig {
    return { ...this.config }
  }
  
  updateConfig(config: Partial<VoiceServiceConfig>): void {
    this.config = { ...this.config, ...config }
  }
  
  getCapabilities() {
    return {
      supportsRealtime: true,
      supportsInterimTranscript: true,
      supportsInterruption: true,
      maxAudioChunkSize: 4096,
      recommendedSampleRate: 24000
    }
  }
  
  // === 私有方法 ===
  
  /**
   * 建立 WebSocket 连接
   */
  private async connectWebSocket(): Promise<void> {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(this.OPENAI_WS_URL)
      this.ws.binaryType = 'arraybuffer'
      
      this.ws.onopen = () => {
        console.log('[OpenAIAdapter] WebSocket 已连接')
        resolve()
      }
      
      this.ws.onmessage = (event) => {
        this.handleMessage(event)
      }
      
      this.ws.onerror = (error) => {
        console.error('[OpenAIAdapter] WebSocket 错误:', error)
        reject(new Error('WebSocket 连接错误'))
      }
      
      this.ws.onclose = (event) => {
        console.log('[OpenAIAdapter] WebSocket 已关闭:', event.code, event.reason)
        this.isConnected = false
        this.handlers.onDisconnected?.(
          event.code !== 1000 ? new Error(`连接关闭: ${event.reason}`) : undefined
        )
      }
    })
  }
  
  /**
   * 发送会话配置
   */
  private sendSessionConfig(scenario: Scenario): void {
    const config = {
      type: 'session.update',
      session: {
        modalities: ['text', 'audio'],
        instructions: scenario.systemPrompt,
        voice: 'alloy',
        input_audio_format: 'pcm16',
        output_audio_format: 'pcm16',
        input_audio_transcription: { model: 'whisper-1' },
        turn_detection: { type: 'server_vad' }
      }
    }
    
    this.sendMessage(config)
  }
  
  /**
   * 发送欢迎消息
   */
  private sendWelcomeMessage(scenario: Scenario): void {
    const welcomeMsg = {
      type: 'conversation.item.create',
      item: {
        type: 'message',
        role: 'assistant',
        content: [{
          type: 'text',
          text: scenario.welcomeMessage
        }]
      }
    }
    
    this.sendMessage(welcomeMsg)
  }
  
  /**
   * 发送 JSON 消息
   */
  private sendMessage(data: Record<string, any>): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      console.warn('[OpenAIAdapter] WebSocket 未连接，无法发送消息')
      return
    }
    
    this.ws.send(JSON.stringify(data))
  }
  
  /**
   * 处理接收到的消息
   */
  private handleMessage(event: MessageEvent): void {
    try {
      // 处理二进制音频数据
      if (event.data instanceof ArrayBuffer) {
        const audioData = new Uint8Array(event.data)
        this.handlers.onAudioChunk?.(audioData)
        this.playAudioChunk(audioData)
        return
      }
      
      // 处理文本消息
      const data = typeof event.data === 'string' 
        ? JSON.parse(event.data) 
        : event.data
      
      this.handleOpenAIMessage(data)
    } catch (error) {
      console.error('[OpenAIAdapter] 解析消息失败:', error)
    }
  }
  
  /**
   * 处理 OpenAI 消息
   */
  private handleOpenAIMessage(data: any): void {
    switch (data.type) {
      case 'session.created':
        console.log('[OpenAIAdapter] OpenAI 会话已创建')
        break
        
      case 'session.updated':
        console.log('[OpenAIAdapter] OpenAI 会话配置已更新')
        break
        
      case 'response.audio.delta':
        // 音频流数据
        if (data.delta) {
          const audioData = this.base64ToUint8Array(data.delta)
          this.handlers.onAudioChunk?.(audioData)
          this.playAudioChunk(audioData)
        }
        break
        
      case 'response.audio_transcript.delta':
        // AI 文本转写流
        this.handlers.onTranscript?.({
          type: 'transcript',
          role: 'ai',
          text: data.delta || '',
          final: false
        })
        break
        
      case 'response.audio.done':
        this.handlers.onAudioEnd?.()
        break
        
      case 'response.content_part.done':
        // 内容完成
        if (data.part?.transcript) {
          this.handlers.onTranscript?.({
            type: 'transcript',
            role: 'ai',
            text: data.part.transcript,
            final: true
          })
        }
        break
        
      case 'conversation.item.input_audio_transcription.completed':
        // 用户语音转写完成
        if (data.transcript) {
          this.handlers.onTranscript?.({
            type: 'transcript',
            role: 'user',
            text: data.transcript,
            final: true
          })
        }
        break
        
      case 'input_audio_buffer.speech_started':
        console.log('[OpenAIAdapter] 检测到用户开始说话')
        break
        
      case 'input_audio_buffer.speech_stopped':
        console.log('[OpenAIAdapter] 检测到用户停止说话')
        break
        
      case 'error':
        console.error('[OpenAIAdapter] 服务端错误:', data)
        this.handlers.onError?.(new Error(data.error?.message || '未知错误'))
        break
        
      default:
        console.log('[OpenAIAdapter] 收到消息:', data.type)
    }
  }
  
  /**
   * Base64 转 Uint8Array
   */
  private base64ToUint8Array(base64: string): Uint8Array {
    const binary = atob(base64)
    const len = binary.length
    const bytes = new Uint8Array(len)
    for (let i = 0; i < len; i++) {
      bytes[i] = binary.charCodeAt(i)
    }
    return bytes
  }
  
  /**
   * 发送音频缓冲区清空
   */
  private sendAudioBufferClear(): void {
    this.sendMessage({
      type: 'input_audio_buffer.clear'
    })
  }
  
  /**
   * 发送音频缓冲区提交
   */
  private sendAudioBufferCommit(): void {
    this.sendMessage({
      type: 'input_audio_buffer.commit'
    })
  }
  
  /**
   * 发送创建响应
   */
  private sendResponseCreate(): void {
    this.sendMessage({
      type: 'response.create'
    })
  }
  
  /**
   * 发送取消响应
   */
  private sendResponseCancel(): void {
    this.sendMessage({
      type: 'response.cancel'
    })
  }
  
  /**
   * 处理并发送音频块
   */
  private async processAndSendAudioChunk(blob: Blob): Promise<void> {
    if (!this.isRecording || !this.ws) return
    
    try {
      // 将 Blob 转换为 ArrayBuffer
      const arrayBuffer = await blob.arrayBuffer()
      
      // 转换为 PCM16
      const pcmData = await this.convertToPCM16(arrayBuffer, blob.type)
      if (pcmData) {
        this.sendAudioData(pcmData)
      }
    } catch (error) {
      console.error('[OpenAIAdapter] 处理音频块失败:', error)
    }
  }
  
  /**
   * 将音频数据转换为 PCM16 格式
   */
  private async convertToPCM16(arrayBuffer: ArrayBuffer, mimeType: string): Promise<Uint8Array | null> {
    try {
      // 如果已经是 PCM 格式，直接返回
      if (mimeType.includes('pcm') || mimeType.includes('wav')) {
        return new Uint8Array(arrayBuffer)
      }
      
      // 对于 WebM/Opus 等压缩格式，需要解码
      if (!this.audioContext) {
        this.audioContext = new (window.AudioContext || (window as any).webkitAudioContext)({
          sampleRate: this.config.sampleRate
        })
      }
      
      const audioBuffer = await this.audioContext.decodeAudioData(arrayBuffer)
      
      // 获取单声道数据
      const channelData = audioBuffer.getChannelData(0)
      
      // 转换为 PCM16
      const pcm16 = new Int16Array(channelData.length)
      for (let i = 0; i < channelData.length; i++) {
        const s = Math.max(-1, Math.min(1, channelData[i]))
        pcm16[i] = s < 0 ? s * 0x8000 : s * 0x7FFF
      }
      
      return new Uint8Array(pcm16.buffer)
    } catch (error) {
      console.error('[OpenAIAdapter] 音频转换失败:', error)
      return null
    }
  }
  
  /**
   * 发送音频数据
   */
  private sendAudioData(data: Uint8Array): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return
    
    // OpenAI 需要 base64 编码
    const base64 = btoa(String.fromCharCode.apply(null, Array.from(data)))
    this.sendMessage({
      type: 'input_audio_buffer.append',
      audio: base64
    })
  }
  
  /**
   * 播放音频块
   */
  private playAudioChunk(pcmData: Uint8Array): void {
    if (!this.playbackContext) {
      this.playbackContext = new (window.AudioContext || (window as any).webkitAudioContext)({
        sampleRate: this.config.sampleRate
      })
    }
    
    // 将 PCM16 转换为 Float32
    const int16Data = new Int16Array(pcmData.buffer)
    const float32Data = new Float32Array(int16Data.length)
    
    for (let i = 0; i < int16Data.length; i++) {
      float32Data[i] = int16Data[i] / 0x8000
    }
    
    // 创建 AudioBuffer
    const audioBuffer = this.playbackContext.createBuffer(
      1,
      float32Data.length,
      this.config.sampleRate
    )
    audioBuffer.getChannelData(0).set(float32Data)
    
    // 加入播放队列
    this.playbackQueue.push(audioBuffer)
    
    // 如果没有在播放，开始播放
    if (!this.isPlayingAudio) {
      this.playNextInQueue()
    }
  }
  
  /**
   * 播放下一个音频块
   */
  private playNextInQueue(): void {
    if (this.playbackQueue.length === 0 || !this.playbackContext) {
      this.isPlayingAudio = false
      this.handlers.onAudioEnd?.()
      return
    }
    
    this.isPlayingAudio = true
    this.handlers.onAudioStart?.()
    
    const audioBuffer = this.playbackQueue.shift()!
    const source = this.playbackContext.createBufferSource()
    source.buffer = audioBuffer
    source.connect(this.playbackContext.destination)
    
    source.onended = () => {
      this.playNextInQueue()
    }
    
    source.start()
    this.currentSource = source
  }
}