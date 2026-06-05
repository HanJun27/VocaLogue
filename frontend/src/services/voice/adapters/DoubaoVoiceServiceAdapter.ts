/**
 * 豆包 Realtime API 适配器
 * 实现豆包端到端实时语音大模型API的二进制协议
 */

import { 
  IVoiceService, 
  VoiceServiceConfig, 
  TranscriptEvent, 
  ConnectionTestResult,
  VoiceServiceEventHandlers 
} from './IVoiceService'
import type { Scenario } from '@/types'

/**
 * 豆包事件ID定义
 */
enum DoubaoEventType {
  // 连接类事件
  StartConnection = 1,
  FinishConnection = 2,
  
  // 会话类事件
  StartSession = 100,
  FinishSession = 101,
  
  // 对话类事件
  PushAudio = 200,
  FinishSpeaking = 201,
  ClientCancel = 202,
  
  // 服务器事件
  SessionStarted = 1000,
  SessionFinished = 1001,
  AudioGenerated = 2000,
  TranscriptGenerated = 2001,
  Error = 9999
}

/**
 * 豆包二进制协议消息类型
 */
enum DoubaoMessageType {
  FullClientRequest = 0b0001,      // 客户端发送文本事件
  FullServerResponse = 0b1001,     // 服务器返回文本事件
  AudioOnlyRequest = 0b0010,       // 客户端发送音频数据
  AudioOnlyResponse = 0b1011,      // 服务器返回音频数据
  ErrorInformation = 0b1111        // 服务器返回错误事件
}

/**
 * 豆包二进制协议辅助类
 */
class DoubaoBinaryProtocol {
  /**
   * 构建二进制消息
   */
  static buildMessage(
    messageType: DoubaoMessageType,
    eventId: number,
    payload: string | Uint8Array,
    sequence?: number,
    sessionId?: string
  ): Uint8Array {
    const isJson = typeof payload === 'string'
    const payloadBytes = isJson 
      ? new TextEncoder().encode(payload)
      : payload
    
    // 计算头部大小
    let headerSize = 4 // 基础头部4字节
    let optionalSize = 0
    
    // Sequence (4 bytes)
    if (sequence !== undefined) {
      optionalSize += 4
    }
    
    // Event ID (4 bytes)
    optionalSize += 4
    
    // Session ID (4 bytes size + variable length)
    if (sessionId) {
      const sessionIdBytes = new TextEncoder().encode(sessionId)
      optionalSize += 4 + sessionIdBytes.length
    }
    
    // Payload size (4 bytes)
    optionalSize += 4
    
    const totalSize = headerSize + optionalSize + payloadBytes.length
    const buffer = new Uint8Array(totalSize)
    let offset = 0
    
    // === Header (4 bytes) ===
    // Byte 0: Protocol Version (4 bits) + Header Size (4 bits)
    buffer[offset++] = 0b00010001 // v1, 4-byte header
    
    // Byte 1: Message Type (4 bits) + Flags (4 bits)
    let flags = 0b0100 // 携带事件ID
    if (sequence !== undefined) {
      flags |= 0b0001 // 携带sequence
    }
    if (sessionId) {
      flags |= 0b1000 // 携带session id
    }
    buffer[offset++] = (messageType << 4) | flags
    
    // Byte 2: Serialization (4 bits) + Compression (4 bits)
    const serialization = isJson ? 0b0001 : 0b0000 // JSON or Raw
    const compression = 0b0000 // 无压缩
    buffer[offset++] = (serialization << 4) | compression
    
    // Byte 3: Reserved
    buffer[offset++] = 0x00
    
    // === Optional ===
    // Sequence (4 bytes)
    if (sequence !== undefined) {
      const view = new DataView(buffer.buffer)
      view.setInt32(offset, sequence, true) // Little-endian
      offset += 4
    }
    
    // Event ID (4 bytes)
    const view = new DataView(buffer.buffer)
    view.setInt32(offset, eventId, true)
    offset += 4
    
    // Session ID
    if (sessionId) {
      const sessionIdBytes = new TextEncoder().encode(sessionId)
      // Session ID size (4 bytes)
      view.setInt32(offset, sessionIdBytes.length, true)
      offset += 4
      // Session ID
      buffer.set(sessionIdBytes, offset)
      offset += sessionIdBytes.length
    }
    
    // Payload size (4 bytes)
    view.setInt32(offset, payloadBytes.length, true)
    offset += 4
    
    // Payload
    buffer.set(payloadBytes, offset)
    
    return buffer
  }
  
