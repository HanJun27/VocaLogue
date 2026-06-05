/**
 * Whisper ASR 服务
 * 使用后端 Whisper API 进行语音识别
 * 
 * 来源参考：everyone-can-use-english 的 use-transcribe.tsx 实现思路
 */

import api from '@/api'

export interface WhisperResult {
  text: string
  language: string
  duration: number
  hasTimeline: boolean
}

export interface AsrOptions {
  language?: string
  timeout?: number
}

/**
 * Whisper ASR 服务类
 */
export class WhisperASRService {
  private mediaRecorder: MediaRecorder | null = null
  private audioChunks: Blob[] = []
  private mediaStream: MediaStream | null = null
  private isRecording = false
  private audioInputDeviceId: string = ''

  /**
   * 设置音频输入设备
   */
  setAudioInputDevice(deviceId: string): void {
    this.audioInputDeviceId = deviceId
  }

  /**
   * 检查浏览器是否支持录音
   */
  isSupported(): boolean {
    return !!(
      navigator.mediaDevices &&
      navigator.mediaDevices.getUserMedia &&
      window.MediaRecorder
    )
  }

  /**
   * 开始录音
   */
  async startRecording(): Promise<void> {
    if (this.isRecording) {
      console.warn('[WhisperASR] 已经在录音中')
      return
    }

    try {
      // 获取麦克风权限
      const audioConstraints: MediaTrackConstraints = {
        sampleRate: 16000,
        channelCount: 1,
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true
      }

      if (this.audioInputDeviceId) {
        audioConstraints.deviceId = { exact: this.audioInputDeviceId }
      }

      this.mediaStream = await navigator.mediaDevices.getUserMedia({
        audio: audioConstraints
      })

      // 创建 MediaRecorder
      // 尝试使用不同的 MIME 类型
      let mimeType = 'audio/webm;codecs=opus'
      if (!MediaRecorder.isTypeSupported(mimeType)) {
        mimeType = 'audio/webm'
        if (!MediaRecorder.isTypeSupported(mimeType)) {
          mimeType = 'audio/mp4'
          if (!MediaRecorder.isTypeSupported(mimeType)) {
            mimeType = '' // 使用默认类型
          }
        }
      }

      const options = mimeType ? { mimeType } : {}
      this.mediaRecorder = new MediaRecorder(this.mediaStream, options)

      this.audioChunks = []

      this.mediaRecorder.ondataavailable = (event) => {
        if (event.data.size > 0) {
          this.audioChunks.push(event.data)
        }
      }

      // 开始录音，每 100ms 收集一次数据
      this.mediaRecorder.start(100)
      this.isRecording = true

      console.log('[WhisperASR] 开始录音')
    } catch (error) {
      console.error('[WhisperASR] 获取麦克风权限失败:', error)
      throw new Error('无法获取麦克风权限，请检查浏览器设置')
    }
  }

  /**
   * 停止录音并返回录音数据
   */
  async stopRecording(): Promise<Blob | null> {
    if (!this.isRecording || !this.mediaRecorder) {
      return null
    }

    return new Promise((resolve) => {
      this.mediaRecorder!.onstop = () => {
        const audioBlob = new Blob(this.audioChunks, {
          type: this.mediaRecorder!.mimeType || 'audio/webm'
        })
        this.cleanup()
        resolve(audioBlob)
      }

      this.mediaRecorder!.stop()
      this.isRecording = false
    })
  }

  /**
   * 取消录音
   */
  cancelRecording(): void {
    if (this.mediaRecorder && this.isRecording) {
      this.mediaRecorder.stop()
    }
    this.cleanup()
    this.isRecording = false
  }

  /**
   * 清理资源
   */
  private cleanup(): void {
    if (this.mediaStream) {
      this.mediaStream.getTracks().forEach(track => track.stop())
      this.mediaStream = null
    }
    this.mediaRecorder = null
    this.audioChunks = []
  }

  /**
   * 使用 Whisper API 转录音频
   */
  async transcribe(
    audioBlob: Blob,
    options: AsrOptions = {}
  ): Promise<WhisperResult> {
    const { language = 'en', timeout = 30000 } = options

    console.log('[WhisperASR] 开始转录:', {
      size: audioBlob.size,
      type: audioBlob.type,
      language
    })

    try {
      const formData = new FormData()
      formData.append('file', audioBlob, 'audio.webm')
      formData.append('language', language)

      const baseUrl = import.meta.env.VITE_API_URL || 'http://localhost:8080'
      const url = `${baseUrl}/api/asr/transcribe`

      const controller = new AbortController()
      const timeoutId = setTimeout(() => controller.abort(), timeout)

      const response = await fetch(url, {
        method: 'POST',
        body: formData,
        signal: controller.signal
      })

      clearTimeout(timeoutId)

      if (!response.ok) {
        const errorText = await response.text()
        console.error('[WhisperASR] 转录失败:', response.status, errorText)
        throw new Error(`转录失败: ${response.status}`)
      }

      const result = await response.json()

      if (result.code !== 200) {
        console.error('[WhisperASR] API 错误:', result.message)
        throw new Error(result.message || '转录失败')
      }

      const data = result.data
      console.log('[WhisperASR] 转录结果:', {
        textLength: data.text?.length,
        language: data.language,
        duration: data.duration
      })

      return {
        text: data.text || '',
        language: data.language || language,
        duration: data.duration || 0,
        hasTimeline: data.hasTimeline || false
      }
    } catch (error) {
      if (error instanceof Error && error.name === 'AbortError') {
        throw new Error('转录超时，请重试')
      }
      console.error('[WhisperASR] 转录异常:', error)
      throw error
    }
  }

  /**
   * 录制并转录（一键完成）
   */
  async recordAndTranscribe(
    options: AsrOptions = {}
  ): Promise<WhisperResult> {
    await this.startRecording()
    
    // 录音最长 30 秒
    return new Promise((resolve, reject) => {
      const maxDuration = 30000
      const timeoutId = setTimeout(async () => {
        try {
          const audioBlob = await this.stopRecording()
          if (audioBlob && audioBlob.size > 0) {
            const result = await this.transcribe(audioBlob, options)
            resolve(result)
          } else {
            reject(new Error('没有录制到音频数据'))
          }
        } catch (error) {
          reject(error)
        }
      }, maxDuration)

      // 监听录音结束（用户手动停止）
      const originalStop = this.stopRecording.bind(this)
      this.stopRecording = async () => {
        clearTimeout(timeoutId)
        try {
          const audioBlob = await originalStop()
          if (audioBlob && audioBlob.size > 0) {
            const result = await this.transcribe(audioBlob, options)
            resolve(result)
          } else {
            reject(new Error('没有录制到音频数据'))
          }
        } catch (error) {
          reject(error)
        }
      }
    })
  }
}

// 导出单例
export const whisperASRService = new WhisperASRService()
