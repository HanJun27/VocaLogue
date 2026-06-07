/**
 * 管线 WebSocket 适配器
 *
 * 连接到后端 /api/pipeline/realtime WebSocket 端点，
 * 实现 ASR→LLM→TTS 管线的实时双向通信，支持打断。
 *
 * 消息协议：
 *   前端 → 后端:
 *     { "type": "start_session", "sessionId": "...", "agentName": "Ava", "useTts": true, ... }
 *     { "type": "user_input", "text": "..." }
 *     { "type": "interrupt" }
 *     { "type": "end_session" }
 *
 *   后端 → 前端:
 *     { "type": "llm_token", "content": "..." }
 *     { "type": "llm_done", "fullText": "..." }
 *     { "type": "tts_start" | "tts_done" }
 *     [binary: TTS audio chunk]
 *     { "type": "interrupted" | "state_change" | "error" }
 */

import type { IVoiceService, VoiceServiceConfig, ConnectionTestResult, VoiceServiceEventHandlers } from '../IVoiceService'
import type { Scenario } from '@/types'

export class PipelineWebSocketAdapter implements IVoiceService {
  private ws: WebSocket | null = null
  private config: VoiceServiceConfig
  private handlers: Partial<VoiceServiceEventHandlers> = {}
  private isConnected = false
  private isRecording = false
  private pendingResolve: (() => void) | null = null

  // 音频播放 — 使用 Blob URL + Audio 元素（比 AudioContext 更可靠，兼容 WAV/MP3）
  private playbackQueue: Blob[] = []
  private isPlayingAudio = false
  private currentAudio: HTMLAudioElement | null = null
  /** 打断后丢弃后续 TTS 音频，直到下一轮 start_session 或超时（2s）后自动清除 */
  private discardAudio = false

  constructor(config: VoiceServiceConfig) {
    this.config = config
  }

  get wsUrl(): string {
    const baseUrl = this.config.backendUrl || 'http://localhost:8080'
    return baseUrl.replace(/^http/, 'ws') + '/api/pipeline/realtime'
  }

  // ==================== IVoiceService 实现 ====================

  async connect(scenario: Scenario): Promise<void> {
    if (this.isConnected) {
      console.warn('[PipelineWS] 已连接，跳过')
      return
    }

    return new Promise((resolve, reject) => {
      try {
        const url = this.wsUrl
        console.log('[PipelineWS] 连接中:', url)

        this.ws = new WebSocket(url)
        this.ws.onopen = () => {
          console.log('[PipelineWS] WebSocket 已连接')
          this.isConnected = true

          // 从 modelConfig 读取 LLM 配置（在 connectPipelineWebSocket 中传入）
          const mc = this.config.modelConfig || {}
          const llmEngine = (mc.llmEngine as string) || 'openai'
          const llmModel = (mc.llmModel as string) || 'gpt-4o'
          const llmApiKey = (mc.llmApiKey as string) || ''
          const llmBaseUrl = (mc.llmBaseUrl as string) || ''

          // 启动会话
          this.sendMessage({
            type: 'start_session',
            sessionId: this.generateSessionId(),
            agentName: scenario.title || 'Ava',
            useTts: true,
            llmEngine,
            llmModel,
            llmApiKey,
            llmBaseUrl,
            ttsEngine: 'piper',
            ttsVoice: 'en_US-amy-medium'
          })

          this.handlers.onConnected?.()
          resolve()
        }

        this.ws.onmessage = (event) => {
          this.handleMessage(event)
        }

        this.ws.onerror = (error) => {
          console.error('[PipelineWS] 连接错误:', error)
          this.handlers.onError?.(new Error('WebSocket 连接错误'))
          if (!this.isConnected) {
            reject(new Error('WebSocket 连接失败'))
          }
        }

        this.ws.onclose = (event) => {
          console.log('[PipelineWS] 连接已关闭:', event.code, event.reason)
          this.isConnected = false
          if (event.code !== 1000) {
            this.handlers.onDisconnected?.(new Error(`连接关闭: ${event.reason}`))
          }
        }

        // 连接超时
        setTimeout(() => {
          if (!this.isConnected) {
            reject(new Error('WebSocket 连接超时'))
          }
        }, 10000)
      } catch (err) {
        reject(err)
      }
    })
  }

  disconnect(): void {
    this.stopAudioPlayback()
    if (this.ws) {
      this.sendMessage({ type: 'end_session' })
      this.ws.close()
      this.ws = null
    }
    this.isConnected = false
    this.isRecording = false
  }