  /**
   * 解析二进制消息
   */
  static parseMessage(buffer: Uint8Array): {
    messageType: DoubaoMessageType
    eventId?: number
    sequence?: number
    sessionId?: string
    payload: Uint8Array
  } | null {
    if (buffer.length < 4) return null
    
    let offset = 0
    
    // === Header (4 bytes) ===
    const byte0 = buffer[offset++]
    const protocolVersion = (byte0 >> 4) & 0x0F
    const headerSize = byte0 & 0x0F
    
    const byte1 = buffer[offset++]
    const messageType = (byte1 >> 4) & 0x0F as DoubaoMessageType
    const flags = byte1 & 0x0F
    
    const byte2 = buffer[offset++]
    const serialization = (byte2 >> 4) & 0x0F
    const compression = byte2 & 0x0F
    
    offset++ // Skip reserved byte
    
    // === Optional ===
    let sequence: number | undefined
    let eventId: number | undefined
    let sessionId: string | undefined
    
    // Sequence
    if (flags & 0b0001) {
      const view = new DataView(buffer.buffer)
      sequence = view.getInt32(offset, true)
      offset += 4
    }
    
    // Event ID
    if (flags & 0b0100) {
      const view = new DataView(buffer.buffer)
      eventId = view.getInt32(offset, true)
      offset += 4
    }
    
    // Session ID
    if (flags & 0b1000) {
      const view = new DataView(buffer.buffer)
      const sessionIdSize = view.getInt32(offset, true)
      offset += 4
      const sessionIdBytes = buffer.slice(offset, offset + sessionIdSize)
      sessionId = new TextDecoder().decode(sessionIdBytes)
      offset += sessionIdSize
    }
    
    // Payload size
    const view = new DataView(buffer.buffer)
    const payloadSize = view.getInt32(offset, true)
    offset += 4
    
    // Payload
    const payload = buffer.slice(offset, offset + payloadSize)
    
    return {
      messageType,
      eventId,
      sequence,
      sessionId,
      payload
    }
  }
}

/**
 * 豆包语音服务适配器
 */
export class DoubaoVoiceServiceAdapter implements IVoiceService {
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
  private sessionId: string | null = null
  private sequence = 0
  
  private config: VoiceServiceConfig
  private handlers: Partial<VoiceServiceEventHandlers> = {}
  
  // 豆包特定配置
  private readonly DOUBAO_WS_URL = 'wss://openspeech.bytedance.com/api/v3/realtime/dialogue'
  private readonly DOUBAO_RESOURCE_ID = 'volc.speech.dialog'
  private readonly DOUBAO_APP_KEY = 'PlgvMymc7f3tQnJ6'
  
  constructor(config: VoiceServiceConfig) {
    this.config = config
  }
  
  // === IVoiceService 实现 ===
  
  async connect(scenario: Scenario): Promise<void> {
    if (!this.config.apiKey) {
      throw new Error('请先配置 API Key')
    }
    
    if (!this.config.appId) {
      throw new Error('请先配置 App ID')
    }
    
    try {
      // 建立 WebSocket 连接
      await this.connectWebSocket()
      
      // 发送 StartConnection 事件
      await this.sendStartConnection()
      
      // 发送 StartSession 事件
      await this.sendStartSession(scenario)
      
      this.isConnected = true
      this.handlers.onConnected?.()
    } catch (error) {
      console.error('[DoubaoAdapter] 连接失败:', error)
      this.handlers.onError?.(error as Error)
      throw error
    }
  }
  
  disconnect(): void {
    this.stopRecording()
    this.stopAudioPlayback()
    
    // 发送 FinishSession 事件
    if (this.sessionId) {
      this.sendEvent(DoubaoEventType.FinishSession, {})
    }
    
    // 发送 FinishConnection 事件
    this.sendEvent(DoubaoEventType.FinishConnection, {})
    
    // 关闭 WebSocket
    if (this.ws) {
      this.ws.close()
      this.ws = null
    }
    
    this.isConnected = false
    this.sessionId = null
  }
  
