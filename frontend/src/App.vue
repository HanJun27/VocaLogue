<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import Header from '@/components/Header.vue'
import ChatArea from '@/components/ChatArea.vue'
import ControlPanel from '@/components/ControlPanel.vue'
import PronunciationModal from '@/components/PronunciationModal.vue'
import ScenarioSelection from '@/components/ScenarioSelection.vue'
import PracticeSummary from '@/components/PracticeSummary.vue'
import SettingsPage from '@/components/SettingsPage.vue'
import ConversationSidebar from '@/components/ConversationSidebar.vue'
import ConversationHistory from '@/components/ConversationHistory.vue'
import PracticeModeModal from '@/components/PracticeModeModal.vue'
import { SCENARIOS, MOCK_RATING_DATABASE } from '@/scenariosData'
import type { DialectMessage, Scenario, PronunciationScore, GrammarFeedback, ConversationSummary, PracticeSummaryResult } from '@/types'
import { Award, AlertCircle } from 'lucide-vue-next'
import api, { API_BASE_URL, getHeaders } from '@/api'
import { 
  getBrowserCapabilities, 
  getCompatibilityMessage,
  type BrowserCapabilities 
} from '@/utils/browserCompat'
import { voiceService } from '@/services/voice/VoiceService'
import { PipelineWebSocketAdapter } from '@/services/voice/adapters/PipelineWebSocketAdapter'
import { whisperASRService } from '@/services/asr'
import { configService } from '@/config'
import type { TranscriptEvent } from '@/services/voice/IVoiceService'
import { startAutoInterruptVAD, stopAutoInterruptVAD, getVAD } from '@/utils/vad'

// 浏览器兼容性检测
const browserCapabilities = ref<BrowserCapabilities | null>(null)
const showCompatBanner = ref(false)
const compatMessage = ref('')

// API 模式控制（启用后端 API 集成）
const USE_API_MODE = ref(true)
const currentSessionId = ref<string | null>(null)

// 模式选择状态
const showModeSelector = ref(false)
const practiceMode = ref<'pipeline' | 'realtime'>('pipeline')

// 真实语音大模型状态
const voiceConnected = ref(false)
const voiceConnecting = ref(false)
const voiceAiReady = ref(false)
const voiceAiInterimText = ref('')
const pendingAiMessageId = ref<string | null>(null)

// 管线 WebSocket 模式状态（替代 REST SSE）
const pipelineAdapter = ref<PipelineWebSocketAdapter | null>(null)
const pipelineWsConnected = ref(false)
// 管线模式下用于音频流式 ASR
let pipelineMediaRecorder: MediaRecorder | null = null
let pipelineMediaStream: MediaStream | null = null
let pipelineAudioChunks: Blob[] = []
let asrWebSocket: WebSocket | null = null

// 是否应该使用真实语音大模型
const shouldUseVoiceAI = computed(() => {
  return configService.isConfigComplete()
})

const currentView = ref<'scenarios' | 'practice' | 'summary' | 'settings' | 'history'>('scenarios')
const currentScenario = ref<Scenario>(SCENARIOS[0])
const messages = ref<DialectMessage[]>([])
const currentQuestionIndex = ref(0)

const isPlayingAudio = ref(false)
const activeVoiceMessageId = ref<string | null>(null)
const subtitlesOn = ref(true)
const isTypingMode = ref(false)

const ratingOpen = ref(false)
const currentRating = ref<PronunciationScore | null>(null)
/** LLM 生成的详细练习总结（后端 EvaluationService 返回） */
const currentSummaryResult = ref<PracticeSummaryResult | null>(null)
const isThinking = ref(false)
/** 是否正在结束对话（生成总结） */
const isEndingConversation = ref(false)

const isRecording = ref(false)
const currentTranscript = ref('')
const recognitionInstance = ref<any>(null)

// ===== 实时对话模式 =====
const isRealtimeMode = ref(false)
/** VAD 检测 — AudioContext 实例 */
let realtimeAudioCtx: AudioContext | null = null
/** VAD 检测 — AnalyserNode 实例 */
let realtimeAnalyser: AnalyserNode | null = null
/** VAD 检测 — 定时器 */
let realtimeVadTimer: ReturnType<typeof setInterval> | null = null
/** VAD 检测 — 连续静音帧计数 */
let realtimeSilenceFrames = 0
/** VAD 检测 — 是否已检测到过语音 */
let realtimeHasSpeech = false
/** VAD 检测 — 是否已触发 ASR（防重复触发） */
let realtimeAsrPending = false
/** 当前流式 ASR 用户消息 ID（用于实时更新对话框） */
let realtimeAsrMsgId: string | null = null

// Whisper ASR 录音状态
const isWhisperRecording = ref(false)
const whisperRecordingBlob = ref<Blob | null>(null)

// 麦克风设备列表
const audioDevices = ref<{ deviceId: string; groupId: string; kind: string; label: string }[]>([])
const selectedAudioDeviceId = ref(configService.getConfig().audioInputDeviceId)

// 对话记录列表状态
const conversationsList = ref<ConversationSummary[]>([])
const conversationsLoading = ref(false)

const statusTime = ref('10:00')

/**
 * 加载对话记录列表
 */
const loadConversations = async () => {
  conversationsLoading.value = true
  try {
    const userId = 'anonymous'
    const convs = await api.getConversations(userId)
    conversationsList.value = convs
  } catch (error) {
    console.error('加载对话记录失败:', error)
    conversationsList.value = []
  } finally {
    conversationsLoading.value = false
  }
}

/**
 * 从 configService 解析当前 LLM 引擎的完整配置（API Key、Base URL、Engine、Model）
 * 供创建会话等需要向后端传递 LLM 配置的场景使用
 */
function resolveLlmPipelineConfig(): {
  llmEngine: string
  llmModel: string
  llmApiKey: string
  llmBaseUrl: string
} {
  const config = configService.getConfig()
  const llmEngine = config.pipelineLlmEngine || 'openai'
  const llmModel = config.pipelineLlmModel || ''
  let llmApiKey = ''
  let llmBaseUrl = ''

  switch (llmEngine) {
    case 'deepseek':
      llmApiKey = config.deepseekApiKey || ''
      llmBaseUrl = 'https://api.deepseek.com/v1'
      break
    case 'glm':
      llmApiKey = config.glmApiKey || ''
      llmBaseUrl = config.glmApiUrl || 'https://open.bigmodel.cn/api/paas/v4'
      break
    case 'qianwen':
      llmApiKey = config.qianwenApiKey || ''
      llmBaseUrl = config.qianwenApiUrl || 'https://dashscope.aliyuncs.com/compatible-mode/v1'
      break
    case 'doubao':
      llmApiKey = config.apiKey || ''
      llmBaseUrl = 'https://api.doubao.com/v1'
      break
    case 'openai':
    default:
      llmApiKey = config.apiKey || ''
      llmBaseUrl = 'https://api.openai.com/v1'
      break
  }

  return { llmEngine, llmModel, llmApiKey, llmBaseUrl }
}

/**
 * 查看历史对话
 */
const handleViewConversation = (sessionId: string) => {
  console.log('查看对话:', sessionId)
  // 切换到 practice 视图并加载该会话的消息
  currentSessionId.value = sessionId
  currentView.value = 'practice'
  // 从后端加载该会话的对话历史
  loadHistoricalMessages(sessionId)
}

/**
 * 加载指定会话的对话历史
 */
const loadHistoricalMessages = async (sessionId: string) => {
  try {
    const messageList = await api.getConversationHistory(sessionId)
    // 转换为 DialectMessage 格式
    const historicalMessages: DialectMessage[] = messageList.map(msg => ({
      id: msg.id,
      role: msg.role === 'assistant' ? 'ai' : (msg.role as 'ai' | 'user'),
      text: msg.text,
      translation: msg.translation,
      timestamp: msg.timestamp,
      showTranslation: subtitlesOn.value
    }))
    messages.value = historicalMessages

    // 更新场景信息
    if (currentSessionId.value) {
      const conv = conversationsList.value.find(c => c.sessionId === currentSessionId.value)
      if (conv) {
        const sc = SCENARIOS.find(s => s.id === conv.scenarioId)
        if (sc) {
          currentScenario.value = sc
        }
      }
    }
  } catch (error) {
    console.error('加载对话历史失败:', error)
  }
}

/**
 * 删除对话记录
 */
const handleDeleteConversation = async (sessionId: string) => {
  if (!confirm('确定要删除这条对话记录吗？此操作不可撤销。')) {
    return
  }
  try {
    await api.deleteConversationRecords(sessionId)
    // 从列表中移除
    conversationsList.value = conversationsList.value.filter(c => c.sessionId !== sessionId)
  } catch (error) {
    console.error('删除对话记录失败:', error)
    alert('删除失败，请重试')
  }
}

/**
 * 新建对话 - 回到场景选择页
 */
const handleCreateNewConversation = () => {
  currentSessionId.value = null
  currentView.value = 'scenarios'
}

/**
 * 结束/完成对话
 */
const handleEndConversation = async () => {
  if (!currentSessionId.value) {
    return
  }
  
  if (!confirm('确定要结束当前对话吗？这将生成对话总结并保存到历史记录。')) {
    return
  }
  
  isEndingConversation.value = true
  currentSummaryResult.value = null
  
  try {
    // 获取当前用户设置的 LLM 配置，传给后端覆盖 Redis 配置
    const llmConfig = resolveLlmPipelineConfig()
    // 结束会话 — 后端会调用 LLM 评测（超时时自动回退到规则引擎），直接返回总结
    const summary = await api.endConversation(currentSessionId.value, {
      llmEngine: llmConfig.llmEngine,
      llmModel: llmConfig.llmModel,
      llmApiKey: llmConfig.llmApiKey,
      llmBaseUrl: llmConfig.llmBaseUrl,
    })
    currentSummaryResult.value = summary
    
    await loadConversations()
    // 跳转到总结页面
    currentView.value = 'summary'
  } catch (error) {
    console.error('结束对话失败:', error)
    alert('结束对话失败：' + (error instanceof Error ? error.message : '未知错误'))
  } finally {
    isEndingConversation.value = false
  }
}

const resetConversationForScenario = (sc: Scenario) => {
  window.speechSynthesis?.cancel()
  isPlayingAudio.value = false
  activeVoiceMessageId.value = null
  currentQuestionIndex.value = 0
  isThinking.value = false
  isRecording.value = false
  currentTranscript.value = ''

  const welcomeMsg: DialectMessage = {
    id: 'welcome_' + Date.now(),
    role: 'ai',
    text: sc.welcomeMessage,
    translation: sc.welcomeTranslation,
    timestamp: '10:00',
    showTranslation: subtitlesOn.value
  }

  messages.value = [welcomeMsg]
  statusTime.value = '10:00'

  // 如果使用真实语音大模型，不自动播放欢迎语（由 AI 端播放）
  if (currentView.value === 'practice' && !shouldUseVoiceAI.value) {
    setTimeout(() => {
      handleManualSpeak(sc.welcomeMessage, welcomeMsg.id)
    }, 400)
  }
}