  async startRecording(): Promise<void> {
    // 在 Pipeline 模式下，录音由外部管理
    // 用户说完后通过 sendUserInput() 发送文本
    this.isRecording = true
    this.handlers.onRecordingStateChange?.(true)
  }

  async stopRecording(): Promise<void> {
    this.isRecording = false
    this.handlers.onRecordingStateChange?.(false)
  }

  async interrupt(): Promise<void> {
    console.log('[PipelineWS] 发送打断信号')
    this.stopAudioPlayback()
    // 标记丢弃后续 TTS 音频（2s 后自动清除，覆盖 WS 传输延迟）
    this.discardAudio = true
    setTimeout(() => { this.discardAudio = false }, 2000)
    this.sendMessage({ type: 'interrupt' })
  }

  stopAudioPlayback(): void {
    if (this.currentAudio) {
      try {
        this.currentAudio.pause()
        this.currentAudio.src = ''
      } catch (_) {}
      this.currentAudio = null
    }
    this.playbackQueue = []
    this.isPlayingAudio = false
  }

  async testConnection(): Promise<ConnectionTestResult> {
    const startTime = Date.now()
    try {
      const ws = new WebSocket(this.wsUrl)
      return await new Promise((resolve) => {
        const timeout = setTimeout(() => {
          ws.close()
          resolve({ success: false, message: '连接超时', latency: Date.now() - startTime })
        }, 10000)

        ws.onopen = () => {
          clearTimeout(timeout)
          ws.close()
          resolve({ success: true, message: '管线 WebSocket 连接正常', latency: Date.now() - startTime })
        }
        ws.onerror = () => {
          clearTimeout(timeout)
          resolve({ success: false, message: 'WebSocket 连接失败', latency: Date.now() - startTime })
        }
      })
    } catch (err: any) {
      return { success: false, message: err.message, latency: Date.now() - startTime }
    }
  }

