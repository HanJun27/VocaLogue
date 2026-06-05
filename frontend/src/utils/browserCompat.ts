/**
 * 浏览器兼容性检测工具
 * 检测 Web Speech API、MediaRecorder、AudioContext 等功能的支持情况
 */

export interface BrowserCapabilities {
  // Web Speech API
  hasSpeechRecognition: boolean
  hasSpeechSynthesis: boolean
  
  // 音频录制
  hasMediaRecorder: boolean
  mediaRecorderMimeTypes: string[]
  
  // 音频播放
  hasAudioContext: boolean
  hasWebAudio: boolean
  
  // WebSocket
  hasWebSocket: boolean
  
  // 媒体设备
  hasMediaDevices: boolean
  
  // 综合评估
  recommendedMode: 'realtime' | 'web-speech' | 'keyboard-only'
  warnings: string[]
  isFirefox: boolean
  isChrome: boolean
  isSafari: boolean
  isEdge: boolean
}

/**
 * 检测浏览器类型
 */
function detectBrowser(): { isFirefox: boolean; isChrome: boolean; isSafari: boolean; isEdge: boolean } {
  const ua = navigator.userAgent.toLowerCase()
  
  return {
    isFirefox: ua.includes('firefox'),
    isChrome: ua.includes('chrome') && !ua.includes('edg'),
    isSafari: ua.includes('safari') && !ua.includes('chrome'),
    isEdge: ua.includes('edg')
  }
}

/**
 * 检测支持的 MediaRecorder MIME 类型
 */
function getSupportedMimeTypes(): string[] {
  const types = [
    'audio/webm;codecs=opus',
    'audio/webm',
    'audio/ogg;codecs=opus',
    'audio/mp4',
    'audio/wav'
  ]
  
  return types.filter(type => MediaRecorder.isTypeSupported(type))
}

/**
 * 检测浏览器能力
 */
export function detectBrowserCapabilities(): BrowserCapabilities {
  const browser = detectBrowser()
  const warnings: string[] = []
  
  // Web Speech API 检测
  const hasSpeechRecognition = 'SpeechRecognition' in window || 'webkitSpeechRecognition' in window
  const hasSpeechSynthesis = 'speechSynthesis' in window
  
  // MediaRecorder 检测
  const hasMediaRecorder = typeof MediaRecorder !== 'undefined'
  let mediaRecorderMimeTypes: string[] = []
  
  if (hasMediaRecorder) {
    mediaRecorderMimeTypes = getSupportedMimeTypes()
  }
  
  // AudioContext 检测
  const hasAudioContext = 'AudioContext' in window || 'webkitAudioContext' in window
  const hasWebAudio = hasAudioContext
  
  // WebSocket 检测
  const hasWebSocket = 'WebSocket' in window
  
  // MediaDevices 检测
  const hasMediaDevices = !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia)
  
  // 生成警告信息
  if (!hasSpeechRecognition) {
    warnings.push('当前浏览器不支持语音识别 (SpeechRecognition)，将使用实时音频流方案')
  }
  
  if (!hasSpeechSynthesis) {
    warnings.push('当前浏览器不支持语音合成 (SpeechSynthesis)，将使用音频流播放')
  }
  
  if (!hasMediaRecorder) {
    warnings.push('当前浏览器不支持音频录制 (MediaRecorder)，语音功能将不可用')
  }
  
  if (!hasMediaDevices) {
    warnings.push('当前浏览器不支持麦克风访问，语音功能将不可用')
  }
  
  if (browser.isFirefox) {
    if (!hasSpeechRecognition) {
      warnings.push('Firefox 不支持 Web Speech API，已自动切换到实时音频流模式')
    }
  }
  
  // 确定推荐模式
  let recommendedMode: 'realtime' | 'web-speech' | 'keyboard-only'
  
  if (!hasMediaDevices || !hasMediaRecorder || !hasWebSocket) {
    recommendedMode = 'keyboard-only'
    warnings.push('浏览器缺少必要的音频功能，仅支持键盘输入模式')
  } else if (browser.isFirefox || !hasSpeechRecognition) {
    // Firefox 或不支持 Web Speech 的浏览器，使用 Realtime API
    recommendedMode = 'realtime'
  } else {
    // Chrome/Edge 等支持 Web Speech 的浏览器，可以选择使用
    recommendedMode = 'web-speech'
  }
  
  return {
    hasSpeechRecognition,
    hasSpeechSynthesis,
    hasMediaRecorder,
    mediaRecorderMimeTypes,
    hasAudioContext,
    hasWebAudio,
    hasWebSocket,
    hasMediaDevices,
    recommendedMode,
    warnings,
    ...browser
  }
}

/**
 * 获取推荐的音频 MIME 类型
 */
export function getRecommendedMimeType(capabilities: BrowserCapabilities): string | null {
  const preferredTypes = [
    'audio/webm;codecs=opus',  // 最佳：Opus 编码，低延迟
    'audio/webm',
    'audio/ogg;codecs=opus',
    'audio/mp4'
  ]
  
  for (const type of preferredTypes) {
    if (capabilities.mediaRecorderMimeTypes.includes(type)) {
      return type
    }
  }
  
  return capabilities.mediaRecorderMimeTypes[0] || null
}

/**
 * 检查是否可以使用实时语音模式
 */
export function canUseRealtimeVoice(capabilities: BrowserCapabilities): boolean {
  return capabilities.hasMediaDevices && 
         capabilities.hasMediaRecorder && 
         capabilities.hasWebSocket &&
         capabilities.hasAudioContext
}

/**
 * 检查是否可以使用 Web Speech API
 */
export function canUseWebSpeech(capabilities: BrowserCapabilities): boolean {
  return capabilities.hasSpeechRecognition && capabilities.hasSpeechSynthesis
}

/**
 * 获取用户友好的浏览器兼容性提示
 */
export function getCompatibilityMessage(capabilities: BrowserCapabilities): string {
  if (capabilities.recommendedMode === 'keyboard-only') {
    return '您的浏览器不支持语音功能，请使用键盘输入模式，或切换到 Chrome/Edge 浏览器获得完整体验。'
  }
  
  if (capabilities.recommendedMode === 'realtime') {
    if (capabilities.isFirefox) {
      return 'Firefox 浏览器已自动启用实时音频流模式，支持完整的语音对话功能。'
    }
    return '已启用实时音频流模式，支持低延迟语音对话。'
  }
  
  return '您的浏览器支持完整的语音功能，可以使用语音识别和语音合成。'
}

// 导出单例检测结果（延迟检测）
let cachedCapabilities: BrowserCapabilities | null = null

export function getBrowserCapabilities(): BrowserCapabilities {
  if (!cachedCapabilities) {
    cachedCapabilities = detectBrowserCapabilities()
  }
  return cachedCapabilities
}