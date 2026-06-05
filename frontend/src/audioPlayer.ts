export class AudioPlayer {
  private audioContext: AudioContext | null = null
  private audioQueue: Float32Array[] = []
  private isPlaying = false
  private currentSource: AudioBufferSourceNode | null = null
  private sampleRate = 16000

  constructor() {}

  init(sampleRate: number = 16000): void {
    this.sampleRate = sampleRate
    this.audioContext = new (window.AudioContext || (window as any).webkitAudioContext)({
      sampleRate: this.sampleRate
    })
  }

  enqueueAudio(pcmData: Uint8Array): void {
    if (!this.audioContext) {
      this.init()
    }
    
    // 将 PCM 数据转换为 Float32Array
    const int16Data = new Int16Array(pcmData.buffer)
    const float32Data = new Float32Array(int16Data.length)
    
    for (let i = 0; i < int16Data.length; i++) {
      float32Data[i] = int16Data[i] / 0x8000 // 归一化到 [-1, 1]
    }
    
    this.audioQueue.push(float32Data)
    
    if (!this.isPlaying) {
      this.playNext()
    }
  }

  private playNext(): void {
    if (this.audioQueue.length === 0 || !this.audioContext) {
      this.isPlaying = false
      return
    }
    
    this.isPlaying = true
    
    const audioData = this.audioQueue.shift()!
    const buffer = this.audioContext.createBuffer(1, audioData.length, this.sampleRate)
    const channelData = buffer.getChannelData(0)
    
    channelData.set(audioData)
    
    const source = this.audioContext.createBufferSource()
    source.buffer = buffer
    source.connect(this.audioContext.destination)
    
    source.onended = () => {
      this.playNext()
    }
    
    source.start()
    this.currentSource = source
  }

  stop(): void {
    if (this.currentSource) {
      this.currentSource.stop()
      this.currentSource = null
    }
    
    this.audioQueue = []
    this.isPlaying = false
  }

  destroy(): void {
    this.stop()
    if (this.audioContext) {
      this.audioContext.close()
      this.audioContext = null
    }
  }
}

// 导出单例
export const audioPlayer = new AudioPlayer()
