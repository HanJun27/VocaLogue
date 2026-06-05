import { configService } from '@/config'
import type { Scenario } from '@/types'
import { 
  getBrowserCapabilities, 
  getRecommendedMimeType, 
  canUseRealtimeVoice,
  type BrowserCapabilities 
} from '@/utils/browserCompat'

export interface TranscriptEvent {
  type: 'transcript'
  role: 'user' | 'ai'
  text: string
  translation?: string
  final: boolean
}

export interface AudioEvent {
  type: 'audio'
  data: Uint8Array
}

export interface VoiceServiceEvents {
  onTranscript: (event: TranscriptEvent) => void
  onAudioStart: () => void
  onAudioChunk: (chunk: Uint8Array) => void
  onAudioEnd: () => void
  onConnected: () => void
  onDisconnected: (error?: Error) => void
  onError: (error: Error) => void
  onRecordingStateChange: (isRecording: boolean) => void
  onInterimTranscript: (text: string) => void
}

export class VoiceService {
  private ws: WebSocket | null = null
  private mediaStream: MediaStream | null = null
  private audioContext: AudioContext | null = null
  private scriptProcessor: ScriptProcessorNode | null = null
  private mediaRecorder: MediaRecorder | null = null
  private isRecording = false
  private isConnected = false
  private silenceTimer: number | null = null
  // private audioChunks: Uint8Array[] = []
  private sampleRate = 16000
  private events: Partial<VoiceServiceEvents> = {}
  private browserCapabilities: BrowserCapabilities | null = null
  private preferredMimeType: string | null = null
  
  // 音频播放相关
  private playbackContext: AudioContext | null = null
  private playbackQueue: AudioBuffer[] = []
  private isPlayingAudio = false
  private currentSource: AudioBufferSourceNode | null = null

  constructor() {
    // 延迟检测浏览器能力
    this.initBrowserCapabilities()
  }

  private initBrowserCapabilities(): void {
    try {
      this.browserCapabilities = getBrowserCapabilities()
      this.preferredMimeType = getRecommendedMimeType(this.browserCapabilities)
      console.log('[VoiceService] 浏览器能力检测:', this.browserCapabilities)
    } catch (error) {
      console.error('[VoiceService] 浏览器能力检测失败:', error)
    }
  }

  on(event: keyof VoiceServiceEvents, callback: any) {
    this.events[event] = callback
  }

  /**
   * 获取浏览器能力信息
   */
  getCapabilities(): BrowserCapabilities | null {
    return this.browserCapabilities
  }

  /**
   * 检查是否可以使用语音功能
   */
  canUseVoice(): boolean {
    return this.browserCapabilities ? canUseRealtimeVoice(this.browserCapabilities) : false
  }

  async connect(scenario: Scenario): Promise<void> {
    const config = configService.getConfig()
    if (!config.apiKey) {
      throw new Error('请先在设置中配置 API Key')
    }

    this.sampleRate = config.sampleRate

    try {
      // 根据模型提供商选择连接方式
      if (config.modelProvider === 'doubao') {
        await this.connectToDoubao(scenario, config.apiKey)
      } else {
        await this.connectToOpenAI(scenario)
      }
      this.isConnected = true
      this.events.onConnected?.()
    } catch (error) {
      console.error('[VoiceService] 连接语音大模型失败:', error)
      this.events.onError?.(error as Error)
      throw error
    }
  }

  private async connectToDoubao(scenario: Scenario, apiKey: string): Promise<void> {
    // 豆包 Realtime API 连接
    const url = `wss://openspeech.bytedance.com/api/v2/voice_chat/realtime?access-token=${apiKey}`
    
    this.ws = new WebSocket(url)
    this.setupWebSocketHandlers(scenario)
  }

  private async connectToOpenAI(scenario: Scenario): Promise<void> {
    // OpenAI Realtime API 连接
    const url = 'wss://api.openai.com/v1/realtime?model=gpt-4o-realtime-preview-2024-10-01'
    
    this.ws = new WebSocket(url)
    this.ws.binaryType = 'arraybuffer'
    this.setupWebSocketHandlers(scenario)
    
    // OpenAI 需要发送认证头（通过 query parameter 或在连接后发送）
    // 这里先建立连接，在 onopen 中发送配置
  }

