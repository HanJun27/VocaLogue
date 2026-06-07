<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue'
import { Mic, Keyboard, Send, Subtitles, Award, Settings2, ChevronDown } from 'lucide-vue-next'

interface AudioDeviceInfo {
  deviceId: string
  groupId: string
  kind: string
  label: string
}

interface Props {
  isRecording: boolean
  isTypingMode: boolean
  subtitlesOn: boolean
  hasRating: boolean
  currentTranscript: string
  audioDevices: AudioDeviceInfo[]
  selectedAudioDeviceId: string
  /** AI 是否正在说话或思考（显示打断按钮） */
  isAISpeaking?: boolean
  /** AI 是否在连接中 */
  isVoiceConnecting?: boolean
  /** 实时对话模式激活 */
  isRealtimeMode?: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  startRecording: []
  stopRecording: []
  toggleTypingMode: []
  toggleSubtitles: []
  sendText: [text: string]
  openRatingModal: []
  changeAudioDevice: [deviceId: string]
  /** 打断 AI 播放 */
  interrupt: []
  /** 切换实时对话模式 */
  toggleRealtimeMode: []
}>()

const typedText = ref('')
const inputRef = ref<HTMLInputElement | null>(null)
const showDeviceMenu = ref(false)

const handleSubmit = (e: SubmitEvent) => {
  e.preventDefault()
  if (!typedText.value.trim()) return
  emit('sendText', typedText.value.trim())
  typedText.value = ''
}

const selectDevice = (deviceId: string) => {
  emit('changeAudioDevice', deviceId)
  showDeviceMenu.value = false
}

watch(
  () => props.isTypingMode,
  async (val) => {
    if (val) {
      await nextTick()
      inputRef.value?.focus()
    }
  }
)

const selectedDeviceLabel = computed(() => {
  if (!props.selectedAudioDeviceId) return '默认麦克风'
  const device = props.audioDevices.find(d => d.deviceId === props.selectedAudioDeviceId)
  return device?.label || '已选麦克风'
})
</script>