/**
 * 重新练习：重置状态 + 创建新会话
 */
const handleRestartPractice = async () => {
  resetConversationForScenario(currentScenario.value)
  currentView.value = 'practice'
  currentSummaryResult.value = null

  // 创建新后端会话
  if (USE_API_MODE.value) {
    try {
      const llmConfig = resolveLlmPipelineConfig()
      const conversation = await api.createConversation(currentScenario.value.id, undefined, {
        useAsr: false,
        useTts: false,
        llmEngine: llmConfig.llmEngine,
        llmModel: llmConfig.llmModel,
        llmApiKey: llmConfig.llmApiKey,
        llmBaseUrl: llmConfig.llmBaseUrl,
        llmTemperature: 0.7,
        ttsEngine: 'openai',
        ttsModel: 'tts-1',
        ttsVoice: 'alloy'
      })
      currentSessionId.value = conversation.sessionId
      console.log('重新练习，新会话ID:', conversation.sessionId)
    } catch (error) {
      console.error('创建新会话失败:', error)
    }
  }

  await loadConversations()
}

const speakAudio = (text: string, messageId: string) => {
  // 清洗 TTS 朗读文本中的特殊字符
  const cleanText = text.replace(/\*/g, '')
  if ('speechSynthesis' in window) {
    window.speechSynthesis.cancel()

    if (activeVoiceMessageId.value === messageId && isPlayingAudio.value) {
      isPlayingAudio.value = false
      activeVoiceMessageId.value = null
      return
    }

    const utterance = new SpeechSynthesisUtterance(cleanText)
    utterance.lang = 'en-US'
    utterance.rate = 0.95

    utterance.onstart = () => {
      isPlayingAudio.value = true
      activeVoiceMessageId.value = messageId
    }

    utterance.onend = () => {
      isPlayingAudio.value = false
      activeVoiceMessageId.value = null
    }

    utterance.onerror = () => {
      isPlayingAudio.value = false
      activeVoiceMessageId.value = null
    }

    window.speechSynthesis.speak(utterance)
  } else {
    alert('抱歉，您的浏览器不支持语音播放合成 (TTS)')
  }
}

/**
 * 使用本地 TTS 服务（Piper/Edge-TTS）播放语音
 */
