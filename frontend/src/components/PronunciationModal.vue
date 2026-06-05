<script setup lang="ts">
import { computed } from 'vue'
import { X, Award, Sparkles } from 'lucide-vue-next'
import type { PronunciationScore } from '@/types'

interface Props {
  score: PronunciationScore
}

const props = defineProps<Props>()
const emit = defineEmits<{
  close: []
}>()

const radius = 35
const circumference = 2 * Math.PI * radius
const strokeDashoffset = computed(() => circumference - (props.score.overall / 100) * circumference)
</script>

<template>
  <div class="fixed inset-0 bg-slate-900/40 backdrop-filter backdrop-blur-sm z-50 flex items-center justify-center p-4">
    <div class="absolute inset-0" @click="emit('close')"></div>

    <div class="relative bg-white rounded-3xl w-full max-w-md p-6 overflow-hidden shadow-2xl border border-slate-100 animate-in zoom-in-95 duration-150 z-10 flex flex-col gap-5">
      <div class="flex justify-between items-center pb-2 border-b border-slate-100">
        <div class="flex items-center gap-2">
          <span class="p-1.5 rounded-lg bg-[#b4ffed] text-[#006053]">
            <Award class="w-4.5 h-4.5" />
          </span>
          <h3 class="font-display text-[#006053] font-bold text-base">口语发音与流利度评测</h3>
        </div>
        <button
          @click="emit('close')"
          class="w-8 h-8 rounded-full flex items-center justify-center hover:bg-slate-100 text-slate-400 hover:text-slate-700 transition-colors cursor-pointer"
        >
          <X class="w-4 h-4" />
        </button>
      </div>

      <div class="bg-[#eff4ff] rounded-2xl p-4 flex items-center gap-5 border border-slate-100">
        <div class="relative flex items-center justify-center w-20 h-20 shrink-0 select-none">
          <svg class="w-full h-full transform -rotate-90">
            <circle
              cx="40"
              cy="40"
              :r="radius"
              class="stroke-slate-200 fill-none"
              stroke-width="7"
            />
            <circle
              cx="40"
              cy="40"
              :r="radius"
              class="stroke-[#006053] fill-none transition-all duration-500 ease-out"
              stroke-width="7"
              :stroke-dasharray="circumference"
              :stroke-dashoffset="strokeDashoffset"
              stroke-linecap="round"
            />
          </svg>
          <div class="absolute flex flex-col items-center justify-center text-center">
            <span class="font-display font-black text-[#006053] text-xl leading-none">
              {{ score.overall }}
            </span>
            <span class="text-[9px] text-[#006053]/80 font-semibold uppercase tracking-wide">综合分</span>
          </div>
        </div>

        <div>
          <div class="flex items-center gap-1.5 mb-1">
            <span class="font-display text-sm font-bold text-[#00201b]">优秀口语水平</span>
            <span class="px-1.5 py-0.5 bg-[#b4ffed] text-[#006053] rounded-full text-[9px] font-bold flex items-center gap-0.5">
              <Sparkles class="w-2.5 h-2.5 fill-current" />
              <span>Advanced</span>
            </span>
          </div>
          <p class="font-sans text-[11px] text-[#00201b]/80 leading-normal">
            您的语音语调、音节重音、音长分配已非常逼近母语者水平，展现出强大的专业说服力！
          </p>
        </div>
      </div>

      <div class="flex flex-col gap-3">
        <div>
          <div class="flex justify-between items-center mb-1 text-xs">
            <span class="font-sans text-slate-500 font-semibold flex items-center gap-1">
              <span class="w-1.5 h-1.5 rounded-full bg-emerald-500"></span>
              <span>发音精准度 (Pronunciation Accuracy)</span>
            </span>
            <span class="font-mono font-bold text-[#006053]">{{ score.accuracy }}点</span>
          </div>
          <div class="w-full h-2 bg-slate-100 rounded-full overflow-hidden">
            <div
              class="h-full bg-emerald-500 rounded-full transition-all duration-500"
              :style="{ width: `${score.accuracy}%` }"
            ></div>
          </div>
        </div>

        <div>
          <div class="flex justify-between items-center mb-1 text-xs">
            <span class="font-sans text-slate-500 font-semibold flex items-center gap-1">
              <span class="w-1.5 h-1.5 rounded-full bg-[#006053]"></span>
              <span>语流连贯度 (Acoustic Fluency)</span>
            </span>
            <span class="font-mono font-bold text-[#006053]">{{ score.fluency }}点</span>
          </div>
          <div class="w-full h-2 bg-slate-100 rounded-full overflow-hidden">
            <div
              class="h-full bg-[#006053] rounded-full transition-all duration-500"
              :style="{ width: `${score.fluency}%` }"
            ></div>
          </div>
        </div>

        <div>
          <div class="flex justify-between items-center mb-1 text-xs">
            <span class="font-sans text-slate-500 font-semibold flex items-center gap-1">
              <span class="w-1.5 h-1.5 rounded-full bg-amber-500"></span>
              <span>词汇与语法深度 (Lexical Grammar)</span>
            </span>
            <span class="font-mono font-bold text-[#006053]">{{ score.grammar }}点</span>
          </div>
          <div class="w-full h-2 bg-slate-100 rounded-full overflow-hidden">
            <div
              class="h-full bg-amber-500 rounded-full transition-all duration-500"
              :style="{ width: `${score.grammar}%` }"
            ></div>
          </div>
        </div>
      </div>

      <div class="bg-slate-50 rounded-xl p-3.5 border border-slate-100">
        <span class="font-sans text-[10px] font-bold text-slate-400 uppercase tracking-widest block mb-1">
          诊断建议 Feedback Report
        </span>
        <p class="font-sans text-[12px] text-slate-600 leading-relaxed">
          {{ score.feedbackSummary }}
        </p>
      </div>

      <button
        @click="emit('close')"
        class="w-full py-2.5 bg-[#006053] hover:bg-[#0f7b6b] text-white rounded-xl text-xs font-bold transition-all shadow-md active:scale-98 cursor-pointer"
      >
        确定，继续练习场景
      </button>
    </div>
  </div>
</template>
