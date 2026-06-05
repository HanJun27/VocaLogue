<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import Header from '@/components/Header.vue'
import ChatArea from '@/components/ChatArea.vue'
import ControlPanel from '@/components/ControlPanel.vue'
import PronunciationModal from '@/components/PronunciationModal.vue'
import ScenarioSelection from '@/components/ScenarioSelection.vue'
import PracticeSummary from '@/components/PracticeSummary.vue'
import SettingsPage from '@/components/SettingsPage.vue'
import { SCENARIOS, MOCK_RATING_DATABASE } from '@/scenariosData'
import type { DialectMessage, Scenario, PronunciationScore, GrammarFeedback } from '@/types'
import { Award, AlertCircle } from 'lucide-vue-next'
import api, { API_BASE_URL, getHeaders } from '@/api'
import { 
  getBrowserCapabilities, 
  getCompatibilityMessage,
  type BrowserCapabilities 
} from '@/utils/browserCompat'
import { voiceService } from '@/services/voice/VoiceService'
import { whisperASRService } from '@/services/asr'
import { configService } from '@/config'
import type { TranscriptEvent } from '@/services/voice/IVoiceService'

// 浏览器兼容性检测
const browserCapabilities = ref<BrowserCapabilities | null>(null)
const showCompatBanner = ref(false)
const compatMessage = ref('')

// API 模式控制（当前为演示使用本地模拟数据）
const USE_API_MODE = ref(false)
const currentSessionId = ref<string | null>(null)

// 真实语音大模型状态
const voiceConnected = ref(false)
const voiceConnecting = ref(false)
const voiceAiReady = ref(false)
const voiceAiInterimText = ref('')
const pendingAiMessageId = ref<string | null>(null)

// 是否应该使用真实语音大模型
const shouldUseVoiceAI = computed(() => {
  return configService.isConfigComplete()
})

const currentView = ref<'scenarios' | 'practice' | 'summary' | 'settings'>('scenarios')
const currentScenario = ref<Scenario>(SCENARIOS[0])
const messages = ref<DialectMessage[]>([])
const currentQuestionIndex = ref(0)

const isPlayingAudio = ref(false)
const activeVoiceMessageId = ref<string | null>(null)
const subtitlesOn = ref(true)
const isTypingMode = ref(false)

const ratingOpen = ref(false)
const currentRating = ref<PronunciationScore | null>(null)
const isThinking = ref(false)

const isRecording = ref(false)
const currentTranscript = ref('')
const recognitionInstance = ref<any>(null)

// Whisper ASR 录音状态
const isWhisperRecording = ref(false)
const whisperRecordingBlob = ref<Blob | null>(null)

// 麦克风设备列表
const audioDevices = ref<{ deviceId: string; groupId: string; kind: string; label: string }[]>([])
const selectedAudioDeviceId = ref(configService.getConfig().audioInputDeviceId)

const statusTime = ref('10:00')

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
      speakAudio(sc.welcomeMessage, welcomeMsg.id)
    }, 400)
  }
}