const playLocalTtsAudio = async (text: string, messageId: string) => {
  // 清洗 TTS 朗读文本中的特殊字符
  const cleanText = text.replace(/\*/g, '')
  const cfg = configService.getConfig()
  const baseUrl = cfg.localTtsBaseUrl || 'http://localhost:8000'
  const engine = cfg.pipelineTtsEngine === 'edge-tts' ? 'edge-tts' : 'piper'
  const voice = engine === 'piper' ? cfg.localTtsPiperVoice : cfg.localTtsEdgeVoice
  const speed = cfg.localTtsPiperSpeed || 1.0

  // 取消正在播放的语音
  if (activeVoiceMessageId.value === messageId && isPlayingAudio.value) {
    isPlayingAudio.value = false
    activeVoiceMessageId.value = null
    return
  }
  window.speechSynthesis?.cancel()

  try {
    const response = await fetch(`${baseUrl}/tts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        text: cleanText,
        engine,
        voice,
        speed,
        output_format: 'wav',
      }),
    })

    if (!response.ok) {
      throw new Error(`TTS 服务返回 ${response.status}`)
    }

    const audioBlob = await response.blob()
    const audioUrl = URL.createObjectURL(audioBlob)
    const audio = new Audio(audioUrl)

    audio.onplay = () => {
      isPlayingAudio.value = true
      activeVoiceMessageId.value = messageId
    }

    audio.onended = () => {
      isPlayingAudio.value = false
      activeVoiceMessageId.value = null
      URL.revokeObjectURL(audioUrl)
    }

    audio.onerror = () => {
      console.error('Local TTS playback error')
      isPlayingAudio.value = false
      activeVoiceMessageId.value = null
      URL.revokeObjectURL(audioUrl)
    }

    await audio.play()
  } catch (err) {
    console.error('Local TTS playback failed:', err)
    isPlayingAudio.value = false
    activeVoiceMessageId.value = null
  }
}

/**
 * 根据当前配置选择合适的 TTS 播放方式（手动点击喇叭按钮时调用）
 */
const handleManualSpeak = (text: string, messageId: string) => {
  // 清理 TTS 朗读中的特殊字符（如 * 号会被朗读为 "star"）
  const cleanText = text.replace(/\*/g, '')
  const cfg = configService.getConfig()
  if (cfg.enableAiPipeline && (cfg.pipelineTtsEngine === 'piper' || cfg.pipelineTtsEngine === 'edge-tts')) {
    playLocalTtsAudio(cleanText, messageId)
  } else {
    speakAudio(cleanText, messageId)
  }
}

/**
 * 枚举音频输入设备
 */
const enumerateAudioDevices = async () => {
  try {
    // 先请求麦克风权限，确保可以获取设备标签
    await navigator.mediaDevices.getUserMedia({ audio: true }).then(stream => {
      stream.getTracks().forEach(track => track.stop())
    }).catch(() => {
      // 权限被拒绝也没关系，我们仍然可以列出设备ID，只是没有标签
    })

    const devices = await navigator.mediaDevices.enumerateDevices()
    const audioInputs = devices
      .filter(d => d.kind === 'audioinput')
      .map(d => ({
        deviceId: d.deviceId,
        groupId: d.groupId,
        kind: d.kind,
        label: d.label
      }))
    
    audioDevices.value = audioInputs
    console.log('[App] 可用麦克风设备:', audioInputs.map(d => d.label || d.deviceId))
  } catch (err) {
    console.error('[App] 枚举音频设备失败:', err)
  }
}

/**
 * 切换麦克风设备
 */
const handleChangeAudioDevice = (deviceId: string) => {
  selectedAudioDeviceId.value = deviceId
  voiceService.setAudioInputDevice(deviceId)
  console.log('[App] 麦克风设备已切换:', deviceId || '默认')
}

// 浏览器兼容性检测初始化
onMounted(() => {
  browserCapabilities.value = getBrowserCapabilities()
  compatMessage.value = getCompatibilityMessage(browserCapabilities.value)
  
  // 如果有警告信息，显示兼容性提示
  if (browserCapabilities.value.warnings.length > 0) {
    showCompatBanner.value = true
  }
  
  // 根据浏览器能力自动选择模式
  if (browserCapabilities.value.recommendedMode === 'keyboard-only') {
    isTypingMode.value = true
  }

  // 枚举麦克风设备
  enumerateAudioDevices()
  // 监听设备变化（如用户插拔麦克风）
  navigator.mediaDevices.addEventListener('devicechange', enumerateAudioDevices)
  
  console.log('[App] 浏览器兼容性:', browserCapabilities.value)
})

// 关闭兼容性提示
const dismissCompatBanner = () => {
  showCompatBanner.value = false
}

if ('speechSynthesis' in window) {
  const handleEnd = () => {
    isPlayingAudio.value = false
    activeVoiceMessageId.value = null
  }
  window.speechSynthesis.addEventListener('end', handleEnd)
}

watch(currentScenario, (newScenario) => {
  resetConversationForScenario(newScenario)
}, { immediate: true })

// 模式切换时，断开对应的连接
watch(practiceMode, (newMode) => {
  if (newMode !== 'pipeline') {
    disconnectPipelineWebSocket()
  }
  if (newMode !== 'realtime') {
    disconnectVoiceAI()
  }
})

// 监听 AI 播放状态，自动启停 VAD 打断检测
watch([isPlayingAudio, isThinking], ([playing, thinking]) => {
  if (!currentSessionId.value) return  // 只在活跃会话中启用
  if (playing || thinking) {
    if (!getVAD()?.running) {
      startAutoInterruptVAD(() => {
        interruptPlayback()
      }).catch(() => {
        // VAD 启动失败（无麦克风权限），静默忽略
      })
    }
  } else {
    stopAutoInterruptVAD()
  }
})

const handleToggleTranslation = (messageId: string) => {
  messages.value = messages.value.map((msg) =>
    msg.id === messageId ? { ...msg, showTranslation: !msg.showTranslation } : msg
  )
}

/**
 * 管线模式：启动音频录音 + 流式 WS 音频发送（200ms 分片发送到后端 ASR）
 */
async function startPipelineAsrRecording() {
  // 检查浏览器是否支持录音
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    alert('您的浏览器不支持麦克风录音')
    return
  }

  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        deviceId: selectedAudioDeviceId.value ? { exact: selectedAudioDeviceId.value } : undefined,
        sampleRate: 16000,
        channelCount: 1,
        echoCancellation: true,
        noiseSuppression: true,
      }
    })

    pipelineMediaStream = stream

    // 使用 MediaRecorder 每 200ms 发送音频块到后端 ASR
    const mimeType = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
      ? 'audio/webm;codecs=opus'
      : 'audio/webm'

    const recorder = new MediaRecorder(stream, { mimeType })
    pipelineMediaRecorder = recorder

    recorder.ondataavailable = (event) => {
      if (event.data.size > 0) {
        // 通过 WebSocket 发送二进制音频块到后端 ASR
        if (pipelineAdapter.value) {
          pipelineAdapter.value.sendAudioChunk(event.data)
        }
      }
    }

    recorder.onstop = () => {
      // 停止所有音轨
      stream.getTracks().forEach(t => t.stop())

      // 通知后端 ASR 音频结束，开始识别
      if (pipelineAdapter.value) {
        pipelineAdapter.value.sendAsrEnd()
      }
      isRecording.value = false
      console.log('[Pipeline] ASR 录音结束，已发送结束信号到后端')
    }

    // 每 200ms 收集一次音频块（流式发送）
    recorder.start(200)
    isRecording.value = true
    console.log('[Pipeline] ASR 流式录音开始，每 200ms 发送音频块')

  } catch (err: any) {
    console.error('[Pipeline] 启动录音失败:', err)
    alert(`录音启动失败: ${err.message}`)
  }
}

// ==================== 实时对话模式 ====================

/**
 * VAD 检测 — 使用 AnalyserNode 检测音频能量
 * 每 200ms 检查一次：
 *   - 能量 > 阈值 → 有语音
 *   - 能量 < 阈值 → 静音，累加静音帧
 *   - 静音帧 >= 8 (≈1.6s) 且之前有过语音 → 触发 ASR
 *   - 检测到语音时如果 AI 正在说话 → 自动打断
 */
function checkRealtimeVad(): void {
  if (!realtimeAnalyser) return

  const data = new Uint8Array(realtimeAnalyser.frequencyBinCount)
  realtimeAnalyser.getByteFrequencyData(data)
  const avg = data.reduce((a, b) => a + b, 0) / data.length

  const hasSpeech = avg > 15 // 能量阈值

  if (hasSpeech) {
    // 语音中
    realtimeSilenceFrames = 0
    if (!realtimeHasSpeech) {
      realtimeHasSpeech = true
      currentTranscript.value = '...'
      // 实时对话模式下，检测到语音就打断（不依赖 isPlayingAudio 标志，
      // 因为多段 TTS 的段间间隙 isPlayingAudio 会短暂为 false）
      if (isPlayingAudio.value || isThinking.value || isRealtimeMode.value) {
        console.log('[Realtime] 检测到用户说话，自动打断 AI')
        interruptPlayback()
      }
    }
  } else {
    // 静音
    if (realtimeHasSpeech) {
      realtimeSilenceFrames++
      // ≈1.6s 静音 → 触发 ASR
      if (realtimeSilenceFrames >= 8 && !realtimeAsrPending) {
        realtimeAsrPending = true
        realtimeHasSpeech = false
        realtimeSilenceFrames = 0
        currentTranscript.value = ''

        // 创建用户消息占位
        const minutesAdded = messages.value.length + 1
        const timeString = `10:${minutesAdded < 10 ? '0' + minutesAdded : minutesAdded}`
        const tempMsg: DialectMessage = {
          id: 'asr_' + Date.now(),
          role: 'user',
          text: '',
          timestamp: timeString,
        }
        realtimeAsrMsgId = tempMsg.id
        messages.value = [...messages.value, tempMsg]

        // 停止当前录音机 → onstop 会自动 sendAsrEnd
        if (pipelineMediaRecorder && pipelineMediaRecorder.state === 'recording') {
          console.log('[Realtime] 静音检测，停止录音机触发 ASR')
          pipelineMediaRecorder.stop()
        } else if (pipelineAdapter.value) {
          console.log('[Realtime] 静音检测，直接 sendAsrEnd')
          pipelineAdapter.value.sendAsrEnd()
        }
      }
    }
  }
}

/**
 * 启动实时对话模式
 *  1. 连接管线 WS
 *  2. 打开麦克风 + MediaRecorder 持续录音
 *  3. 启动 AnalyserNode VAD
 *  4. VAD 检测静音 → 自动 ASR → LLM → 继续录音
 */
/** 上次同步到 ASR 服务的设备，避免反复 UpdateSettings 阻塞 30s */
let lastAsrDevice: string | null = null

async function startRealtimeConversation(): Promise<void> {
  // 只在设备变化时才同步 ASR 设置（避免反复阻塞 30s）
  const cfg = configService.getConfig()
  const targetDevice = cfg.asrGpuEnabled ? 'cuda' : 'cpu'
  if (targetDevice !== lastAsrDevice) {
    try {
      await api.updateAsrSettings({ device: targetDevice })
      console.log('[Realtime] ASR 设备已应用:', targetDevice)
      lastAsrDevice = targetDevice
    } catch (e) {
      console.warn('[Realtime] ASR 设置同步失败（使用当前设备）:', e)
    }
  }

  // 确保管线 WS 已连接
  if (!pipelineWsConnected.value) {
    await connectPipelineWebSocket()
    if (!pipelineWsConnected.value) {
      console.error('[Realtime] 管线 WS 连接失败')
      return
    }
  }

  // 打断 AI 播放
  if (isPlayingAudio.value || isThinking.value) {
    interruptPlayback()
    await new Promise(r => setTimeout(r, 300))
  }

  try {
    // 获取麦克风流
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        deviceId: selectedAudioDeviceId.value ? { exact: selectedAudioDeviceId.value } : undefined,
        echoCancellation: true,
        noiseSuppression: true,
      }
    })
    pipelineMediaStream = stream

    // 设置 VAD 分析器
    const audioCtx = new AudioContext()
    const source = audioCtx.createMediaStreamSource(stream)
    const analyser = audioCtx.createAnalyser()
    analyser.fftSize = 256
    source.connect(analyser)
    realtimeAudioCtx = audioCtx
    realtimeAnalyser = analyser

    // 启动 MediaRecorder 持续录音（200ms 分片）
    const mimeType = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
      ? 'audio/webm;codecs=opus' : 'audio/webm'

    const recorder = new MediaRecorder(stream, { mimeType })
    pipelineMediaRecorder = recorder

    recorder.ondataavailable = (event) => {
      // 持续发送所有音频块。后端在 asr_end 前积累到一个 gRPC 流，
      // asr_end 后自动创建新流，不会混淆
      if (event.data.size > 0 && pipelineAdapter.value) {
        pipelineAdapter.value.sendAudioChunk(event.data)
      }
    }

    recorder.onstop = () => {
      // 停止时不关闭 mic 流（VAD + 后续 recorder 复用同一个 stream）
      isRecording.value = false
      // 通知后端 ASR 音频结束，开始识别
      if (pipelineAdapter.value) {
        pipelineAdapter.value.sendAsrEnd()
      }
      console.log('[Realtime] 录音停止，已发送结束信号到后端')
    }

    recorder.start(200)
    isRecording.value = true
    isRealtimeMode.value = true

    // 重置 VAD 状态
    realtimeSilenceFrames = 0
    realtimeHasSpeech = false
    realtimeAsrPending = false
    realtimeAsrMsgId = null

    // 启动 VAD 定时器（每 200ms）
    realtimeVadTimer = setInterval(() => checkRealtimeVad(), 200)

    console.log('[Realtime] 实时对话模式已启动')
  } catch (err: any) {
    console.error('[Realtime] 启动失败:', err)
    alert(`实时对话启动失败: ${err.message}`)
  }
}

/**
 * 重启实时录音机（每轮 ASR 后调用，确保下一轮音频有完整 WebM 头）
 * 复用已有的 pipelineMediaStream，不重新获取 getUserMedia
 */
function restartRealtimeRecorder(): void {
  if (!pipelineMediaStream) return

  // 停止当前录音机
  if (pipelineMediaRecorder && pipelineMediaRecorder.state === 'recording') {
    pipelineMediaRecorder.stop()
  }

  // 创建新的 MediaRecorder → 新的 WebM 流，包含完整 EBML 头
  const mimeType = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
    ? 'audio/webm;codecs=opus' : 'audio/webm'

  const recorder = new MediaRecorder(pipelineMediaStream, { mimeType })
  pipelineMediaRecorder = recorder

  recorder.ondataavailable = (event) => {
    if (event.data.size > 0 && pipelineAdapter.value) {
      pipelineAdapter.value.sendAudioChunk(event.data)
    }
  }

  recorder.onstop = () => {
    isRecording.value = false
    if (pipelineAdapter.value) {
      pipelineAdapter.value.sendAsrEnd()
    }
  }

  recorder.start(200)
  isRecording.value = true
  console.log('[Realtime] 录音机已重启（新 WebM 流）')
}

/**
 * 停止实时对话模式
 */
function stopRealtimeConversation(): void {
  isRealtimeMode.value = false

  // 停止 VAD
  if (realtimeVadTimer) {
    clearInterval(realtimeVadTimer)
    realtimeVadTimer = null
  }
  if (realtimeAudioCtx) {
    realtimeAudioCtx.close().catch(() => {})
    realtimeAudioCtx = null
  }
  realtimeAnalyser = null
  realtimeHasSpeech = false
  realtimeSilenceFrames = 0
  realtimeAsrPending = false
  realtimeAsrMsgId = null

  // 停止录音
  if (pipelineMediaRecorder && pipelineMediaRecorder.state === 'recording') {
    pipelineMediaRecorder.stop()
  }
  pipelineMediaRecorder = null

  // 停止麦克风流（onstop 中不再自动停止，这里需要手动清理）
  if (pipelineMediaStream) {
    pipelineMediaStream.getTracks().forEach(t => t.stop())
    pipelineMediaStream = null
  }

  currentTranscript.value = ''
  console.log('[Realtime] 实时对话模式已停止')
}

/**
 * 切换实时对话模式
 */
async function toggleRealtimeMode(): Promise<void> {
  if (isRealtimeMode.value) {
    stopRealtimeConversation()
  } else {
    await startRealtimeConversation()
  }
}

/**
 * 开始录音 - 根据配置选择合适的方案
 * 优先级：管线 WS + ASR > 豆包 Realtime API > Whisper ASR > 浏览器原生 Web Speech
 */
const startRecording = async () => {
  // 停止之前的录音 — 如果 AI 正在说话/思考，自动打断
  if (isPlayingAudio.value || isThinking.value) {
    interruptPlayback()
    // 给打断一点传播时间
    await new Promise(r => setTimeout(r, 100))
  }

  window.speechSynthesis?.cancel()
  isPlayingAudio.value = false
  activeVoiceMessageId.value = null
  currentTranscript.value = ''

  // 停止 VAD（用户已主动按下录音键）
  stopAutoInterruptVAD()

  // 方案0 (最高优先)：管线 WebSocket 模式 — 实时音频流式 ASR
  if (practiceMode.value === 'pipeline' && configService.getConfig().enableAiPipeline) {
    // 确保 pipeline WS 已连接
    if (!pipelineWsConnected.value) {
      await connectPipelineWebSocket()
      if (!pipelineWsConnected.value) {
        console.warn('[App] 管线 WS 连接失败，回退到其他方案')
      } else {
        // 连接 ASR 流式 WebSocket
        await startPipelineAsrRecording()
        return
      }
    } else {
      await startPipelineAsrRecording()
      return
    }
  }

  // 方案1：如果语音 AI 已连接，使用真实语音大模型（豆包 Realtime API）
  if (voiceAiReady.value && voiceConnected.value) {
    try {
      await voiceService.startRecording()
      // isRecording 由 onRecordingStateChange 事件更新
    } catch (err: any) {
      console.error('[App] 语音 AI 录音失败:', err)
      alert(`录音启动失败: ${err.message}`)
    }
    return
  }

  // 方案2：使用 Whisper ASR（通过后端 API 转录）
  if (whisperASRService.isSupported()) {
    try {
      // 设置音频输入设备
      whisperASRService.setAudioInputDevice(selectedAudioDeviceId.value)
      
      await whisperASRService.startRecording()
      isWhisperRecording.value = true
      isRecording.value = true
      
      console.log('[App] Whisper ASR 录音开始')
      return
    } catch (err: any) {
      console.error('[App] Whisper ASR 录音失败:', err)
      // Whisper ASR 失败，尝试浏览器原生 API
    }
  }

  // 方案3：使用浏览器原生 Web Speech API
  if (!browserCapabilities.value) {
    browserCapabilities.value = getBrowserCapabilities()
  }
  
  if (browserCapabilities.value.hasSpeechRecognition) {
    startWebSpeechRecognition()
  } else {
    // 浏览器不支持任何语音识别
    if (browserCapabilities.value.isFirefox) {
      alert('Firefox 浏览器不支持语音识别。\n\n请配置 OpenAI API Key 以启用 Whisper 语音识别功能。\n\n或者切换到 Chrome/Edge 浏览器获得更好的语音体验。')
    } else {
      alert('您的浏览器不支持语音识别功能。\n\n请配置 OpenAI API Key 以启用 Whisper 语音识别功能。')
    }
    isTypingMode.value = true
  }
}

/**
 * 启动浏览器原生 Web Speech 识别
 */
const startWebSpeechRecognition = () => {
  const SpeechRecognition =
    (window as any).webkitSpeechRecognition || (window as any).SpeechRecognition

  try {
    const recognition = new SpeechRecognition()
    recognition.continuous = true
    recognition.interimResults = true
    recognition.lang = 'en-US'

    recognition.onstart = () => {
      isRecording.value = true
      currentTranscript.value = ''
    }

    recognition.onresult = (event: any) => {
      let interimTranscript = ''
      let finalTranscript = ''

      for (let i = event.resultIndex; i < event.results.length; ++i) {
        if (event.results[i].isFinal) {
          finalTranscript += event.results[i][0].transcript
        } else {
          interimTranscript += event.results[i][0].transcript
        }
      }

      const transcript = finalTranscript || interimTranscript
      currentTranscript.value = transcript
    }

    recognition.onerror = (e: any) => {
      console.error('Recording error: ', e)
      if (e.error === 'not-allowed') {
        alert('麦克风权限被拒绝。请通过浏览器设置允许此程序访问麦克风。')
      }
      stopRecordingInstance(recognition)
    }

    recognition.onend = () => {
      isRecording.value = false
    }

    recognition.start()
    recognitionInstance.value = recognition
    console.log('[App] Web Speech 识别开始')
  } catch (err) {
    console.error('[App] Web Speech 启动失败:', err)
    isRecording.value = false
    alert('语音识别启动失败，请使用键盘输入模式。')
  }
}

const stopRecordingInstance = (instance: any) => {
  if (instance) {
    try {
      instance.stop()
    } catch (err) {
      console.error(err)
    }
  }
  isRecording.value = false
}

const handleStopRecording = async () => {
  // 方案0：管线 WebSocket 模式 — 停止 MediaRecorder
  if (practiceMode.value === 'pipeline' && pipelineMediaRecorder && pipelineMediaRecorder.state !== 'inactive') {
    isRecording.value = false
    pipelineMediaRecorder.stop()
    // onstop 回调中会处理后续 ASR + pipeline 提交
    return
  }

  // 方案1：使用语音大模型（豆包 Realtime API）
  if (voiceAiReady.value && voiceConnected.value) {
    await voiceService.stopRecording()
    // isRecording 和 isThinking 由事件处理器更新
    currentTranscript.value = ''
    return
  }

  // 方案2：使用 Whisper ASR - 需要停止录音并转录
  if (isWhisperRecording.value) {
    isWhisperRecording.value = false
    isRecording.value = false
    
    try {
      const audioBlob = await whisperASRService.stopRecording()
      
      if (!audioBlob || audioBlob.size === 0) {
        alert('没有录制到音频数据，请重试！')
        return
      }
      
      console.log('[App] Whisper ASR 录音已停止，开始转录...')
      
      // 调用 Whisper 转录
      const result = await whisperASRService.transcribe(audioBlob, { language: 'en' })
      
      console.log('[App] Whisper 转录成功:', result.text)
      handleUserAnswerSubmit(result.text.trim())
    } catch (err: any) {
      console.error('[App] Whisper 转录失败:', err)
      alert(`转录失败: ${err.message}\n请重试或使用键盘输入！`)
    }
    return
  }

  // 方案3：使用浏览器原生 Web Speech API
  if (recognitionInstance.value) {
    stopRecordingInstance(recognitionInstance.value)
  }

  const transcriptToSubmit = currentTranscript.value.trim()
  if (transcriptToSubmit.length > 2) {
    handleUserAnswerSubmit(transcriptToSubmit)
  } else {
    alert('没有检测到清晰的英语演说，请按住麦克风再说一遍，或选择键盘输入！')
  }
  currentTranscript.value = ''
}

const generateLexicalCorrection = (text: string, questionKeywords: Scenario['questions'][0]['keywords']): GrammarFeedback | undefined => {
  const textLower = text.toLowerCase()

  if (questionKeywords && questionKeywords.length > 0) {
    for (const item of questionKeywords) {
      if (textLower.includes(item.phrase.toLowerCase())) {
        return {
          original: item.phrase,
          suggested: item.suggested,
          title: '建议优化表达',
          explanation: item.explanation
        }
      }
    }
  }

  if (textLower.includes('i have been working') && currentScenario.value.id === 'frontend' && currentQuestionIndex.value === 0) {
    return {
      original: "I have been working as a frontend developer...",
      suggested: "I bring three years of dedicated frontend experience, specializing in...",
      title: "建议优化表达",
      explanation: '"I have been working as a frontend developer..." 非常好。为了展现更强的专业性，可以尝试说: "I bring three years of dedicated frontend experience, specializing in..."'
    }
  }

  if (textLower.includes('well done') || textLower.includes('steak')) {
    return {
      original: "well done / i want steak",
      suggested: "I prefer it medium-rare to maintain the tenderness...",
      title: "建议优化表达",
      explanation: '在点西餐牛排时，直接说 "I want steak" 稍显生硬，用优雅的 "I would like to order... cooked medium-rare" 更加地道！'
    }
  }

  if (textLower.includes('easy') || textLower.includes('like')) {
    return {
      original: "easy to use / like to write code",
      suggested: "highly intuitive / passionate about architectural scaling and design integration",
      title: "建议优化表达",
      explanation: '避免在面试中使用 simple / easy 这样的常用轻量词。替换为"高度直观、对构建具备高可扩展性的架构底座极具热忱"，更显团队骨干深度！'
    }
  }

  if (textLower.includes('think') || textLower.includes('maybe')) {
    return {
      original: "I think the project is...",
      suggested: "Based on concrete metrics and telemetry, we evaluated that...",
      title: "建议优化表达",
      explanation: '用"基于具体的数据指标与遥测跟踪分析，我们客观评估得出..." 来代替带有主观犹豫色彩的 "I think"（我想），能瞬间将工程理性体现得淋漓尽致。'
    }
  }

  return {
    original: text.split(' ').slice(0, 4).join(' ') + "...",
    suggested: "I possess high-performance proficiency across software paradigms...",
    title: "词汇广度提升",
    explanation: '句型架构完整！若能融入如 "spearhead" (带头、发起)、"paradigm" (范式流派)、"robustness" (稳健鲁棒性) 等行业动词词组，会使回答听上去分量十足。'
  }
}

/**
 * 播放 TTS 音频 URL（从后端获取）
 */
const playTtsAudio = async (ttsUrl: string, messageId: string) => {
  try {
    const baseUrl = configService.getConfig().backendUrl || 'http://localhost:8080'
    const audio = new Audio(`${baseUrl}${ttsUrl}`)
    audio.onplay = () => {
      isPlayingAudio.value = true
      activeVoiceMessageId.value = messageId
    }
    audio.onended = () => {
      isPlayingAudio.value = false
      activeVoiceMessageId.value = null
    }
    audio.onerror = () => {
      console.error('TTS playback error')
      isPlayingAudio.value = false
      activeVoiceMessageId.value = null
    }
    await audio.play()
  } catch (err) {
    console.error('TTS playback failed:', err)
    isPlayingAudio.value = false
    activeVoiceMessageId.value = null
  }
}

/**
 * 使用 WebSocket 管线处理用户消息（流式 LLM + Token 级 TTS）
 */
const handlePipelineWsSubmit = async (text: string) => {
  const config = configService.getConfig()

  if (!currentSessionId.value) {
    currentSessionId.value = 'pipeline-' + Date.now()
  }

  // 添加用户消息
  const minutesAdded = messages.value.length + 1
  const timeString = `10:${minutesAdded < 10 ? '0' + minutesAdded : minutesAdded}`
  statusTime.value = timeString

  const userMsg: DialectMessage = {
    id: 'usr_' + Date.now(),
    role: 'user',
    text: text,
    timestamp: timeString,
  }
  messages.value = [...messages.value, userMsg]

  // 持久化：保存用户消息到后端数据库
  if (USE_API_MODE.value && currentSessionId.value) {
    api.saveMessage(
      currentSessionId.value,
      text,
      false,
      undefined,
      undefined,
      'user'
    ).catch(err => console.error('[Pipeline] 保存用户消息失败:', err))
  }

  // AI 正在思考
  isThinking.value = true

  // 构建对话历史并发送到管线 WS
  const history = buildConversationHistory()
  console.log('[Pipeline] 发送用户输入, history:', history.length, '条')
  pipelineAdapter.value?.sendUserInput(text, history)
}

/**
 * 使用 AI Pipeline（ASR→LLM→TTS）处理用户消息
 */
const handleAiPipelineSubmit = async (text: string) => {
  const config = configService.getConfig()
  const sessionId = currentSessionId.value || 'ai-practice-' + Date.now()

  if (!currentSessionId.value) {
    currentSessionId.value = sessionId
  }

  // 添加用户消息
  const minutesAdded = messages.value.length + 1
  const timeString = `10:${minutesAdded < 10 ? '0' + minutesAdded : minutesAdded}`
  statusTime.value = timeString

  const userMsg: DialectMessage = {
    id: 'usr_' + Date.now(),
    role: 'user',
    text: text,
    timestamp: timeString,
  }
  messages.value = [...messages.value, userMsg]

  // 持久化：保存用户消息到后端数据库
  if (USE_API_MODE.value && currentSessionId.value) {
    api.saveMessage(
      currentSessionId.value,
      text,
      false,
      undefined,
      undefined,
      'user'
    ).catch(err => console.error('[SSE] 保存用户消息失败:', err))
  }

  // 根据引擎获取对应的 API Key 和 Base URL
  let apiKey = config.apiKey;
  let baseUrl = '';

  switch (config.pipelineLlmEngine) {
    case 'deepseek':
      apiKey = config.deepseekApiKey;
      baseUrl = 'https://api.deepseek.com';
      break;
    case 'glm':
      apiKey = config.glmApiKey;
      baseUrl = config.glmApiUrl || 'https://open.bigmodel.cn/api/paas/v4';
      break;
    case 'qianwen':
      apiKey = config.qianwenApiKey;
      baseUrl = config.qianwenApiUrl || 'https://dashscope.aliyuncs.com/compatible-mode/v1';
      break;
    case 'openai':
    default:
      apiKey = config.apiKey;
      baseUrl = 'https://api.openai.com/v1';
      break;
  }

  isThinking.value = true

  const nextTimeMinutes = minutesAdded + 1
  const nextTimeString = `10:${nextTimeMinutes < 10 ? '0' + nextTimeMinutes : nextTimeMinutes}`

  // 创建 AbortController 用于打断
  const controller = new AbortController()
  abortController.value = controller

  try {
    // 使用流式 API - GET 请求，query 参数传递
    const params = new URLSearchParams({
      sessionId,
      text,
      agentName: config.pipelineAgentName,
      llmEngine: config.pipelineLlmEngine,
      llmModel: config.pipelineLlmModel,
      llmApiKey: apiKey,
      llmBaseUrl: baseUrl,
      useTts: config.pipelineUseTts ? 'true' : 'false',
      ttsEngine: config.pipelineTtsEngine,
      ttsVoice: config.pipelineTtsVoice,
    })
    const streamUrl = `${API_BASE_URL}/api/practice/chat/stream?${params}`
    console.log('[App] Stream request URL:', streamUrl)

    const response = await fetch(streamUrl, { signal: controller.signal })
    console.log('[App] Stream response status:', response.status, response.statusText)

    if (!response.ok) {
      throw new Error(`Stream request failed: ${response.status} ${response.statusText}`)
    }

    const reader = response.body?.getReader()
    if (!reader) {
      throw new Error('No response body')
    }

    const decoder = new TextDecoder()
    let buffer = ''
    let fullText = ''
    let aiMsgCreated = false
    let aiMsgId = ''
    let chunkCount = 0

    isThinking.value = false

    while (true) {
      const { done, value } = await reader.read()
      if (done) {
        console.log('[App] Stream done, total chunks:', chunkCount, 'fullText length:', fullText.length)
        break
      }

      const chunk = decoder.decode(value, { stream: true })
      chunkCount++
      console.log(`[App] Stream chunk #${chunkCount}, raw:`, chunk.substring(0, 200))

      buffer += chunk

      // 处理 SSE 格式的数据
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        const trimmed = line.trim()
        if (!trimmed) continue

        // 处理 SSE data 行，兼容 "data:xxx" 和 "data: xxx" 两种格式
        if (trimmed.startsWith('data:')) {
          let data
          if (trimmed.startsWith('data: ')) {
            data = trimmed.slice(6).trim()
          } else {
            data = trimmed.slice(5).trim()
          }
          if (data && data !== '[DONE]') {
            try {
              const json = JSON.parse(data)
              const content = json.choices?.[0]?.delta?.content || json.text
              if (content) {
                fullText += content
                console.log('[App] Received content chunk:', content)
              }
            } catch (e) {
              // 如果不是 JSON，当做纯文本处理
              fullText += data
              console.log('[App] Non-JSON data:', data.substring(0, 100))
            }

            // 创建 AI 消息（仅第一次收到数据时）
            if (!aiMsgCreated && fullText) {
              aiMsgId = 'ai_' + Date.now()
              const aiMsg: DialectMessage = {
                id: aiMsgId,
                role: 'ai',
                text: fullText,
                timestamp: nextTimeString,
                showTranslation: subtitlesOn.value,
              }
              messages.value = [...messages.value, aiMsg]
              aiMsgCreated = true
              console.log('[App] Created AI message:', aiMsgId)
            } else if (aiMsgCreated) {
              // 更新现有消息
              const msgIndex = messages.value.findIndex(m => m.id === aiMsgId)
              if (msgIndex !== -1) {
                messages.value[msgIndex].text = fullText
                messages.value = [...messages.value]
              }
            }
          }
        } else {
          console.log('[App] Non-data line:', trimmed.substring(0, 100))
        }
      }
    }

    // 如果没有收到任何内容
    if (!fullText.trim()) {
      if (!aiMsgCreated) {
        aiMsgId = 'ai_' + Date.now()
        messages.value = [...messages.value, {
          id: aiMsgId,
          role: 'ai',
          text: 'No response received. Please try again.',
          timestamp: nextTimeString,
          showTranslation: subtitlesOn.value,
        }]
      } else {
        const msgIndex = messages.value.findIndex(m => m.id === aiMsgId)
        if (msgIndex !== -1) {
          messages.value[msgIndex].text = 'No response received. Please try again.'
        }
      }
    }

    // 拆分英文和中文翻译（LLM 按要求返回的 "英文|||中文" 格式）
    const { text: cleanText, translation } = splitTextAndTranslation(fullText)
    if (translation) {
      // 更新消息，拆分为 text 和 translation
      const msgIdx = messages.value.findIndex(m => m.id === aiMsgId)
      if (msgIdx !== -1) {
        messages.value[msgIdx].text = cleanText
        messages.value[msgIdx].translation = translation
        messages.value = [...messages.value]
      }
    }

    // 持久化：保存 AI 回复到后端数据库
    if (USE_API_MODE.value && currentSessionId.value && cleanText) {
      api.saveMessage(
        currentSessionId.value,
        cleanText,
        false,
        undefined,
        undefined,
        'assistant'
      ).catch(err => console.error('[SSE] 保存AI消息失败:', err))
    }

    // 流式响应完成后，自动使用 TTS 朗读 AI 回复
    if (cleanText.trim() && config.pipelineUseTts) {
      setTimeout(() => {
        const ttsEngine = config.pipelineTtsEngine
        // 使用本地 TTS 服务（Piper/Edge-TTS）
        if (ttsEngine === 'piper' || ttsEngine === 'edge-tts') {
          playLocalTtsAudio(cleanText, aiMsgId)
        } else {
          // 使用浏览器原生 SpeechSynthesis 或 OpenAI TTS
          speakAudio(cleanText, aiMsgId)
        }
      }, 300)
    }
  } catch (error) {
    console.error('AI Pipeline failed:', error)
    isThinking.value = false

    const aiMsgId = 'ai_' + Date.now()
    messages.value = [...messages.value, {
      id: aiMsgId,
      role: 'ai',
      text: 'Sorry, I encountered an error. Please try again or check your API configuration.',
      timestamp: `10:${minutesAdded + 1}`,
      showTranslation: subtitlesOn.value,
    }]
  }
}