<template>
  <div class="flex-none w-full glass-panel border-t border-[#bdc9c5]/35 pt-4 pb-7 px-4 rounded-t-3xl shadow-[0_-8px_30px_rgba(15,123,107,0.06)]">
    <div class="max-w-[850px] mx-auto flex flex-col gap-3">
      <div v-if="isRecording" class="w-full text-center px-4 py-2 bg-emerald-50/70 border border-emerald-100/50 rounded-lg text-emerald-950 text-xs font-medium font-sans max-w-lg mx-auto">
        <div class="flex items-center justify-center gap-1.5 mb-1 text-[10px] text-emerald-700 tracking-wider font-bold uppercase animate-pulse">
          <span class="w-1.5 h-1.5 rounded-full bg-emerald-600"></span>
          <span>正在聆听并转写语音...</span>
        </div>
        <p class="italic font-mono min-h-[16px] text-slate-700 select-none">
          {{ currentTranscript || '“请开始用英文回答面试提问...”' }}
        </p>
      </div>

      <div class="flex items-center justify-between relative mt-1.5">
        <button
          @click="emit('openRatingModal')"
          :class="[
            'w-14 h-14 flex flex-col items-center justify-center gap-0.5 transition-colors group cursor-pointer',
            hasRating ? 'text-[#006053]' : 'text-slate-400 hover:text-slate-600'
          ]"
          title="查看最新一轮发音能力评测结果"
        >
          <div :class="[
            'w-9 h-9 rounded-full flex items-center justify-center transition-all shadow-sm',
            hasRating ? 'bg-[#98f3df] text-[#00201b]' : 'bg-slate-100 group-hover:bg-slate-200 text-slate-500'
          ]">
            <Award class="w-4 h-4 fill-current text-inherit" />
          </div>
          <span class="font-sans text-[9px] font-bold tracking-tight">发音评测</span>
        </button>

        <div class="absolute left-1/2 transform -translate-x-1/2 flex items-center gap-4">
          <template v-if="!isTypingMode">
            <div class="relative flex flex-col items-center">
              <!-- 设备选择器 -->
              <div class="relative mb-1.5">
                <button
                  @click="showDeviceMenu = !showDeviceMenu"
                  class="flex items-center gap-1 px-2 py-0.5 rounded-md bg-slate-100 hover:bg-slate-200 transition-colors text-[9px] font-medium text-slate-500 cursor-pointer"
                  title="选择麦克风设备"
                >
                  <Settings2 class="w-2.5 h-2.5" />
                  <span class="max-w-[80px] truncate">{{ selectedDeviceLabel }}</span>
                  <ChevronDown class="w-2.5 h-2.5" />
                </button>

                <!-- 设备下拉菜单 -->
                <div
                  v-if="showDeviceMenu"
                  class="absolute bottom-full left-1/2 -translate-x-1/2 mb-1 w-56 bg-white rounded-xl shadow-lg border border-slate-200 py-1 z-50 max-h-48 overflow-y-auto"
                >
                  <button
                    @click="selectDevice('')"
                    :class="[
                      'w-full text-left px-3 py-2 text-xs hover:bg-slate-50 transition-colors',
                      !selectedAudioDeviceId ? 'bg-[#E6F4F1] text-[#006053] font-semibold' : 'text-slate-600'
                    ]"
                  >
                    默认麦克风
                  </button>
                  <button
                    v-for="device in audioDevices"
                    :key="device.deviceId"
                    @click="selectDevice(device.deviceId)"
                    :class="[
                      'w-full text-left px-3 py-2 text-xs hover:bg-slate-50 transition-colors truncate',
                      selectedAudioDeviceId === device.deviceId ? 'bg-[#E6F4F1] text-[#006053] font-semibold' : 'text-slate-600'
                    ]"
                  >
                    {{ device.label || `麦克风 (${device.deviceId.slice(0, 8)}...)` }}
                  </button>
                  <div v-if="audioDevices.length === 0" class="px-3 py-2 text-xs text-slate-400 text-center">
                    未检测到其他麦克风设备
                  </div>
                </div>
              </div>

              <!-- 录音按钮区域 -->
              <div class="relative flex items-center justify-center">
                <!-- AI 正在说话/思考时显示打断按钮 -->
                <div v-if="isAISpeaking && !isRecording" class="absolute inset-0 flex items-center justify-center">
                  <button
                    @click="emit('interrupt')"
                    class="w-16 h-16 rounded-full bg-red-500 text-white shadow-[0_6px_20px_rgba(220,38,38,0.3)] flex items-center justify-center active:scale-95 transition-all z-20 hover:scale-105 cursor-pointer animate-pulse"
                    title="打断 AI 并开始说话"
                  >
                    <svg class="w-7 h-7" viewBox="0 0 24 24" fill="currentColor">
                      <path d="M6 6h4v12H6V6zm8 0h4v12h-4V6z"/>
                    </svg>
                  </button>
                </div>

                <template v-else>
                  <div v-if="isRecording" class="pulsing-ring w-20 h-20"></div>
                  <div v-if="isRecording" class="pulsing-ring w-20 h-20"></div>

                  <button
                    @click="isRecording ? emit('stopRecording') : emit('startRecording')"
                    :class="[
                      'relative w-16 h-16 rounded-full shadow-[0_6px_20px_rgba(0,96,83,0.25)] flex items-center justify-center active:scale-95 transition-all z-10 cursor-pointer',
                      isRecording ? 'bg-red-500 text-white' : 'bg-[#006053] text-white hover:scale-105'
                    ]"
                    :title="isRecording ? '结束录音并提交' : '开始录音答题'"
                  >
                    <div v-if="isRecording" class="w-4.5 h-4.5 bg-white rounded-sm animate-pulse" />
                    <Mic v-else class="w-6 h-6 stroke-[2.5px]" />
                  </button>
                </template>
              </div>
            </div>
          </template>

          <form v-else @submit="handleSubmit" class="flex items-center gap-1.5 px-3 py-1 bg-[#eff4ff] border border-slate-200 rounded-full shadow-inner w-[340px]">
            <input
              ref="inputRef"
              v-model="typedText"
              type="text"
              placeholder="输入你的面试英语回答..."
              class="flex-1 font-sans text-xs bg-transparent border-none outline-none text-slate-800 py-1.5 px-1 placeholder-slate-400"
            />
            <button
              type="submit"
              :disabled="!typedText.trim()"
              class="p-1.5 bg-[#006053] hover:bg-[#0f7b6b] text-white rounded-full transition-colors cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <Send class="w-3 h-3" />
            </button>
          </form>

          <button
            @click="emit('toggleRealtimeMode')"
            :class="[
              'w-7 h-7 flex items-center justify-center rounded-full border transition-colors shadow-sm cursor-pointer',
              isRealtimeMode ? 'bg-red-500 text-white border-red-400 animate-pulse' : 'bg-white text-slate-500 hover:text-slate-800 border-slate-200'
            ]"
            :title="isRealtimeMode ? '停止实时对话' : '实时对话模式（语音检测自动打断）'"
          >
            <svg v-if="isRealtimeMode" class="w-3.5 h-3.5" viewBox="0 0 24 24" fill="currentColor">
              <path d="M6 6h4v12H6V6zm8 0h4v12h-4V6z"/>
            </svg>
            <svg v-else class="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/>
              <path d="M19 10v2a7 7 0 0 1-14 0v-2"/>
              <line x1="12" y1="19" x2="12" y2="23"/>
              <line x1="8" y1="23" x2="16" y2="23"/>
            </svg>
          </button>
          <button
            @click="emit('toggleTypingMode')"
            :class="[
              'w-7 h-7 flex items-center justify-center rounded-full border border-slate-200 hover:bg-slate-100 transition-colors shadow-sm cursor-pointer',
              isTypingMode ? 'bg-[#98f3df] text-[#00201b]' : 'bg-white text-slate-500 hover:text-slate-800'
            ]"
            :title="isTypingMode ? '回到语音交互' : '切换为键盘输入打字模式'"
          >
            <Mic v-if="isTypingMode" class="w-3.5 h-3.5" />
            <Keyboard v-else class="w-3.5 h-3.5" />
          </button>
        </div>

        <button
          @click="emit('toggleSubtitles')"
          :class="[
            'w-14 h-14 flex flex-col items-center justify-center gap-0.5 transition-colors group cursor-pointer',
            subtitlesOn ? 'text-[#006053]' : 'text-slate-400 hover:text-slate-600'
          ]"
          :title="subtitlesOn ? '关闭字幕翻译' : '打开字幕翻译'"
        >
          <div :class="[
            'w-9 h-9 rounded-full flex items-center justify-center transition-all shadow-sm',
            subtitlesOn ? 'bg-[#98f3df] text-[#00201b]' : 'bg-slate-100 group-hover:bg-slate-200 text-slate-500'
          ]">
            <Subtitles :class="['w-4 h-4', subtitlesOn ? 'stroke-[2.5px]' : 'stroke-inherit']" />
          </div>
          <span class="font-sans text-[9px] font-bold tracking-tight">
            字幕({{ subtitlesOn ? '开' : '关' }})
          </span>
        </button>
      </div>
    </div>

    <!-- 设备选择器背景遮罩 -->
    <div
      v-if="showDeviceMenu"
      class="fixed inset-0 z-40"
      @click="showDeviceMenu = false"
    ></div>
  </div>
</template>