const speakAudio = (text: string, messageId: string) => {
  if ('speechSynthesis' in window) {
    window.speechSynthesis.cancel()

    if (activeVoiceMessageId.value === messageId && isPlayingAudio.value) {
      isPlayingAudio.value = false
      activeVoiceMessageId.value = null
      return
    }

    const utterance = new SpeechSynthesisUtterance(text)
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

const handleToggleTranslation = (messageId: string) => {
  messages.value = messages.value.map((msg) =>
    msg.id === messageId ? { ...msg, showTranslation: !msg.showTranslation } : msg
  )
}

/**
 * 开始录音 - 根据配置选择合适的方案
 * 优先级：豆包 Realtime API > Whisper ASR > 浏览器原生 Web Speech
 */
const startRecording = async () => {
  // 停止之前的录音
  window.speechSynthesis?.cancel()
  isPlayingAudio.value = false
  activeVoiceMessageId.value = null
  currentTranscript.value = ''

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
      
      if (result.text && result.text.trim().length > 2) {
        console.log('[App] Whisper 转录成功:', result.text)
        handleUserAnswerSubmit(result.text.trim())
      } else {
        alert('没有检测到清晰的英语演说，请按住麦克风再说一遍，或选择键盘输入！')
      }
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
    })
    const streamUrl = `${API_BASE_URL}/api/practice/chat/stream?${params}`
    console.log('[App] Stream request URL:', streamUrl)

    const response = await fetch(streamUrl)
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

const handleUserAnswerSubmit = async (text: string) => {
  // 如果启用了 AI Pipeline 模式，走管线流程
  if (configService.getConfig().enableAiPipeline) {
    await handleAiPipelineSubmit(text)
    return
  }

  // 如果语音大模型已连接，使用 sendTextToVoiceAI
  if (voiceAiReady.value && voiceConnected.value) {
    await sendTextToVoiceAI(text)
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

    setTimeout(() => {
      speakAudio(aiResponseText, aiMsg.id)
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
  // 如果使用语音大模型且已在连接中，先断开
  if (shouldUseVoiceAI.value && voiceConnected.value && currentView.value === 'practice') {
    disconnectVoiceAI()
  }

  // 如果启用 API 模式，从后端获取完整场景数据
  if (USE_API_MODE.value) {
    try {
      const fullScenario = await api.getScenarioById(sc.id)
      currentScenario.value = fullScenario
    } catch (error) {
      console.error('获取场景详情失败:', error)
      // 回退到本地数据
      currentScenario.value = sc
    }
  } else {
    currentScenario.value = sc
  }
  resetConversationForScenario(sc)

  // 如果在练习模式且使用语音大模型，重新连接
  if (shouldUseVoiceAI.value && currentView.value === 'practice') {
    await connectVoiceAI()
  }
}

const handleViewChange = async (view: 'scenarios' | 'practice' | 'summary' | 'settings') => {
  window.speechSynthesis?.cancel()
  isPlayingAudio.value = false
  activeVoiceMessageId.value = null

  // 切换到练习视图
  if (view === 'practice') {
    // 连接语音大模型
    if (shouldUseVoiceAI.value) {
      await connectVoiceAI()
    }

    // 创建后端会话
    if (USE_API_MODE.value) {
      try {
        const conversation = await api.createConversation(currentScenario.value.id)
        currentSessionId.value = conversation.sessionId
        console.log('创建会话成功:', conversation.sessionId)
      } catch (error) {
        console.error('创建会话失败:', error)
      }
    }
  }

  // 离开练习视图
  if (currentView.value === 'practice' && view !== 'practice') {
    // 断开语音大模型
    disconnectVoiceAI()

    // 结束后端会话
    if (currentSessionId.value) {
      try {
        await api.endConversation(currentSessionId.value)
        console.log('会话已结束')
      } catch (error) {
        console.error('结束会话失败:', error)
      }
      currentSessionId.value = null
    }
  }

  currentView.value = view
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

  // 键盘模式下，通过模拟获取 AI 回复（语音 AI 主要支持语音输入）
  // 后续可扩展为通过 REST API 发送文本到 AI
  setTimeout(() => {
    isThinking.value = false

    const nextIndex = currentQuestionIndex.value + 1
    const isFinish = nextIndex > currentScenario.value.questions.length

    let aiResponseText = isFinish
      ? "Congratulations! You have completed all of the active interactive prompts in this scenario. Let's transition to your performance summary."
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

// 组件销毁时断开连接
onUnmounted(() => {
  disconnectVoiceAI()
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

      <main class="flex-1 w-full flex flex-col relative h-[calc(100vh-4rem)]">
        <ScenarioSelection
          v-if="currentView === 'scenarios'"
          :scenarios="SCENARIOS"
          @select-scenario="handleSelectScenario"
          @view-change="handleViewChange"
          @open-history="ratingOpen = true"
        />

        <template v-else-if="currentView === 'practice'">
        <div class="flex-1 flex flex-col relative h-full">
          <div class="px-5 md:px-12 pt-4 flex items-center justify-between">
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
                @click="currentView = 'summary'"
                class="text-[10px] font-bold text-slate-700 hover:text-slate-900 flex items-center gap-1 bg-white border border-slate-200 px-2.5 py-1 rounded-lg transition-colors cursor-pointer shadow-sm"
              >
                预览总结分析
              </button>
            </div>
          </div>

          <ChatArea
            :messages="messages"
            :is-playing-audio="isPlayingAudio"
            :active-voice-message-id="activeVoiceMessageId"
            :current-scenario="currentScenario"
            :is-thinking="isThinking"
            @speak="speakAudio"
            @toggle-translation="handleToggleTranslation"
          />

          <ControlPanel
            :is-recording="isRecording"
            :is-typing-mode="isTypingMode"
            :subtitles-on="subtitlesOn"
            :has-rating="!!currentRating"
            :current-transcript="currentTranscript"
            :audio-devices="audioDevices"
            :selected-audio-device-id="selectedAudioDeviceId"
            @start-recording="startRecording"
            @stop-recording="handleStopRecording"
            @toggle-typing-mode="isTypingMode = !isTypingMode"
            @toggle-subtitles="handleGlobalToggleSubtitles"
            @send-text="handleUserAnswerSubmit"
            @open-rating-modal="ratingOpen = true"
            @change-audio-device="handleChangeAudioDevice"
          />
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
        :messages="messages"
        @restart-practice="() => { resetConversationForScenario(currentScenario); currentView = 'practice' }"
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
    </template>
  </div>
</template>