// AbortController 用于取消 REST SSE 请求
const abortController = ref<AbortController | null>(null)

/**
 * 打断 AI 播放/思考 — 全场景通用
 * 用于打断按钮和自动 VAD 触发
 */
const interruptPlayback = () => {
  if (practiceMode.value === 'realtime' && (voiceConnected.value || voiceAiReady.value)) {
    // 端到端模式：通过语音服务打断
    voiceService.interrupt()
  } else if (practiceMode.value === 'pipeline') {
    if (pipelineAdapter.value) {
      // WebSocket 管线模式：通过专用 pipelineAdapter 打断
      pipelineAdapter.value.interrupt()
    } else if (voiceService.isPipelineMode) {
      // 降级路径
      voiceService.interrupt()
    } else {
      // REST SSE 模式：取消 fetch，停止所有播放
      abortController.value?.abort()
    }
  }

  // 通用清理
  window.speechSynthesis?.cancel()
  isPlayingAudio.value = false
  activeVoiceMessageId.value = null
  isThinking.value = false
  isRecording.value = false

  console.log('[App] 已打断 AI 播放')
}

const handleUserAnswerSubmit = async (text: string) => {
  // 端到端（实时语音）模式：使用语音大模型
  if (practiceMode.value === 'realtime') {
    if (voiceAiReady.value && voiceConnected.value) {
      await sendTextToVoiceAI(text)
      return
    }
  }

  // 管线模式：走 WebSocket ASR-LLM-TTS 流程（优先）
  if (practiceMode.value === 'pipeline' && pipelineWsConnected.value && pipelineAdapter.value) {
    await handlePipelineWsSubmit(text)
    return
  }

  // 管线模式：REST SSE 回退
  if (practiceMode.value === 'pipeline' && configService.getConfig().enableAiPipeline) {
    await handleAiPipelineSubmit(text)
    return
  }

  window.speechSynthesis?.cancel()
  isPlayingAudio.value = false
  activeVoiceMessageId.value = null

  const minutesAdded = messages.value.length + 1
  const timeString = `10:${minutesAdded < 10 ? '0' + minutesAdded : minutesAdded}`
  statusTime.value = timeString

  let feedbackResult: GrammarFeedback | undefined
  if (currentQuestionIndex.value === 0) {
    feedbackResult = generateLexicalCorrection(text, [])
  } else {
    const activeQ = currentScenario.value.questions[currentQuestionIndex.value - 1]
    feedbackResult = generateLexicalCorrection(text, activeQ ? activeQ.keywords : [])
  }

  const userMsg: DialectMessage = {
    id: 'usr_' + Date.now(),
    role: 'user',
    text: text,
    timestamp: timeString,
    feedback: feedbackResult
  }

  messages.value = [...messages.value, userMsg]

  // 如果启用 API 模式，保存消息到后端
  if (USE_API_MODE.value && currentSessionId.value) {
    try {
      await api.saveMessage(
        currentSessionId.value,
        text,
        false,
        currentRating.value?.overall,
        feedbackResult
      )
    } catch (error) {
      console.error('保存消息失败:', error)
    }
  }

  const mockRatingOption = MOCK_RATING_DATABASE[Math.floor(Math.random() * MOCK_RATING_DATABASE.length)]
  const generatedScore: PronunciationScore = {
    overall: Math.min(100, Math.max(75, (mockRatingOption.overall as number) + Math.floor(Math.random() * 5) - 2)),
    accuracy: Math.min(100, Math.max(75, (mockRatingOption.accuracy as number) + Math.floor(Math.random() * 5) - 2)),
    fluency: Math.min(100, Math.max(75, (mockRatingOption.fluency as number) + Math.floor(Math.random() * 5) - 4)),
    grammar: Math.min(100, Math.max(75, (mockRatingOption.grammar as number) + Math.floor(Math.random() * 4) - 2)),
    feedbackSummary: mockRatingOption.summary as string
  }
  currentRating.value = generatedScore

  isThinking.value = true

  const nextIndex = currentQuestionIndex.value + 1
  setTimeout(() => {
    isThinking.value = false

    let aiResponseText = ''
    let aiTranslationText = ''

    const isFinish = nextIndex > currentScenario.value.questions.length

    if (isFinish) {
      aiResponseText = "Congratulations! You have completed all of the active interactive prompts in this scenario. We have evaluated your complete acoustic rhythm. Let's transition to your performance summary study plan immediately."
      aiTranslationText = "恭喜你！你完成了我们今天练习场景中的所有对话节点。我已经全面评估整理了你的流利度与语篇时态细节，让我们立即为您生成课后总结分析和行动计划！"

      setTimeout(() => {
        currentSummaryResult.value = null // mock 模式无 LLM 评估
        currentView.value = 'summary'
      }, 2200)
    } else {
      const nextQ = currentScenario.value.questions[nextIndex - 1]
      if (nextQ) {
        aiResponseText = nextQ.text
        aiTranslationText = nextQ.translation
      }
    }

    const nextTimeMinutes = minutesAdded + 1
    const nextTimeString = `10:${nextTimeMinutes < 10 ? '0' + nextTimeMinutes : nextTimeMinutes}`
    statusTime.value = nextTimeString

    const aiMsg: DialectMessage = {
      id: 'ai_' + Date.now(),
      role: 'ai',
      text: aiResponseText,
      translation: aiTranslationText,
      timestamp: nextTimeString,
      showTranslation: subtitlesOn.value
    }

    messages.value = [...messages.value, aiMsg]
    currentQuestionIndex.value = nextIndex

    // 持久化：保存 AI 回复（基本文本模式）
    if (USE_API_MODE.value && currentSessionId.value) {
      api.saveMessage(
        currentSessionId.value,
        aiResponseText,
        false,
        undefined,
        undefined,
        'assistant'
      ).catch(err => console.error('保存AI消息失败:', err))
    }

    setTimeout(() => {
      handleManualSpeak(aiResponseText, aiMsg.id)
    }, 400)

  }, 2000)
}

