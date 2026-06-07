/**
 * 轻量级 VAD（语音活动检测）工具
 *
 * 使用 AudioContext + AnalyserNode 实时检测麦克风音量，
 * 当检测到用户说话时触发回调，用于自动打断 AI 播放。
 *
 * 用法:
 *   const vad = new VAD({
 *     onSpeechStart: () => console.log('用户开始说话'),
 *     onSpeechEnd: () => console.log('用户停止说话'),
 *     threshold: 0.05,     // 音量阈值 (0-1)
 *     silenceMs: 500,      // 静音多久视为说话结束
 *   })
 *   await vad.start()
 *   // ...
 *   vad.stop()
 */

export interface VADConfig {
  /** 音量阈值 (0-1)，默认 0.05 */
  threshold?: number
  /** 检测到说话后的防抖延时 (ms)，默认 200ms */
  debounceMs?: number
  /** 说话结束静音超时 (ms)，默认 800ms */
  silenceMs?: number
  /** 用户开始说话回调 */
  onSpeechStart?: () => void
  /** 用户停止说话回调 */
  onSpeechEnd?: () => void
}

export class VAD {
  private audioContext: AudioContext | null = null
  private analyser: AnalyserNode | null = null
  private mediaStream: MediaStream | null = null
  private source: MediaStreamAudioSourceNode | null = null
  private animationId: number | null = null
  private isRunning = false
  private isSpeaking = false
  private silenceStart = 0
  private debounceTimer: number | null = null

  private readonly threshold: number
  private readonly debounceMs: number
  private readonly silenceMs: number
  private readonly onSpeechStart?: () => void
  private readonly onSpeechEnd?: () => void

  constructor(config: VADConfig = {}) {
    this.threshold = config.threshold ?? 0.05
    this.debounceMs = config.debounceMs ?? 200
    this.silenceMs = config.silenceMs ?? 800
    this.onSpeechStart = config.onSpeechStart
    this.onSpeechEnd = config.onSpeechEnd
  }

  /**
   * 启动 VAD 检测
   */
  async start(): Promise<void> {
    if (this.isRunning) return

    try {
      this.mediaStream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true
        }
      })

      this.audioContext = new (window.AudioContext || (window as any).webkitAudioContext)()
      this.source = this.audioContext.createMediaStreamSource(this.mediaStream)
      this.analyser = this.audioContext.createAnalyser()
      this.analyser.fftSize = 256
      this.source.connect(this.analyser)

      this.isRunning = true
      this.silenceStart = Date.now()
      this.poll()
    } catch (err) {
      console.error('[VAD] 启动失败:', err)
      this.cleanup()
      throw err
    }
  }

  /**
   * 停止 VAD 检测
   */
  stop(): void {
    this.isRunning = false
    if (this.animationId !== null) {
      cancelAnimationFrame(this.animationId)
      this.animationId = null
    }
    this.cleanup()
    this.isSpeaking = false
  }

  /**
   * 是否正在运行
   */
  get running(): boolean {
    return this.isRunning
  }

  /**
   * 用户是否正在说话
   */
  get speaking(): boolean {
    return this.isSpeaking
  }

  private poll(): void {
    if (!this.isRunning || !this.analyser) return

    const data = new Uint8Array(this.analyser.frequencyBinCount)
    this.analyser.getByteTimeDomainData(data)

    // 计算 RMS 音量
    let sum = 0
    for (let i = 0; i < data.length; i++) {
      const normalized = (data[i] - 128) / 128
      sum += normalized * normalized
    }
    const rms = Math.sqrt(sum / data.length)

    const thresholdMet = rms > this.threshold

    if (thresholdMet) {
      // 用户正在说话 — 防抖处理
      if (!this.isSpeaking) {
        if (this.debounceTimer === null) {
          this.debounceTimer = window.setTimeout(() => {
            this.debounceTimer = null
            if (this.isRunning) {
              this.isSpeaking = true
              this.onSpeechStart?.()
            }
          }, this.debounceMs)
        }
      } else {
        // 持续说话，重置静音计时
        this.silenceStart = Date.now()
      }
    } else {
      // 静音
      if (this.debounceTimer !== null) {
        clearTimeout(this.debounceTimer)
        this.debounceTimer = null
      }

      if (this.isSpeaking) {
        // 检查静音是否超过阈值
        if (Date.now() - this.silenceStart > this.silenceMs) {
          this.isSpeaking = false
          this.onSpeechEnd?.()
        }
      }
    }

    this.animationId = requestAnimationFrame(() => this.poll())
  }

  private cleanup(): void {
    if (this.source) {
      this.source.disconnect()
      this.source = null
    }
    if (this.audioContext) {
      this.audioContext.close().catch(() => {})
      this.audioContext = null
    }
    if (this.mediaStream) {
      this.mediaStream.getTracks().forEach(t => t.stop())
      this.mediaStream = null
    }
    this.analyser = null
    if (this.debounceTimer !== null) {
      clearTimeout(this.debounceTimer)
      this.debounceTimer = null
    }
  }
}

// 单例
let globalVAD: VAD | null = null

/**
 * 获取全局 VAD 实例
 */
export function getVAD(): VAD | null {
  return globalVAD
}

/**
 * 启动全局 VAD，检测到说话时自动打断
 * @param onInterrupt 用户在 AI 播放时开始说话的回调
 */
export async function startAutoInterruptVAD(onInterrupt: () => void): Promise<void> {
  if (globalVAD?.running) return

  globalVAD = new VAD({
    threshold: 0.05,
    debounceMs: 200,
    silenceMs: 800,
    onSpeechStart: () => {
      console.log('[VAD] 检测到用户说话，自动打断')
      onInterrupt()
    },
    onSpeechEnd: () => {
      console.log('[VAD] 用户停止说话')
    }
  })

  try {
    await globalVAD.start()
    console.log('[VAD] 自动打断 VAD 已启动')
  } catch (err) {
    console.warn('[VAD] 启动失败（可能无麦克风权限）:', err)
    globalVAD = null
  }
}

/**
 * 停止全局 VAD
 */
export function stopAutoInterruptVAD(): void {
  if (globalVAD) {
    globalVAD.stop()
    globalVAD = null
    console.log('[VAD] 自动打断 VAD 已停止')
  }
}
