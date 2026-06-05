<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import { Volume2, VolumeX, Languages, Check, Lightbulb } from 'lucide-vue-next'
import type { DialectMessage, Scenario } from '@/types'

interface Props {
  messages: DialectMessage[]
  isPlayingAudio: boolean
  activeVoiceMessageId: string | null
  currentScenario: Scenario
  isThinking: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  speak: [text: string, messageId: string]
  toggleTranslation: [messageId: string]
}>()

const bottomRef = ref<HTMLElement | null>(null)

watch(
  () => [props.messages.length, props.isThinking],
  async () => {
    await nextTick()
    bottomRef.value?.scrollIntoView({ behavior: 'smooth' })
  }
)
</script>

<template>
  <div class="flex-1 p-4 md:p-8 no-scrollbar flex flex-col gap-6 max-w-[1000px] mx-auto w-full pt-6 pb-4">
    <div class="self-center glass-panel rounded-xl px-6 py-3 shadow-[0_2px_8px_rgba(0,107,92,0.02)] border border-[#bdc9c5]/30 max-w-lg text-center">
      <p class="font-sans text-xs text-slate-500 leading-relaxed font-medium">
        你正在进行 <span class="text-[#006053] font-bold">{{ currentScenario.title }}</span> 的模拟练习。AI 面试官将开始提问，请用英语回答。若不方便发音，可随时切换到键盘输入。
      </p>
    </div>

    <template v-for="message in messages" :key="message.id">
      <div v-if="message.role === 'ai'" class="flex w-full mt-4 space-x-3 max-w-3xl self-start">
        <div class="flex-shrink-0 w-10 h-10 rounded-full bg-[#d9e3f6] flex items-center justify-center border border-[#bdc9c5]/20 shadow-sm overflow-hidden select-none">
          <img
            alt="AI Avatar"
            class="w-full h-full object-cover"
            src="https://lh3.googleusercontent.com/aida/AP1WRLtyg6b33f4lr8OaooUsJIG0oJybvvYKDxqyFauMM4QlIR8XRHfDb2PkhZbEWyf3SwT77FSRDuyRMFPRXY8hVoir0uUSdviEILygG45iyw9n2E79a1wikh7kQh4PY_RHWIbVEyLraHPYHPoVSI1mlJzLiTfjWc1DSTNJkmzTfay7MnrR38zV7jNY9tKB5pPCA0D6r6Ph6kGfcCxi229TRgebcq1s2RFgrrRE1dVnXNKWtphySGH_YiNC2LM"
          />
        </div>

        <div class="flex flex-col gap-1 max-w-[85%] md:max-w-[75%]">
          <span class="font-mono text-[11px] text-slate-400 font-medium ml-1">
            AI 面试官 • {{ message.timestamp }}
          </span>

          <div class="bg-[#0f7b6b] text-white p-4 rounded-2xl rounded-tl-sm shadow-[0_4px_16px_rgba(15,123,107,0.08)] relative group/bubble select-text">
            <p class="font-sans text-sm md:text-[15px] leading-relaxed font-normal">
              {{ message.text }}
            </p>

            <div class="absolute -bottom-4.5 right-2 opacity-0 group-hover/bubble:opacity-100 focus-within:opacity-100 transition-opacity flex bg-white rounded-full shadow-md border border-slate-100 p-0.5 z-10 gap-0.5">
              <button
                @click="emit('speak', message.text, message.id)"
                :class="[
                  'w-7 h-7 flex items-center justify-center rounded-full transition-colors',
                  activeVoiceMessageId === message.id && isPlayingAudio ? 'bg-[#b4ffed] text-[#006053]' : 'text-[#006053] hover:bg-[#E6F4F1]'
                ]"
                :title="activeVoiceMessageId === message.id && isPlayingAudio ? '停止播放' : '播放语音'"
                class="cursor-pointer"
              >
                <VolumeX v-if="activeVoiceMessageId === message.id && isPlayingAudio" class="w-3.5 h-3.5" />
                <Volume2 v-else class="w-3.5 h-3.5" />
              </button>
              <button
                @click="emit('toggleTranslation', message.id)"
                :class="[
                  'w-7 h-7 flex items-center justify-center rounded-full transition-colors',
                  message.showTranslation ? 'bg-amber-100 text-amber-700' : 'text-slate-500 hover:bg-slate-50'
                ]"
                title="显示/隐藏翻译"
                class="cursor-pointer"
              >
                <Languages class="w-3.5 h-3.5" />
              </button>
            </div>
          </div>

          <div v-if="message.showTranslation && message.translation" class="mt-1 ml-2 text-slate-500 font-sans text-xs md:text-sm animate-in fade-in slide-in-from-top-1 duration-200">
            {{ message.translation }}
          </div>
        </div>
      </div>

      <div v-else class="flex w-full mt-4 space-x-3 max-w-3xl self-end justify-end">
        <div class="flex flex-col gap-1.5 max-w-[85%] md:max-w-[75%] items-end">
          <span class="font-mono text-[11px] text-slate-400 font-medium mr-1">
            你 • {{ message.timestamp }}
          </span>

          <div class="bg-[#f0f4fc]/90 border border-slate-200/50 text-[#121c2a] p-4 rounded-2xl rounded-tr-sm shadow-[0_2px_8px_rgba(0,0,0,0.02)] select-text">
            <p class="font-sans text-sm md:text-[15px] leading-relaxed font-medium">
              {{ message.text }}
            </p>
          </div>

          <div v-if="message.feedback" class="mt-1 w-full max-w-md bg-amber-50/85 border border-amber-200/50 rounded-xl p-3.5 shadow-sm text-left flex gap-3 items-start animate-in zoom-in-95 duration-200">
            <span class="p-1 rounded-lg bg-amber-100 text-amber-700 shrink-0 mt-0.5">
              <Lightbulb class="w-4 h-4 fill-amber-300 stroke-amber-700" />
            </span>
            <div>
              <div class="flex items-center gap-1.5 mb-1.5">
                <span class="font-sans text-xs font-bold text-amber-800">{{ message.feedback.title }}</span>
                <span class="px-1.5 py-0.5 rounded-full bg-emerald-100 text-emerald-800 text-[9px] font-bold flex items-center gap-0.5">
                  <Check class="w-2.5 h-2.5" />
                  <span>结构极佳</span>
                </span>
              </div>
              
              <div class="font-sans text-xs text-amber-900 leading-relaxed mb-1.5">
                <span class="text-slate-400 line-through mr-1.5 font-mono">
                  "{{ message.feedback.original }}"
                </span>
                <span>非常好。为了展现更强的专业性，可以尝试说:</span>
                <div class="font-mono bg-white/70 border border-amber-200/30 p-1.5 rounded-md mt-1 font-semibold text-amber-950 text-[11px] inline-block tracking-wide select-all">
                  "{{ message.feedback.suggested }}"
                </div>
              </div>

              <p class="font-sans text-[11px] text-amber-700 leading-normal font-medium bg-amber-100/30 border-l-2 border-amber-400 pl-1.5 py-0.5">
                {{ message.feedback.explanation }}
              </p>
            </div>
          </div>
        </div>

        <div class="flex-shrink-0 w-10 h-10 rounded-full bg-[#d7e5e2] flex items-center justify-center border border-[#bdc9c5]/30 shadow-sm overflow-hidden select-none">
          <span class="font-display font-bold text-xs text-slate-600">ME</span>
        </div>
      </div>
    </template>

    <div v-if="isThinking" class="flex w-full mt-4 space-x-3 max-w-3xl self-start animate-in fade-in duration-200">
      <div class="flex-shrink-0 w-10 h-10 rounded-full bg-[#d9e3f6] flex items-center justify-center border border-[#bdc9c5]/20 shadow-sm overflow-hidden select-none">
        <img
          alt="AI Avatar"
          class="w-full h-full object-cover"
          src="https://lh3.googleusercontent.com/aida/AP1WRLtyg6b33f4lr8OaooUsJIG0oJybvvYKDxqyFauMM4QlIR8XRHfDb2PkhZbEWyf3SwT77FSRDuyRMFPRXY8hVoir0uUSdviEILygG45iyw9n2E79a1wikh7kQh4PY_RHWIbVEyLraHPYHPoVSI1mlJzLiTfjWc1DSTNJkmzTfay7MnrR38zV7jNY9tKB5pPCA0D6r6Ph6kGfcCxi229TRgebcq1s2RFgrrRE1dVnXNKWtphySGH_YiNC2LM"
        />
      </div>

      <div class="flex flex-col gap-1">
        <span class="font-mono text-[11px] text-slate-400 font-medium ml-1">
          AI 面试官正在思考...
        </span>
        <div class="bg-[#b4ffed]/30 border border-[#bdc9c5]/20 text-[#006053] px-5 py-3.5 rounded-2xl rounded-tl-sm shadow-sm inline-flex items-center gap-1 w-fit">
          <div class="w-1.5 h-1.5 rounded-full bg-[#006053] typing-dot animate-bounce"></div>
          <div class="w-1.5 h-1.5 rounded-full bg-[#006053] typing-dot animate-bounce"></div>
          <div class="w-1.5 h-1.5 rounded-full bg-[#006053] typing-dot animate-bounce"></div>
        </div>
      </div>
    </div>

    <div ref="bottomRef" class="h-4" />
  </div>
</template>