const handleGlobalToggleSubtitles = () => {
  const targetState = !subtitlesOn.value
  subtitlesOn.value = targetState
  messages.value = messages.value.map((msg) =>
    msg.role === 'ai' ? { ...msg, showTranslation: targetState } : msg
  )
}

/**
 * 重置场景 - 处理语音 AI 重连
 */
const handleResetScenario = async () => {
  if (voiceConnected.value) {
    disconnectVoiceAI()
    await new Promise(r => setTimeout(r, 300))
  }
  resetConversationForScenario(currentScenario.value)
  if (shouldUseVoiceAI.value && currentView.value === 'practice') {
    await connectVoiceAI()
  }
}

const handleSelectScenario = async (sc: Scenario) => {
  // 如果已在连接中，先断开
  if (voiceConnected.value) {
    disconnectVoiceAI()
  }

  // 获取场景数据
  if (USE_API_MODE.value) {
    try {
      const fullScenario = await api.getScenarioById(sc.id)
      currentScenario.value = fullScenario
    } catch (error) {
      console.error('获取场景详情失败:', error)
      currentScenario.value = sc
    }
  } else {
    currentScenario.value = sc
  }
  resetConversationForScenario(sc)

  // 弹出模式选择窗口，而不是直接进入练习
  showModeSelector.value = true
}