  private setupWebSocketHandlers(scenario: Scenario): void {
    if (!this.ws) return

    this.ws.onopen = () => {
      console.log('[VoiceService] WebSocket 已连接')
      
      // 发送初始配置
      this.sendInitialConfig(scenario)
    }

    this.ws.onmessage = (event) => {
      this.handleWebSocketMessage(event)
    }

    this.ws.onerror = (error) => {
      console.error('[VoiceService] WebSocket 错误:', error)
      this.events.onError?.(new Error('WebSocket 连接错误'))
    }

    this.ws.onclose = (event) => {
      console.log('[VoiceService] WebSocket 已关闭:', event.code, event.reason)
      this.isConnected = false
      this.events.onDisconnected?.(
        event.code !== 1000 ? new Error(`连接关闭: ${event.reason}`) : undefined
      )
    }
  }

  private sendInitialConfig(scenario: Scenario): void {
    const config = configService.getConfig()
    
    if (config.modelProvider === 'gpt-4o') {
      // OpenAI Realtime API 配置
      const sessionConfig = {
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
      
      this.ws?.send(JSON.stringify(sessionConfig))
      
      // 发送欢迎消息
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
      this.ws?.send(JSON.stringify(welcomeMsg))
    } else {
      // 豆包配置（根据实际 API 文档调整）
      const sessionConfig = {
        type: 'session.update',
        session: {
          system_prompt: scenario.systemPrompt,
          welcome_message: scenario.welcomeMessage,
          audio_format: 'pcm16',
          sample_rate: this.sampleRate
        }
      }
      this.ws?.send(JSON.stringify(sessionConfig))
    }
  }

  private handleWebSocketMessage(event: MessageEvent): void {
    try {
      // 处理二进制音频数据
      if (event.data instanceof ArrayBuffer) {
        const audioData = new Uint8Array(event.data)
        this.events.onAudioChunk?.(audioData)
        this.playAudioChunk(audioData)
        return
      }
      
      // 处理文本消息
      const data = typeof event.data === 'string' 
        ? JSON.parse(event.data) 
        : event.data

      const config = configService.getConfig()
      
      if (config.modelProvider === 'doubao') {
        this.handleDoubaoMessage(data)
      } else {
        this.handleOpenAIMessage(data)
      }
    } catch (error) {
      console.error('[VoiceService] 解析消息失败:', error)
    }
  }

  private handleDoubaoMessage(data: any): void {
    // 处理豆包 API 的消息
    console.log('[VoiceService] 收到豆包消息:', data)
    
    // 根据豆包实际 API 响应格式调整
    if (data.type === 'transcript' && data.text) {
      this.events.onTranscript?.({
        type: 'transcript',
        role: data.role || 'ai',
        text: data.text,
        translation: data.translation,
        final: data.is_final || false
      })
    }
    
    if (data.type === 'audio' && data.data) {
      const audioData = this.base64ToUint8Array(data.data)
      this.events.onAudioChunk?.(audioData)
      this.playAudioChunk(audioData)
    }
  }

  private handleOpenAIMessage(data: any): void {
    // 处理 OpenAI Realtime API 的消息
    switch (data.type) {
      case 'session.created':
        console.log('[VoiceService] OpenAI 会话已创建')
        break
        
      case 'session.updated':
        console.log('[VoiceService] OpenAI 会话配置已更新')
        break
        
      case 'response.audio.delta':
        // 音频流数据
        if (data.delta) {
          const audioData = this.base64ToUint8Array(data.delta)
          this.events.onAudioChunk?.(audioData)
          this.playAudioChunk(audioData)
        }
        break
        
      case 'response.audio_transcript.delta':
        // AI 文本转写流
        this.events.onTranscript?.({
          type: 'transcript',
          role: 'ai',
          text: data.delta || '',
          final: false
        })
        break
        
      case 'response.audio.done':
        this.events.onAudioEnd?.()
        this.flushAudioQueue()
        break
        
      case 'response.content_part.done':
        // 内容完成
        if (data.part?.transcript) {
          this.events.onTranscript?.({
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
          this.events.onTranscript?.({
            type: 'transcript',
            role: 'user',
            text: data.transcript,
            final: true
          })
        }
        break
        
      case 'input_audio_buffer.speech_started':
        // 检测到用户开始说话
        console.log('[VoiceService] 检测到用户开始说话')
        break
        
      case 'input_audio_buffer.speech_stopped':
        // 检测到用户停止说话
        console.log('[VoiceService] 检测到用户停止说话')
        break
        
      case 'error':
        console.error('[VoiceService] 服务端错误:', data)
        this.events.onError?.(new Error(data.error?.message || '未知错误'))
        break
        
      default:
        console.log('[VoiceService] 收到消息:', data.type, data)
    }
  }

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
   * 开始录音 - 使用 MediaRecorder（兼容所有浏览器）
   */
  async startRecording(): Promise<void> {
    if (!this.isConnected) {
      throw new Error('请先连接语音大模型')
    }

    if (this.isRecording) {
      console.warn('[VoiceService] 已经在录音中')
      return
    }

    try {
      // 获取麦克风权限
      this.mediaStream = await navigator.mediaDevices.getUserMedia({
        audio: {
          sampleRate: this.sampleRate,
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true
        }
      })

      // 使用 MediaRecorder 录制音频（兼容 Firefox）
      await this.startMediaRecorderRecording()
      
      this.isRecording = true
      this.events.onRecordingStateChange?.(true)
      
      // 发送开始录音的信号
      this.sendAudioStart()
      
    } catch (error) {
      console.error('[VoiceService] 获取麦克风权限失败:', error)
      throw new Error('无法获取麦克风权限，请检查浏览器设置')
    }
  }

  /**
   * 使用 MediaRecorder 录制音频（推荐，兼容 Firefox）
   */
  private async startMediaRecorderRecording(): Promise<void> {
    if (!this.mediaStream) {
      throw new Error('媒体流未初始化')
    }

    // 确定使用的 MIME 类型
    const mimeType = this.preferredMimeType || 'audio/webm'
    
    try {
      this.mediaRecorder = new MediaRecorder(this.mediaStream, {
        mimeType,
        audioBitsPerSecond: 128000
      })
    } catch (e) {
      // 如果指定的 MIME 类型不支持，使用默认
      console.warn('[VoiceService] MIME 类型不支持，使用默认配置:', mimeType)
      this.mediaRecorder = new MediaRecorder(this.mediaStream)
    }

    this.mediaRecorder.ondataavailable = async (event) => {
      if (event.data.size > 0) {
        await this.processAndSendAudioChunk(event.data)
      }
    }

    this.mediaRecorder.onerror = (event) => {
      console.error('[VoiceService] MediaRecorder 错误:', event)
      this.events.onError?.(new Error('录音过程中发生错误'))
    }

    // 每 100ms 发送一次音频数据，实现低延迟流式传输
    this.mediaRecorder.start(100)
    console.log('[VoiceService] MediaRecorder 已启动，MIME:', this.mediaRecorder.mimeType)
  }

  /**
   * 处理并发送音频块
   */
  private async processAndSendAudioChunk(blob: Blob): Promise<void> {
    if (!this.isRecording || !this.ws) return

    try {
      // 将 Blob 转换为 ArrayBuffer
      const arrayBuffer = await blob.arrayBuffer()
      
      // 如果需要，可以在这里进行音频格式转换
      // 对于 OpenAI Realtime API，需要 PCM16 格式
      // MediaRecorder 输出的是 WebM/Opus，需要解码转换
      
      // 方案1：直接发送原始数据（如果后端支持）
      // this.ws.send(arrayBuffer)
      
      // 方案2：转换为 PCM16 后发送（OpenAI 需要）
      const pcmData = await this.convertToPCM16(arrayBuffer, blob.type)
      if (pcmData) {
        this.sendAudioData(pcmData)
      }
    } catch (error) {
      console.error('[VoiceService] 处理音频块失败:', error)
    }
  }

  /**
   * 将音频数据转换为 PCM16 格式
   * 注意：这是一个简化版本，实际可能需要使用 AudioContext 进行解码
   */
  private async convertToPCM16(arrayBuffer: ArrayBuffer, mimeType: string): Promise<Uint8Array | null> {
    try {
      // 如果已经是 PCM 格式，直接返回
      if (mimeType.includes('pcm') || mimeType.includes('wav')) {
        return new Uint8Array(arrayBuffer)
      }

      // 对于 WebM/Opus 等压缩格式，需要解码
      // 这里使用 AudioContext 进行解码
      if (!this.audioContext) {
        this.audioContext = new (window.AudioContext || (window as any).webkitAudioContext)({
          sampleRate: this.sampleRate
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
      console.error('[VoiceService] 音频转换失败:', error)
      return null
    }
  }

  /**
   * 使用 ScriptProcessor 录制音频（备用方案，已弃用但仍可用）
   */
  // private startScriptProcessorRecording(): void {
  //   if (!this.mediaStream) return

  //   this.audioContext = new (window.AudioContext || (window as any).webkitAudioContext)({
  //     sampleRate: this.sampleRate
  //   })

  //   const source = this.audioContext.createMediaStreamSource(this.mediaStream)
  //   this.scriptProcessor = this.audioContext.createScriptProcessor(4096, 1, 1)
    
  //   this.scriptProcessor.onaudioprocess = (e) => {
  //     if (!this.isRecording) return

      // const inputData = e.inputBuffer.getChannelData(0)
      // const pcmData = this.floatTo16BitPCM(inputData)
      
      // 发送音频数据
      // this.sendAudioData(pcmData)
      
      // 静音检测（可选）
      // this.detectSilence(inputData)
      // }

      // source.connect(this.scriptProcessor)
      // this.scriptProcessor.connect(this.audioContext.destination)
      // }

  // private floatTo16BitPCM(input: Float32Array): Uint8Array {
  //   const output = new Int16Array(input.length)
  //   for (let i = 0; i < input.length; i++) {
  //     const s = Math.max(-1, Math.min(1, input[i]))
  //     output[i] = s < 0 ? s * 0x8000 : s * 0x7FFF
  //   }
  //   return new Uint8Array(output.buffer)
  // }

  private sendAudioStart(): void {
    const config = configService.getConfig()
    if (config.modelProvider === 'gpt-4o') {
      this.ws?.send(JSON.stringify({
        type: 'input_audio_buffer.clear'
      }))
    }
  }

  private sendAudioData(data: Uint8Array): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return

    const config = configService.getConfig()
    if (config.modelProvider === 'gpt-4o') {
      // OpenAI 需要 base64 编码
      const base64 = btoa(String.fromCharCode.apply(null, Array.from(data)))
      this.ws.send(JSON.stringify({
        type: 'input_audio_buffer.append',
        audio: base64
      }))
    } else {
      // 豆包可能使用二进制格式
      this.ws.send(data)
    }
  }

  // private detectSilence(inputData: Float32Array): void {
  //   const config = configService.getConfig()
  //   if (!config.vadEnabled) return

  //   // 计算音量
  //   let sum = 0
  //   for (let i = 0; i < inputData.length; i++) {
  //     sum += inputData[i] * inputData[i]
  //   }
  //   const rms = Math.sqrt(sum / inputData.length)
    
  //   // 如果音量低于阈值，开始静音计时
  //   if (rms < config.vadThreshold) {
  //     if (!this.silenceTimer) {
  //       this.silenceTimer = window.setTimeout(() => {
  //         this.stopRecording()
  //       }, config.vadSilenceDuration)
  //     }
  //   } else {
  //     // 有声音，重置静音定时器
  //     if (this.silenceTimer) {
  //       clearTimeout(this.silenceTimer)
  //       this.silenceTimer = null
  //     }
  //   }
  // }

  /**
   * 停止录音
   */
  async stopRecording(): Promise<void> {
    if (!this.isRecording) return
    
    this.isRecording = false
    this.events.onRecordingStateChange?.(false)
    
    if (this.silenceTimer) {
      clearTimeout(this.silenceTimer)
      this.silenceTimer = null
    }

    // 停止 MediaRecorder
    if (this.mediaRecorder && this.mediaRecorder.state !== 'inactive') {
      this.mediaRecorder.stop()
      this.mediaRecorder = null
    }

    // 停止 ScriptProcessor（如果使用）
    if (this.scriptProcessor) {
      this.scriptProcessor.disconnect()
      this.scriptProcessor = null
    }

    // 关闭媒体流
    if (this.mediaStream) {
      this.mediaStream.getTracks().forEach(track => track.stop())
      this.mediaStream = null
    }

    // 关闭音频上下文
    if (this.audioContext) {
      await this.audioContext.close()
      this.audioContext = null
    }

    // 发送结束信号
    this.sendAudioEnd()
  }

  private sendAudioEnd(): void {
    const config = configService.getConfig()
    if (config.modelProvider === 'gpt-4o') {
      this.ws?.send(JSON.stringify({
        type: 'input_audio_buffer.commit'
      }))
      this.ws?.send(JSON.stringify({
        type: 'response.create'
      }))
    }
  }

  /**
   * 打断当前播放
   */
  async interrupt(): Promise<void> {
    // 停止录音
    await this.stopRecording()
    
    // 停止音频播放
    this.stopAudioPlayback()
    
    // 发送打断信号
    const config = configService.getConfig()
    if (config.modelProvider === 'gpt-4o') {
      this.ws?.send(JSON.stringify({
        type: 'response.cancel'
      }))
    }
  }

  /**
   * 播放音频块
   */
  private playAudioChunk(pcmData: Uint8Array): void {
    if (!this.playbackContext) {
      this.playbackContext = new (window.AudioContext || (window as any).webkitAudioContext)({
        sampleRate: this.sampleRate
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
      this.sampleRate
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
      this.events.onAudioEnd?.()
      return
    }

    this.isPlayingAudio = true
    this.events.onAudioStart?.()

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
   * 刷新音频队列（立即播放所有缓冲的音频）
   */
  private flushAudioQueue(): void {
    // 队列会自动播放，这里只是确保开始播放
    if (!this.isPlayingAudio && this.playbackQueue.length > 0) {
      this.playNextInQueue()
    }
  }

  /**
   * 停止音频播放
   */
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

  /**
   * 断开连接
   */
  disconnect(): void {
    this.stopRecording()
    this.stopAudioPlayback()
    
    if (this.playbackContext) {
      this.playbackContext.close()
      this.playbackContext = null
    }
    
    if (this.ws) {
      this.ws.close()
      this.ws = null
    }
    
    this.isConnected = false
  }

  /**
   * 测试 API Key 的连通性
   */
  async testConnection(): Promise<{ success: boolean; message: string; latency?: number }> {
    const config = configService.getConfig()
    if (!config.apiKey) {
      return { success: false, message: '请先输入 API Key' }
    }

    const startTime = Date.now()

    try {
      if (config.modelProvider === 'gpt-4o') {
        return await this.testOpenAIConnection(config.apiKey, startTime)
      } else {
        return await this.testDoubaoConnection(config.apiKey, startTime)
      }
    } catch (error) {
      console.error('[VoiceService] 测试连接失败:', error)
      return {
        success: false,
        message: error instanceof Error ? error.message : '未知错误',
        latency: Date.now() - startTime
      }
    }
  }

  private async testOpenAIConnection(apiKey: string, startTime: number): Promise<{ success: boolean; message: string; latency?: number }> {
    try {
      const response = await fetch('https://api.openai.com/v1/models', {
        headers: {
          'Authorization': `Bearer ${apiKey}`
        }
      })

      const latency = Date.now() - startTime

      if (response.ok) {
        return {
          success: true,
          message: 'OpenAI API Key 有效',
          latency
        }
      } else {
        const errorData = await response.json().catch(() => null)
        return {
          success: false,
          message: errorData?.error?.message || 'API Key 无效',
          latency
        }
      }
    } catch (error) {
      return {
        success: false,
        message: '网络连接失败，请检查网络',
        latency: Date.now() - startTime
      }
    }
  }

  private async testDoubaoConnection(apiKey: string, startTime: number): Promise<{ success: boolean; message: string; latency?: number }> {
    // 豆包 API 测试（需要根据实际 API 调整）
    try {
      // 这里使用一个模拟的测试请求
      // 实际使用时需要替换为豆包官方的健康检查接口
      const response = await fetch('https://openspeech.bytedance.com/api/v1/health', {
        method: 'GET',
        headers: {
          'Authorization': `Bearer ${apiKey}`
        }
      })

      const latency = Date.now() - startTime

      if (response.ok) {
        return {
          success: true,
          message: '豆包 API Key 有效',
          latency
        }
      } else {
        return {
          success: false,
          message: 'API Key 无效或已过期',
          latency
        }
      }
    } catch (error) {
      return {
        success: false,
        message: '网络连接失败，请检查网络',
        latency: Date.now() - startTime
      }
    }
  }
}

// 导出单例
export const voiceService = new VoiceService()