  async startRecording(): Promise<void> {
    if (!this.isConnected) {
      throw new Error('请先连接到语音服务')
    }
    
    if (this.isRecording) {
      console.warn('[DoubaoAdapter] 已经在录音中')
      return
    }
    
    try {
      // 获取麦克风权限
      this.mediaStream = await navigator.mediaDevices.getUserMedia({
        audio: {
          sampleRate: this.config.sampleRate,
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true
        }
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
      
      // 每 20ms 发送一次音频数据（豆包推荐）
      this.mediaRecorder.start(20)
      
      this.isRecording = true
      this.handlers.onRecordingStateChange?.(true)
      
    } catch (error) {
      console.error('[DoubaoAdapter] 获取麦克风权限失败:', error)
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
    
    // 发送 FinishSpeaking 事件
    this.sendEvent(DoubaoEventType.FinishSpeaking, {})
  }
  
  async interrupt(): Promise<void> {
    // 停止录音
    await this.stopRecording()
    
    // 停止音频播放
    this.stopAudioPlayback()
    
    // 发送 ClientCancel 事件
    this.sendEvent(DoubaoEventType.ClientCancel, {})
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
    
    try {
      // 测试豆包健康检查接口
      const response = await fetch('https://openspeech.bytedance.com/api/v1/health', {
        method: 'GET',
        headers: {
          'Authorization': `Bearer ${this.config.apiKey}`
        }
      })
      
      const latency = Date.now() - startTime
      
      if (response.ok) {
        return {
          success: true,
          message: '豆包 API 连接正常',
          latency
        }
      } else {
        return {
          success: false,
          message: '豆包 API 连接失败',
          latency
        }
      }
    } catch (error) {
      return {
        success: false,
        message: '网络连接失败',
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
      maxAudioChunkSize: 640, // 20ms @ 16kHz PCM16
      recommendedSampleRate: 16000
    }
  }
  
  // === 私有方法 ===
  
  /**
   * 建立 WebSocket 连接
   */
  private async connectWebSocket(): Promise<void> {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(this.DOUBAO_WS_URL)
      
      // 设置请求头（需要在连接时通过其他方式传递）
      // 注意：WebSocket API 不支持自定义 headers，豆包可能需要通过 query parameter 或其他方式
      
      this.ws.binaryType = 'arraybuffer'
      
      this.ws.onopen = () => {
        console.log('[DoubaoAdapter] WebSocket 已连接')
        resolve()
      }
      
      this.ws.onmessage = (event) => {
        this.handleMessage(event.data)
      }
      
      this.ws.onerror = (error) => {
        console.error('[DoubaoAdapter] WebSocket 错误:', error)
        reject(new Error('WebSocket 连接错误'))
      }
      
      this.ws.onclose = (event) => {
        console.log('[DoubaoAdapter] WebSocket 已关闭:', event.code, event.reason)
        this.isConnected = false
        this.handlers.onDisconnected?.(
          event.code !== 1000 ? new Error(`连接关闭: ${event.reason}`) : undefined
        )
      }
    })
  }
  
  /**
   * 发送 StartConnection 事件
   */
  private async sendStartConnection(): Promise<void> {
    this.sendEvent(DoubaoEventType.StartConnection, {})
  }
  
  /**
   * 发送 StartSession 事件
   */
  private async sendStartSession(scenario: Scenario): Promise<void> {
    this.sessionId = this.generateSessionId()
    
    const sessionConfig = {
      asr: {
        audio_info: {
          format: 'speech_opus',
          sample_rate: this.config.sampleRate,
          channel: 1
        }
      },
      tts: {
        audio_config: {
          channel: 1,
          format: 'pcm_s16le',
          sample_rate: 24000
        },
        speaker: 'zh_female_vv_jupiter_bigtts' // 默认音色
      },
      dialog: {
        bot_name: '豆包',
        system_role: scenario.systemPrompt,
        extra: {
          input_mod: 'push_to_talk' // 按键说话模式
        }
      }
    }
    
    this.sendEvent(DoubaoEventType.StartSession, sessionConfig, this.sessionId)
  }
  
  /**
   * 发送事件
   */
  private sendEvent(eventId: number, payload: Record<string, any>, sessionId?: string): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      console.warn('[DoubaoAdapter] WebSocket 未连接，无法发送事件')
      return
    }
    
    const sequence = this.sequence++
    const jsonPayload = JSON.stringify(payload)
    
    const message = DoubaoBinaryProtocol.buildMessage(
      DoubaoMessageType.FullClientRequest,
      eventId,
      jsonPayload,
      sequence,
      sessionId
    )
    
    this.ws.send(message)
    console.log(`[DoubaoAdapter] 发送事件: ${eventId}, sequence: ${sequence}`)
  }
  