/**
 * 用户选择了练习模式后的回调
 */
const handlePracticeModeSelect = async (mode: 'pipeline' | 'realtime') => {
  showModeSelector.value = false
  practiceMode.value = mode

  // 切换到练习视图
  currentView.value = 'practice'

  // 端到端模式需要连接语音大模型
  if (mode === 'realtime' && shouldUseVoiceAI.value) {
    await connectVoiceAI()
  }

  // 创建后端会话
  if (USE_API_MODE.value) {
    try {
      const llmConfig = resolveLlmPipelineConfig()
      const conversation = await api.createConversation(currentScenario.value.id, undefined, {
        useAsr: false,
        useTts: false,
        llmEngine: llmConfig.llmEngine,
        llmModel: llmConfig.llmModel,
        llmApiKey: llmConfig.llmApiKey,
        llmBaseUrl: llmConfig.llmBaseUrl,
        llmTemperature: 0.7,
        ttsEngine: 'openai',
        ttsModel: 'tts-1',
        ttsVoice: 'alloy'
      })
      currentSessionId.value = conversation.sessionId
    } catch (error) {
      console.error('创建会话失败:', error)
    }
  }

  await loadConversations()
}

const handleViewChange = async (view: 'scenarios' | 'practice' | 'summary' | 'settings' | 'history') => {
    window.speechSynthesis?.cancel()
    isPlayingAudio.value = false
    activeVoiceMessageId.value = null

    // 离开练习视图时断开管线 WS
    if (currentView.value === 'practice' && view !== 'practice') {
      disconnectPipelineWebSocket()
    }

    // 先切换视图
    currentView.value = view

    // 切换到历史记录视图时刷新列表
    if (view === 'history') {
      await loadConversations()
    }

    // 切换到练习视图
    if (view === 'practice') {
      // 管线模式：连接 WebSocket 管线
      if (practiceMode.value === 'pipeline' && configService.getConfig().enableAiPipeline) {
        connectPipelineWebSocket()
      }
      // 端到端模式：连接语音大模型
      if (shouldUseVoiceAI.value) {
        await connectVoiceAI()
      }

      // 创建后端会话
      if (USE_API_MODE.value) {
        try {
          const llmConfig = resolveLlmPipelineConfig()
          const conversation = await api.createConversation(currentScenario.value.id, undefined, {
            useAsr: false,
            useTts: false,
            llmEngine: llmConfig.llmEngine,
            llmModel: llmConfig.llmModel,
            llmApiKey: llmConfig.llmApiKey,
            llmBaseUrl: llmConfig.llmBaseUrl,
            llmTemperature: 0.7,
            ttsEngine: 'openai',
            ttsModel: 'tts-1',
            ttsVoice: 'alloy'
          })
          currentSessionId.value = conversation.sessionId
          console.log('创建会话成功:', conversation.sessionId)
        } catch (error) {
          console.error('创建会话失败:', error)
        }
      }
      
      // 创建完会话后刷新列表
      await loadConversations()
    } else if (view === 'history') {
      // 切换到历史记录视图时也加载列表
      await loadConversations()
    }

    // 离开练习视图
    if (view !== 'practice' && currentSessionId.value) {
      // 断开语音大模型
      disconnectVoiceAI()

      // 停止 VAD
      stopAutoInterruptVAD()

      // 结束后端会话
      try {
        await api.endConversation(currentSessionId.value)
        console.log('会话已结束')
        await loadConversations() // 结束后也刷新列表
      } catch (error) {
        console.error('结束会话失败:', error)
      }
      currentSessionId.value = null
    }
}

// ==================== 真实语音大模型集成 ====================

/**
 * 设置语音服务事件处理器
 */
let voiceEventHandlersSetup = false
function setupVoiceEventHandlers() {
  if (voiceEventHandlersSetup) return
  voiceEventHandlersSetup = true

  voiceService.on('onConnected', () => {
    console.log('[App] 语音大模型已连接')
    voiceConnected.value = true
    voiceConnecting.value = false
    voiceAiReady.value = true
  })

  voiceService.on('onDisconnected', (error?: Error) => {
    console.log('[App] 语音大模型已断开', error?.message)
    voiceConnected.value = false
    voiceAiReady.value = false
    voiceConnecting.value = false
  })

  voiceService.on('onTranscript', (event: TranscriptEvent) => {
    console.log('[App] 收到转写事件:', event.role, event.final, event.text.substring(0, 50))

    if (event.role === 'user' && event.final && event.text.trim()) {
      // 用户语音转写完成 - 添加用户消息
      const minutesAdded = messages.value.length + 1
      const timeString = `10:${minutesAdded < 10 ? '0' + minutesAdded : minutesAdded}`
      statusTime.value = timeString

      const userMsg: DialectMessage = {
        id: 'usr_' + Date.now(),
        role: 'user',
        text: event.text,
        timestamp: timeString
      }
      messages.value = [...messages.value, userMsg]

      // AI 正在思考
      isThinking.value = true
    }

    if (event.role === 'ai') {
      if (event.final && event.text.trim()) {
        // AI 最终转写完成
        isThinking.value = false
        voiceAiInterimText.value = ''

        const minutesAdded = messages.value.length + 1
        const timeString = `10:${minutesAdded < 10 ? '0' + minutesAdded : minutesAdded}`
        statusTime.value = timeString

        const aiMsg: DialectMessage = {
          id: 'ai_' + Date.now(),
          role: 'ai',
          text: event.text,
          translation: event.translation,
          timestamp: timeString,
          showTranslation: subtitlesOn.value
        }
        messages.value = [...messages.value, aiMsg]
        pendingAiMessageId.value = null
      } else if (!event.final && event.text) {
        // AI 中间转写 - 暂存用于显示
        voiceAiInterimText.value = event.text
        isThinking.value = true
      }
    }
  })

  voiceService.on('onAudioStart', () => {
    console.log('[App] AI 开始语音播放')
    isPlayingAudio.value = true
    isThinking.value = false
  })

  voiceService.on('onAudioEnd', () => {
    console.log('[App] AI 语音播放结束')
    isPlayingAudio.value = false
    activeVoiceMessageId.value = null
  })

  voiceService.on('onError', (error: Error) => {
    console.error('[App] 语音服务错误:', error.message)
    voiceConnecting.value = false
    voiceAiReady.value = false
  })

  voiceService.on('onRecordingStateChange', (recording: boolean) => {
    isRecording.value = recording
    if (!recording) {
      // 停止录音后，AI 正在处理
      isThinking.value = true
    }
  })
}

/**
 * 连接语音大模型
 */
async function connectVoiceAI() {
  if (voiceConnected.value || voiceConnecting.value) return

  voiceConnecting.value = true
  setupVoiceEventHandlers()

  try {
    await voiceService.connect(currentScenario.value)
    console.log('[App] 语音大模型连接成功')
  } catch (error: any) {
    console.error('[App] 连接语音大模型失败:', error)
    voiceConnecting.value = false
    voiceAiReady.value = false
    alert(`连接语音大模型失败: ${error.message || '请检查配置和网络连接'}`)
  }
}

/**
 * 断开语音大模型
 */
function disconnectVoiceAI() {
  if (!voiceConnected.value) return
  voiceService.disconnect()
  voiceConnected.value = false
  voiceAiReady.value = false
  voiceAiInterimText.value = ''
  pendingAiMessageId.value = null
}

// ==================== 管线 WebSocket 模式 ====================

/**
 * 设置管线 WebSocket 事件处理器
 */
