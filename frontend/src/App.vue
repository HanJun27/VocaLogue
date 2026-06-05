<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import Header from '@/components/Header.vue'
import ChatArea from '@/components/ChatArea.vue'
import ControlPanel from '@/components/ControlPanel.vue'
import PronunciationModal from '@/components/PronunciationModal.vue'
import ScenarioSelection from '@/components/ScenarioSelection.vue'
import PracticeSummary from '@/components/PracticeSummary.vue'
import SettingsPage from '@/components/SettingsPage.vue'
import { SCENARIOS, MOCK_RATING_DATABASE } from '@/scenariosData'
import type { DialectMessage, Scenario, PronunciationScore, GrammarFeedback } from '@/types'
import { Award, AlertCircle, CheckCircle2 } from 'lucide-vue-next'
import api from '@/api'
import { 
  getBrowserCapabilities, 
  getCompatibilityMessage,
  type BrowserCapabilities 
} from '@/utils/browserCompat'
import { voiceService } from '@/services/voice/VoiceService'

// 浏览器兼容性检测
const browserCapabilities = ref<BrowserCapabilities | null>(null)
const showCompatBanner = ref(false)
const compatMessage = ref('')

// API 模式控制（当前为演示使用本地模拟数据）
const USE_API_MODE = ref(false)
const currentSessionId = ref<string | null>(null)

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

  if (currentView.value === 'practice') {
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
 * 开始录音 - 根据浏览器能力选择合适的方案
 */
const startRecording = () => {
  // 检查浏览器能力
  if (!browserCapabilities.value) {
    browserCapabilities.value = getBrowserCapabilities()
  }
  
  // 如果不支持语音，提示用户切换键盘模式
  if (browserCapabilities.value.recommendedMode === 'keyboard-only') {
    alert('您的浏览器不支持语音功能。请使用键盘输入模式进行回答！')
    isTypingMode.value = true
    return
  }
  
  // Firefox 或其他不支持 Web Speech 的浏览器，使用实时音频流模式
  if (!browserCapabilities.value.hasSpeechRecognition) {
    // 这里应该连接 Realtime API 进行语音对话
    // 由于当前是演示模式，暂时提示用户使用键盘输入
    if (browserCapabilities.value.isFirefox) {
      alert('Firefox 浏览器不支持 Web Speech API。\n\n请使用键盘输入模式，或切换到 Chrome/Edge 浏览器获得完整语音体验。\n\n提示：完整语音功能需要配置 Realtime API（OpenAI/豆包）。')
    } else {
      alert('您的浏览器不支持语音识别功能。请使用键盘输入模式！')
    }
    isTypingMode.value = true
    return
  }
  
  // Chrome/Edge 等支持 Web Speech 的浏览器
  const SpeechRecognition =
    (window as any).webkitSpeechRecognition || (window as any).SpeechRecognition

  try {
    window.speechSynthesis?.cancel()
    isPlayingAudio.value = false
    activeVoiceMessageId.value = null

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
  } catch (err) {
    console.error(err)
    isRecording.value = false
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

const handleStopRecording = () => {
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

const handleUserAnswerSubmit = async (text: string) => {
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

const handleSelectScenario = async (sc: Scenario) => {
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
}

const handleViewChange = async (view: 'scenarios' | 'practice' | 'summary') => {
  window.speechSynthesis?.cancel()
  isPlayingAudio.value = false
  activeVoiceMessageId.value = null

  // 如果切换到练习视图，创建会话
  if (view === 'practice' && USE_API_MODE.value) {
    try {
      const conversation = await api.createConversation(currentScenario.value.id)
      currentSessionId.value = conversation.sessionId
      console.log('创建会话成功:', conversation.sessionId)
    } catch (error) {
      console.error('创建会话失败:', error)
    }
  }

  // 如果离开练习视图，结束会话
  if (currentView.value === 'practice' && view !== 'practice' && currentSessionId.value) {
    try {
      await api.endConversation(currentSessionId.value)
      console.log('会话已结束')
    } catch (error) {
      console.error('结束会话失败:', error)
    }
    currentSessionId.value = null
  }

  currentView.value = view
}
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
              <button
                @click="resetConversationForScenario(currentScenario)"
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
            @start-recording="startRecording"
            @stop-recording="handleStopRecording"
            @toggle-typing-mode="isTypingMode = !isTypingMode"
            @toggle-subtitles="handleGlobalToggleSubtitles"
            @send-text="handleUserAnswerSubmit"
            @open-rating-modal="ratingOpen = true"
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