  /**
   * 处理接收到的消息
   */
  private handleMessage(data: ArrayBuffer): void {
    const buffer = new Uint8Array(data)
    const parsed = DoubaoBinaryProtocol.parseMessage(buffer)
    
    if (!parsed) {
      console.error('[DoubaoAdapter] 无法解析消息')
      return
    }
    
    const { messageType, eventId, payload } = parsed
    
    console.log(`[DoubaoAdapter] 收到消息: messageType=${messageType}, eventId=${eventId}`)
    
    switch (messageType) {
      case DoubaoMessageType.FullServerResponse:
        this.handleServerEvent(eventId, payload)
        break
        
      case DoubaoMessageType.AudioOnlyResponse:
        this.handleAudioResponse(payload)
        break
        
      case DoubaoMessageType.ErrorInformation:
        this.handleError(payload)
        break
        
      default:
        console.log('[DoubaoAdapter] 未处理的消息类型:', messageType)
    }
  }
  
  /**
   * 处理服务器事件
   */
  private handleServerEvent(eventId: number | undefined, payload: Uint8Array): void {
    if (!eventId) return
    
    const json = new TextDecoder().decode(payload)
    const data = JSON.parse(json)
    
    switch (eventId) {
      case DoubaoEventType.SessionStarted:
        console.log('[DoubaoAdapter] 会话已启动')
        break
        
      case DoubaoEventType.SessionFinished:
        console.log('[DoubaoAdapter] 会话已结束')
        break
        
      case DoubaoEventType.TranscriptGenerated:
        this.handleTranscript(data)
        break
        
      case DoubaoEventType.Error:
        this.handleError(payload)
        break
        
      default:
        console.log('[DoubaoAdapter] 未处理的事件:', eventId, data)
    }
  }
  
  /**
   * 处理音频响应
   */
  private handleAudioResponse(payload: Uint8Array): void {
    this.handlers.onAudioChunk?.(payload)
    this.playAudioChunk(payload)
  }
  
  /**
   * 处理转写结果
   */
  private handleTranscript(data: any): void {
    const event: TranscriptEvent = {
      type: 'transcript',
      role: data.role || 'ai',
      text: data.text || '',
      final: data.is_final || false
    }
    
    this.handlers.onTranscript?.(event)
  }
  
  /**
   * 处理错误
   */
  private handleError(payload: Uint8Array): void {
    const json = new TextDecoder().decode(payload)
    const data = JSON.parse(json)
    const error = new Error(data.error || '未知错误')
    
    console.error('[DoubaoAdapter] 服务器错误:', error)
    this.handlers.onError?.(error)
  }
  
  /**
   * 处理并发送音频块
   */
  private async processAndSendAudioChunk(blob: Blob): Promise<void> {
    if (!this.isRecording || !this.ws) return
    
    try {
      // 将 Blob 转换为 ArrayBuffer
      const arrayBuffer = await blob.arrayBuffer()
      
      // 发送音频数据
      this.sendAudioData(new Uint8Array(arrayBuffer))
    } catch (error) {
      console.error('[DoubaoAdapter] 处理音频块失败:', error)
    }
  }
  
  /**
   * 发送音频数据
   */
  private sendAudioData(data: Uint8Array): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return
    
    const sequence = this.sequence++
    
    const message = DoubaoBinaryProtocol.buildMessage(
      DoubaoMessageType.AudioOnlyRequest,
      DoubaoEventType.PushAudio,
      data,
      sequence,
      this.sessionId || undefined
    )
    
    this.ws.send(message)
  }
  
  /**
   * 播放音频块
   */
  private playAudioChunk(pcmData: Uint8Array): void {
    if (!this.playbackContext) {
      this.playbackContext = new (window.AudioContext || (window as any).webkitAudioContext)({
        sampleRate: 24000 // 豆包默认使用 24kHz
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
      24000
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
  
  /**
   * 生成会话ID
   */
  private generateSessionId(): string {
    return `session_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`
  }
}