function setupPipelineEventHandlers() {
  const adapter = pipelineAdapter.value
  if (!adapter) return

  // Track the current streaming message ID
  let currentStreamMsgId: string | null = null

  adapter.on('transcript', (event: any) => {
    if (event.role === 'ai') {
      if (event.final && event.text) {
        if (event.text === '[已打断]') return
        // 拆分英文和中文翻译
        const { text: cleanText, translation } = splitTextAndTranslation(event.text)
        // LLM 完成 — 更新或替换流式消息为最终消息
        const minutesAdded = messages.value.length + 1
        const timeString = `10:${minutesAdded < 10 ? '0' + minutesAdded : minutesAdded}`
        statusTime.value = timeString

        // 如果之前有流式消息，替换它
        const streamIndex = messages.value.findIndex(m => m.id === currentStreamMsgId)
        if (streamIndex !== -1) {
          const updated = [...messages.value]
          updated[streamIndex] = {
            ...updated[streamIndex],
            id: 'ai_' + Date.now(),
            text: cleanText,
            translation: translation,
            showTranslation: subtitlesOn.value,
          }
          messages.value = updated
        } else {
          // 没有流式消息，新建
          messages.value = [...messages.value, {
            id: 'ai_' + Date.now(),
            role: 'ai',
            text: cleanText,
            translation: translation,
            timestamp: timeString,
            showTranslation: subtitlesOn.value,
          }]
        }
        currentStreamMsgId = null
        isThinking.value = false

        // 持久化：保存 AI 回复到后端数据库
        if (USE_API_MODE.value && currentSessionId.value && event.text) {
          api.saveMessage(
            currentSessionId.value,
            event.text,
            false,
            undefined,
            undefined,
            'assistant'
          ).catch(err => console.error('[Pipeline] 保存AI消息失败:', err))
        }
      } else if (!event.final && event.text) {
        // LLM token — 追加到流式消息（打字机效果）
        if (currentStreamMsgId) {
          // 已有流式消息，追加内容
          const idx = messages.value.findIndex(m => m.id === currentStreamMsgId)
          if (idx !== -1) {
            messages.value[idx].text += event.text
            messages.value = [...messages.value]
          } else {
            // 流式消息被删除了，新建
            currentStreamMsgId = null
          }
        }

        if (!currentStreamMsgId) {
          // 创建新的流式消息
          const minutesAdded = messages.value.length + 1
          const timeString = `10:${minutesAdded < 10 ? '0' + minutesAdded : minutesAdded}`
          const tempMsg: DialectMessage = {
            id: 'ai_stream_' + Date.now(),
            role: 'ai',
            text: event.text,
            timestamp: timeString,
          }
          currentStreamMsgId = tempMsg.id
          messages.value = [...messages.value, tempMsg]
        }
        isThinking.value = false
      }
    } else if (event.role === 'user') {
      if (isRealtimeMode.value) {
        // ===== 实时对话模式：ASR 结果流式输出到用户对话框 =====
        if (!realtimeAsrMsgId) {
          // 没有占位消息时忽略（例如打断后）
          currentTranscript.value = event.text || ''
          return
        }

        const idx = messages.value.findIndex(m => m.id === realtimeAsrMsgId)
        if (idx === -1) {
          realtimeAsrMsgId = null
          return
        }

        if (event.final) {
          // 最终结果 → 更新消息 + 触发 LLM
          const text = (event.text || '').trim()
          const msgId = realtimeAsrMsgId
          realtimeAsrMsgId = null
          realtimeAsrPending = false

          if (text.length > 2) {
            messages.value[idx].text = text
            messages.value = [...messages.value]
            currentTranscript.value = ''
            console.log('[Realtime] ASR 最终结果:', text)
            // 直接触发 LLM 管线（不额外添加用户消息，已在对话中）
            isThinking.value = true
            const history = buildConversationHistory()
            pipelineAdapter.value?.sendUserInput(text, history)
            // ASR 完成后重启录音机（新 WebM 头），用户可随时说话打断 TTS
            restartRealtimeRecorder()
          } else {
            // 识别为空 → 移除占位消息
            console.warn('[Realtime] ASR 结果太短或为空，移除占位')
            messages.value = messages.value.filter(m => m.id !== msgId)
            isThinking.value = false
            // 空结果也重启录音机，继续监听
            restartRealtimeRecorder()
          }
        } else {
          // 中间结果 → 追加文本
          messages.value[idx].text = (messages.value[idx].text || '') + (event.text || '')
          messages.value = [...messages.value]
        }
      } else {
        // ===== 非实时模式（手动录音） =====
        if (event.final && event.text) {
          currentTranscript.value = ''
          const text = event.text.trim()
          if (text.length > 2) {
            console.log('[Pipeline] ASR 识别结果:', text)
            handleUserAnswerSubmit(text)
          } else {
            console.warn('[Pipeline] ASR 结果太短:', text)
            alert('没有检测到清晰的语音，请重试或使用键盘输入！')
            isThinking.value = false
          }
        } else if (!event.final && event.text) {
          currentTranscript.value = event.text
        }
      }
    }
  })

  adapter.on('audiostart', () => {
    console.log('[Pipeline] TTS 音频开始播放')
    isPlayingAudio.value = true
    isThinking.value = false
  })

  adapter.on('audioend', () => {
    console.log('[Pipeline] TTS 音频播放结束')
    isPlayingAudio.value = false
    activeVoiceMessageId.value = null
  })

  adapter.on('recordingstatechange', (recording: boolean) => {
    isRecording.value = recording
  })

  adapter.on('error', (error: Error) => {
    console.error('[Pipeline] 管线错误:', error.message)
    isThinking.value = false
  })

  adapter.on('connected', () => {
    pipelineWsConnected.value = true
    console.log('[Pipeline] WS 连接成功')
  })

  adapter.on('disconnected', () => {
    pipelineWsConnected.value = false
    console.log('[Pipeline] WS 断开')
  })
}

/**
 * 连接管线 WebSocket
 */
async function connectPipelineWebSocket() {
  if (pipelineWsConnected.value) return
  if (pipelineAdapter.value) {
    disconnectPipelineWebSocket()
  }

  const config = configService.getConfig()
  const backendUrl = config.backendUrl || 'http://localhost:8080'

  // 获取用户选中的 LLM 引擎对应的 API Key 和 Base URL
  let llmApiKey = ''
  let llmBaseUrl = ''
  const llmEngine = config.pipelineLlmEngine || 'openai'
  const llmModel = config.pipelineLlmModel || ''

  switch (llmEngine) {
    case 'deepseek':
      llmApiKey = config.deepseekApiKey || ''
      llmBaseUrl = 'https://api.deepseek.com/v1'
      break
    case 'glm':
      llmApiKey = config.glmApiKey || ''
      llmBaseUrl = config.glmApiUrl || 'https://open.bigmodel.cn/api/paas/v4'
      break
    case 'qianwen':
      llmApiKey = config.qianwenApiKey || ''
      llmBaseUrl = config.qianwenApiUrl || 'https://dashscope.aliyuncs.com/compatible-mode/v1'
      break
    case 'doubao':
      llmApiKey = config.apiKey || ''
      llmBaseUrl = 'https://api.doubao.com/v1'
      break
    case 'openai':
    default:
      llmApiKey = config.apiKey || ''
      llmBaseUrl = 'https://api.openai.com/v1'
      break
  }

  const adapter = new PipelineWebSocketAdapter({
    apiKey: llmApiKey,
    appId: '',
    modelProvider: 'pipeline',
    backendUrl,
    sampleRate: 16000,
    audioChunkSize: 100,
    audioInputDeviceId: config.audioInputDeviceId || '',
    vadEnabled: false,
    vadThreshold: 0.5,
    vadSilenceDuration: 800,
    modelConfig: {
      llmEngine,
      llmModel,
      llmApiKey,
      llmBaseUrl
    }
  })

  pipelineAdapter.value = adapter
  setupPipelineEventHandlers()

  try {
    await adapter.connect(currentScenario.value)
    pipelineWsConnected.value = true
    console.log('[Pipeline] 连接成功, engine=', llmEngine, 'model=', llmModel)
  } catch (err: any) {
    pipelineWsConnected.value = false
    console.error('[Pipeline] 连接失败:', err.message)
  }
}

/**
 * 断开管线 WebSocket
 */
function disconnectPipelineWebSocket() {
  if (pipelineAdapter.value) {
    pipelineAdapter.value.disconnect()
    pipelineAdapter.value = null
  }
  pipelineWsConnected.value = false
  pipelineMediaRecorder = null
  pipelineMediaStream = null
  pipelineAudioChunks = []
  asrWebSocket = null
}

/**
 * 从当前消息列表构建对话历史
 */
function buildConversationHistory(): Array<{role: string; content: string}> {
  const history: Array<{role: string; content: string}> = []
  for (const msg of messages.value) {
    if (msg.role === 'user') {
      history.push({ role: 'user', content: msg.text })
    } else if (msg.role === 'ai' || msg.role === 'assistant') {
      history.push({ role: 'assistant', content: msg.text })
    }
  }
  return history
}

/**
 * 拆分 LLM 返回的"英文|||中文"格式文本
 * 如果 LLM 遵循了提示词要求，返回 { text, translation }
 * 否则返回 { text, translation: undefined }
 */
function splitTextAndTranslation(raw: string): { text: string; translation?: string } {
  const idx = raw.indexOf('|||')
  if (idx !== -1) {
    const text = raw.substring(0, idx).trim()
    const translation = raw.substring(idx + 3).trim()
    return { text, translation: translation || undefined }
  }
  return { text: raw }
}

/**
 * 使用语音大模型发送文本消息（键盘输入模式）
 * 键盘模式回退到浏览器 TTS/模拟响应
 */
async function sendTextToVoiceAI(text: string) {
  if (!voiceConnected.value) return

  // 显示用户消息
  const minutesAdded = messages.value.length + 1
  const timeString = `10:${minutesAdded < 10 ? '0' + minutesAdded : minutesAdded}`
  statusTime.value = timeString

  const userMsg: DialectMessage = {
    id: 'usr_' + Date.now(),
    role: 'user',
    text: text,
    timestamp: timeString
  }
  messages.value = [...messages.value, userMsg]

  // AI 正在思考
  isThinking.value = true

  // 键盘模式下通过管线 LLM 获取 AI 回复（语音 AI 主要支持语音输入）
  // 这里复用管线流程获得真实的 LLM 响应
  if (configService.getConfig().enableAiPipeline) {
    await handleAiPipelineSubmit(text)
  } else {
    // 无管线配置时，使用模拟响应
    setTimeout(() => {
      isThinking.value = false
      const nextIndex = currentQuestionIndex.value + 1
      const isFinish = nextIndex > currentScenario.value.questions.length
      let aiResponseText = isFinish
        ? 'Congratulations! You have completed all of the active interactive prompts in this scenario. Let\'s transition to your performance summary.'
        : currentScenario.value.questions[nextIndex - 1]?.text || ''
      const aiMsg: DialectMessage = {
        id: 'ai_' + Date.now(),
        role: 'ai',
        text: aiResponseText,
        timestamp: `10:${(minutesAdded + 1) < 10 ? '0' + (minutesAdded + 1) : (minutesAdded + 1)}`,
        showTranslation: subtitlesOn.value
      }
      messages.value = [...messages.value, aiMsg]
      currentQuestionIndex.value = nextIndex
    }, 2000)
  }
}// 组件销毁时断开连接
onUnmounted(() => {
  disconnectVoiceAI()
  disconnectPipelineWebSocket()
})