  on(event: string, handler: (...args: any[]) => void): void {
    // Support both 'transcript' and 'onTranscript' style event names
    const key = event.startsWith('on') ? event : 'on' + event.charAt(0).toUpperCase() + event.slice(1)
    ;(this.handlers as any)[key] = handler
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
      maxAudioChunkSize: 999999,
      recommendedSampleRate: 16000
    }
  }

  // ==================== 公开方法（给 App.vue 调用） ====================

  /**
   * 发送用户输入文本到管线（支持对话历史）
   * @param text 用户输入的文本
   * @param history 可选：对话历史消息列表
   */
  sendUserInput(text: string, history?: Array<{role: string; content: string}>): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      console.warn('[PipelineWS] 未连接，无法发送')
      return
    }
    console.log('[PipelineWS] 发送用户输入:', text.substring(0, 50), 'history:', history?.length || 0)
    this.sendMessage({ type: 'user_input', text, history: history || [] })
  }

  /**
   * 发送 ASR 中间结果到管线（触发推测性生成）
   * @param interimText ASR 当前的中间识别文本
   */
  sendAsrInterim(interimText: string): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return
    if (!interimText || interimText.trim().length < 5) return
    this.sendMessage({ type: 'asr_interim', text: interimText.trim() })
  }

  /**
   * 发送音频块（二进制）到后端 ASR
   * 后端会积累这些块，收到 asr_end 后一次性识别
   */
  sendAudioChunk(chunk: Blob): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return
    this.ws.send(chunk)
  }

  /**
   * 发送 ASR 结束信号，通知后端对积累的音频进行识别
   */
  sendAsrEnd(): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return
    this.sendMessage({ type: 'asr_end' })
  }

  /**
   * 检查是否已连接
   */
  get connected(): boolean {
    return this.isConnected
  }

  // ==================== 私有方法 ====================

  private sendMessage(msg: Record<string, any>): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(msg))
    }
  }

  private handleMessage(event: MessageEvent): void {
    // 处理二进制音频数据
    if (event.data instanceof ArrayBuffer || event.data instanceof Blob) {
      this.handleAudioData(event.data)
      return
    }

    // 处理文本消息
    try {
      const msg = JSON.parse(event.data)
      this.handleJsonMessage(msg)
    } catch (_) {
      // 非 JSON 消息忽略
    }
  }

  private handleJsonMessage(msg: Record<string, any>): void {
    const type = msg.type as string

    switch (type) {
      case 'ready':
        console.log('[PipelineWS] 服务就绪:', msg.message)
        break

      case 'session_started':
        console.log('[PipelineWS] 会话已启动:', msg.sessionId)
        break

      case 'asr_result':
        // ASR 识别结果（来自后端的流式/积累 ASR）
        if (msg.text) {
          this.handlers.onTranscript?.({
            type: 'transcript',
            role: 'user',
            text: msg.text,
            final: msg.final !== false
          })
        }
        break

      case 'asr_interim':
        // ASR 中间结果（未来流式 ASR 使用）
        if (msg.text) {
          this.handlers.onTranscript?.({
            type: 'transcript',
            role: 'user',
            text: msg.text,
            final: false
          })
        }
        break

      case 'llm_start':
        console.log('[PipelineWS] LLM 开始生成')
        break

      case 'llm_token':
        // 流式 token — 用于前端打字机效果
        if (msg.content) {
          this.handlers.onTranscript?.({
            type: 'transcript',
            role: 'ai',
            text: msg.content,
            final: false
          })
        }
        break

      case 'llm_done':
        // LLM 完成（即使空内容也要发送，确保前端退出 thinking 状态）
        this.handlers.onTranscript?.({
          type: 'transcript',
          role: 'ai',
          text: msg.fullText || '(no response)',
          final: true
        })
        break

      case 'tts_start':
        console.log('[PipelineWS] TTS 开始')
        this.handlers.onAudioStart?.()
        break

      case 'tts_done':
        console.log('[PipelineWS] TTS 结束')
        this.handlers.onAudioEnd?.()
        break

      case 'interrupted':
        console.log('[PipelineWS] 已打断')
        this.handlers.onTranscript?.({
          type: 'transcript',
          role: 'ai',
          text: '[已打断]',
          final: true
        })
        break

      case 'state_change':
        console.log('[PipelineWS] 状态变更:', msg.state)
        break

      case 'error':
        console.error('[PipelineWS] 服务端错误:', msg.message)
        this.handlers.onError?.(new Error(msg.message || '服务端错误'))
        break

      case 'session_ended':
        console.log('[PipelineWS] 会话已结束')
        break

      default:
        console.log('[PipelineWS] 未知消息:', type, msg)
    }
  }

  private handleAudioData(data: ArrayBuffer | Blob): void {
    // 打断后丢弃已发送的音频（WS 传输延迟导致的部分音频仍在路上）
    if (this.discardAudio) {
      console.log('[PipelineWS] 丢弃打断后 TTS 音频:', data instanceof Blob ? data.size : (data.byteLength || 0), 'bytes')
      return
    }
    // 将二进制数据作为 Blob 入队（后端返回的是 WAV 或 MP3 格式）
    const blob = data instanceof Blob ? data : new Blob([data], { type: 'audio/wav' })
    console.log('[PipelineWS] TTS 音频入队:', blob.size, 'bytes, type:', blob.type)
    this.playbackQueue.push(blob)
    if (!this.isPlayingAudio) {
      this.playNextAudio()
    }
  }

  private playNextAudio(): void {
    if (this.playbackQueue.length === 0) {
      this.isPlayingAudio = false
      return
    }

    this.isPlayingAudio = true
    const blob = this.playbackQueue.shift()!
    const url = URL.createObjectURL(blob)

    const audio = new Audio(url)
    this.currentAudio = audio

    audio.onended = () => {
      URL.revokeObjectURL(url)
      this.currentAudio = null
      this.playNextAudio()
    }

    audio.onerror = (e) => {
      console.warn('[PipelineWS] 音频播放错误:', e)
      URL.revokeObjectURL(url)
      this.currentAudio = null
      this.playNextAudio()
    }

    audio.play().catch(err => {
      console.warn('[PipelineWS] audio.play() 失败:', err.message)
      // 可能被浏览器 autoplay 策略阻止，尝试在用户交互后重试
      URL.revokeObjectURL(url)
      this.currentAudio = null
      this.playNextAudio()
    })
  }

  /** 已不需要 — 保留空实现兼容接口签名 */
  private playRawPcm(_buffer: ArrayBuffer): void {
    // TTS 使用 Blob URL 播放，不再需要 raw PCM fallback
    console.warn('[PipelineWS] playRawPcm 已废弃，忽略')
  }

  private generateSessionId(): string {
    return 'pipeline_' + Date.now() + '_' + Math.random().toString(36).slice(2, 8)
  }
}