</script>

<template>
  <div class="bg-slate-50 text-slate-900 min-h-screen flex flex-col font-sans overflow-x-hidden antialiased select-none">
    <!-- 浏览器兼容性提示 Banner -->
    <div 
      v-if="showCompatBanner && browserCapabilities" 
      class="fixed top-0 left-0 right-0 z-[100] px-4 py-3 shadow-lg"
      :class="[
        browserCapabilities.recommendedMode === 'keyboard-only' 
          ? 'bg-red-50 border-b border-red-200' 
          : 'bg-amber-50 border-b border-amber-200'
      ]"
    >
      <div class="max-w-4xl mx-auto flex items-center justify-between gap-4">
        <div class="flex items-center gap-3">
          <AlertCircle 
            v-if="browserCapabilities.recommendedMode === 'keyboard-only'" 
            class="w-5 h-5 text-red-500" 
          />
          <AlertCircle 
            v-else 
            class="w-5 h-5 text-amber-500" 
          />
          <div class="flex-1">
            <p 
              class="text-sm font-medium"
              :class="[
                browserCapabilities.recommendedMode === 'keyboard-only' 
                  ? 'text-red-700' 
                  : 'text-amber-700'
              ]"
            >
              {{ compatMessage }}
            </p>
            <p 
              v-if="browserCapabilities.isFirefox" 
              class="text-xs mt-1 text-amber-600"
            >
              推荐使用 Chrome 或 Edge 浏览器获得最佳语音体验
            </p>
          </div>
        </div>
        <button 
          @click="dismissCompatBanner"
          class="px-3 py-1.5 rounded-lg text-xs font-medium transition-colors"
          :class="[
            browserCapabilities.recommendedMode === 'keyboard-only' 
              ? 'bg-red-100 hover:bg-red-200 text-red-700' 
              : 'bg-amber-100 hover:bg-amber-200 text-amber-700'
          ]"
        >
          我知道了
        </button>
      </div>
    </div>

    <!-- 设置页面独立显示 -->
    <SettingsPage v-if="currentView === 'settings'" @go-back="handleViewChange('scenarios')" />

    <!-- 其他页面 -->
    <template v-else>
      <Header
        :current-scenario="currentScenario"
        :scenarios="SCENARIOS"
        :status-text="`本轮训练 ${statusTime}`"
        :current-view="currentView"
        @select-scenario="handleSelectScenario"
        @view-change="handleViewChange"
      />

      <main class="flex-1 w-full flex flex-col pt-16 min-h-screen">

        <ScenarioSelection
          v-if="currentView === 'scenarios'"
          :scenarios="SCENARIOS"
          @select-scenario="handleSelectScenario"
          @view-change="handleViewChange"
          @open-history="handleViewChange('history')"
        />

        <ConversationHistory
          v-else-if="currentView === 'history'"
          :conversations="conversationsList"
          :loading="conversationsLoading"
          @view-conversation="handleViewConversation"
          @delete-conversation="handleDeleteConversation"
          @close="handleViewChange('scenarios')"
          @create-new="handleCreateNewConversation"
        />

        <!-- ===== practice 视图 - 使用 fixed 定位，和 header 一样固定在页面上 ===== -->
        <template v-else-if="currentView === 'practice'">
        <div class="fixed inset-x-0 top-16 bottom-0 flex overflow-hidden z-10">
          <!-- 左侧历史对话边栏 -->
          <ConversationSidebar
            :conversations="conversationsList"
            :loading="conversationsLoading"
            :current-session-id="currentSessionId"
            :scenarios="SCENARIOS"
            @select-conversation="handleViewConversation"
            @delete-conversation="handleDeleteConversation"
            @create-new-conversation="handleCreateNewConversation"
          />

          <!-- 右侧主内容区域 -->
          <div class="flex-1 flex flex-col min-w-0 overflow-hidden">
            <!-- 顶部状态栏（固定在聊天区上方，不随聊天滚动） -->
            <div class="flex-none px-5 md:px-12 pt-4 pb-2 flex items-center justify-between bg-gradient-to-b from-[#f7fbfa] to-transparent z-10">
              <div class="flex items-center gap-2">
                <span class="px-2.5 py-1 bg-[#b4ffed] text-[#006053] font-mono text-[10px] font-black uppercase rounded-md tracking-wider">
                  {{ currentScenario.tag }}
                </span>
                <span class="text-xs font-semibold text-slate-500 font-sans">
                  当前练习: <span class="text-slate-800 font-bold">{{ currentScenario.title }}</span> (进度: {{ currentQuestionIndex }}/{{ currentScenario.questions.length }})
                </span>
              </div>

              <div class="flex items-center gap-2">
                <div v-if="voiceConnecting" class="flex items-center gap-1.5 px-2.5 py-1 bg-amber-50 border border-amber-200 rounded-lg">
                  <span class="w-1.5 h-1.5 rounded-full bg-amber-500 animate-pulse"></span>
                  <span class="text-[10px] font-bold text-amber-700">语音AI连接中...</span>
                </div>
                <div v-else-if="voiceConnected && voiceAiReady" class="flex items-center gap-1.5 px-2.5 py-1 bg-emerald-50 border border-emerald-200 rounded-lg">
                  <span class="w-1.5 h-1.5 rounded-full bg-emerald-500"></span>
                  <span class="text-[10px] font-bold text-emerald-700">语音AI已连接</span>
                </div>
                <button
                  @click="handleResetScenario"
                  class="text-[10px] font-bold text-[#006053] hover:text-[#0f7b6b] flex items-center gap-1 bg-white border border-[#bdc9c5]/35 px-2.5 py-1 rounded-lg transition-colors cursor-pointer shadow-sm"
                >
                  重置该场景
                </button>
                <button
                  @click="handleEndConversation"
                  :disabled="isEndingConversation"
                  class="text-[10px] font-bold text-white flex items-center gap-1 bg-[#006053] px-3 py-1 rounded-lg transition-colors shadow-sm"
                  :class="isEndingConversation ? 'opacity-60 cursor-not-allowed' : 'hover:opacity-90 cursor-pointer'"
                >
                  <svg v-if="isEndingConversation" class="w-3 h-3 animate-spin" viewBox="0 0 24 24" fill="none">
                    <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
                    <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                  </svg>
                  <span>{{ isEndingConversation ? '正在生成总结...' : '完成对话' }}</span>
                </button>
              </div>
            </div>

            <!-- 仅聊天区域可滚动 -->
            <div class="flex-1 min-h-0 overflow-y-auto">
              <ChatArea
                :messages="messages"
                :is-playing-audio="isPlayingAudio"
                :active-voice-message-id="activeVoiceMessageId"
                :current-scenario="currentScenario"
                :is-thinking="isThinking"
                @speak="handleManualSpeak"
                @toggle-translation="handleToggleTranslation"
              />
            </div>

            <!-- 底部控制面板（固定在聊天区下方，不随聊天滚动） -->
            <div class="flex-none z-20">
              <ControlPanel
                :is-recording="isRecording"
                :is-typing-mode="isTypingMode"
                :subtitles-on="subtitlesOn"
                :has-rating="!!currentRating"
                :current-transcript="currentTranscript"
                :audio-devices="audioDevices"
                :selected-audio-device-id="selectedAudioDeviceId"
                :is-ai-speaking="isPlayingAudio || isThinking"
                :is-voice-connecting="voiceConnecting"
                :is-realtime-mode="isRealtimeMode"
                @start-recording="startRecording"
                @stop-recording="handleStopRecording"
                @toggle-typing-mode="isTypingMode = !isTypingMode"
                @toggle-subtitles="handleGlobalToggleSubtitles"
                @send-text="handleUserAnswerSubmit"
                @open-rating-modal="ratingOpen = true"
                @change-audio-device="handleChangeAudioDevice"
                @interrupt="interruptPlayback"
                @toggle-realtime-mode="toggleRealtimeMode"
              />
            </div>
          </div>
        </div>
        </template>

      <PracticeSummary
        v-else-if="currentView === 'summary'"
        :current-scenario="currentScenario"
        :score="currentRating || {
          overall: 88,
          accuracy: 85,
          fluency: 90,
          grammar: 82,
          feedbackSummary: '您的整体语调和发音十分标准，语音节奏富有条理，能够生动传达关键意思。仅在局部高级词藻挑选及过去时复现上有少量微调空间。'
        }"
        :summary-result="currentSummaryResult"
        :messages="messages"
        @restart-practice="handleRestartPractice"
        @go-back-to-scenarios="handleViewChange('scenarios')"
      />
      </main>

      <PronunciationModal
        v-if="ratingOpen && currentRating"
        :score="currentRating"
        @close="ratingOpen = false"
      />

      <div
        v-if="ratingOpen && !currentRating"
        class="fixed inset-0 bg-slate-900/40 backdrop-filter backdrop-blur-sm z-50 flex items-center justify-center p-4"
      >
        <div class="absolute inset-0" @click="ratingOpen = false"></div>
        <div class="relative bg-white rounded-2xl w-full max-w-sm p-6 overflow-hidden shadow-2xl border border-slate-100 animate-in zoom-in-95 duration-150 z-10 flex flex-col gap-4 text-center items-center">
          <span class="p-3 rounded-full bg-slate-100 text-slate-400">
            <Award class="w-8 h-8" />
          </span>
          <div>
            <h3 class="font-display font-bold text-slate-800 text-base mb-1">暂无评测记录</h3>
            <p class="font-sans text-xs text-slate-500 leading-relaxed">
              请先在 【对话练习】 中回答面试官提问。回答提交后，LingoAI 学术评测算法将自动生成高精确度的发音和语法质量测绘报告！
            </p>
          </div>
          <button
            @click="ratingOpen = false"
            class="w-full py-2 bg-slate-100 hover:bg-slate-200 text-slate-700 rounded-lg text-xs font-bold transition-all cursor-pointer"
          >
            我知道了
          </button>
        </div>
      </div>

      <!-- 模式选择弹窗 -->
      <PracticeModeModal
        v-if='showModeSelector'
        :scenario='currentScenario'
        @select-mode='handlePracticeModeSelect'
        @close='showModeSelector = false'
      />
    </template>
  </div>
</template>