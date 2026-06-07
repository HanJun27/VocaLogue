<script setup lang="ts">
import { ref } from 'vue'
import { Zap, Cpu, Info } from 'lucide-vue-next'
import type { Scenario } from '@/types'

interface Props {
  scenario: Scenario
}

defineProps<Props>()

const emit = defineEmits<{
  selectMode: [mode: 'pipeline' | 'realtime']
  close: []
}>()

const selectedMode = ref<'pipeline' | 'realtime' | null>(null)

const confirmSelection = () => {
  if (selectedMode.value) {
    emit('selectMode', selectedMode.value)
  }
}
</script>

<template>
  <div class="fixed inset-0 bg-slate-900/40 backdrop-filter backdrop-blur-sm z-50 flex items-center justify-center p-4">
    <div class="absolute inset-0" @click="emit('close')"></div>

    <div class="relative bg-white rounded-3xl w-full max-w-lg overflow-hidden shadow-2xl border border-slate-100 animate-in zoom-in-95 fade-in duration-150 z-10">
      <div class="h-2 bg-gradient-to-r from-[#0F7B6B] via-[#b4ffed] to-[#006053]"></div>

      <div class="p-6 md:p-8">
        <div class="flex items-center gap-3 mb-2">
          <span class="text-2xl">{{ scenario.emoji || '💬' }}</span>
          <div>
            <h2 class="text-xl font-bold text-slate-800 font-display">{{ scenario.title }}</h2>
            <span class="text-xs font-mono text-[#006053] font-bold uppercase tracking-wider bg-[#b4ffed]/30 px-2 py-0.5 rounded">
              {{ scenario.tag }}
            </span>
          </div>
        </div>

        <p class="text-sm text-slate-500 mt-3 mb-5 leading-relaxed">
          请选择练习模式。不同的模式在延迟、流畅度和功能完整度上有所区别。
        </p>

        <div class="space-y-3">
          <label
            class="flex items-start gap-4 p-4 rounded-2xl border-2 cursor-pointer transition-all duration-200"
            :class="selectedMode === 'pipeline'
              ? 'border-[#0F7B6B] bg-[#E6F4F1] shadow-md'
              : 'border-slate-200 hover:border-slate-300 bg-white'"
          >
            <input
              type="radio"
              name="practiceMode"
              value="pipeline"
              v-model="selectedMode"
              class="mt-1 accent-[#0F7B6B]"
            />
            <div class="flex-1">
              <div class="flex items-center gap-2">
                <Cpu class="w-5 h-5 text-[#0F7B6B]" />
                <span class="font-bold text-slate-800">ASR → LLM → TTS 管线模式</span>
              </div>
              <ul class="mt-2 text-xs text-slate-500 space-y-0.5">
                <li class="flex items-center gap-1.5"><span class="w-1 h-1 rounded-full bg-slate-300"></span>适合本地部署调试，各环节可独立切换引擎</li>
                <li class="flex items-center gap-1.5"><span class="w-1 h-1 rounded-full bg-slate-300"></span>端到端延迟约 3-5 秒，支持发音评测</li>
                <li class="flex items-center gap-1.5"><span class="w-1 h-1 rounded-full bg-slate-300"></span>不依赖语音大模型 API，节省用量</li>
              </ul>
            </div>
          </label>

          <label
            class="flex items-start gap-4 p-4 rounded-2xl border-2 cursor-pointer transition-all duration-200"
            :class="selectedMode === 'realtime'
              ? 'border-[#0F7B6B] bg-[#E6F4F1] shadow-md'
              : 'border-slate-200 hover:border-slate-300 bg-white'"
          >
            <input
              type="radio"
              name="practiceMode"
              value="realtime"
              v-model="selectedMode"
              class="mt-1 accent-[#0F7B6B]"
            />
            <div class="flex-1">
              <div class="flex items-center gap-2">
                <Zap class="w-5 h-5 text-amber-500" />
                <span class="font-bold text-slate-800">端到端实时语音模式</span>
                <span class="text-[10px] font-bold px-2 py-0.5 rounded-full bg-amber-100 text-amber-700">推荐</span>
              </div>
              <ul class="mt-2 text-xs text-slate-500 space-y-0.5">
                <li class="flex items-center gap-1.5"><span class="w-1 h-1 rounded-full bg-slate-300"></span>端到端延迟 &lt; 1.5 秒，对话流畅自然</li>
                <li class="flex items-center gap-1.5"><span class="w-1 h-1 rounded-full bg-slate-300"></span>支持语音打断、实时字幕、中英双语</li>
                <li class="flex items-center gap-1.5"><span class="w-1 h-1 rounded-full bg-slate-300"></span>需要配置豆包 / GPT-4o Realtime API</li>
              </ul>
            </div>
          </label>
        </div>

        <div v-if="selectedMode === 'realtime'" class="mt-4 p-3 bg-amber-50 border border-amber-200 rounded-xl flex items-start gap-2">
          <Info class="w-4 h-4 text-amber-500 mt-0.5 shrink-0" />
          <p class="text-xs text-amber-700 leading-relaxed">
            端到端模式需要配置语音大模型 API Key。如果尚未配置，请在设置中先完成配置，或选择管线模式。
          </p>
        </div>

        <div class="mt-6 flex items-center gap-3">
          <button
            @click="emit('close')"
            class="flex-1 py-3 px-4 border border-slate-200 text-slate-600 rounded-xl font-semibold text-sm hover:bg-slate-50 transition-all cursor-pointer"
          >
            取消
          </button>
          <button
            @click="confirmSelection"
            :disabled="!selectedMode"
            class="flex-1 py-3 px-4 rounded-xl font-semibold text-sm transition-all cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed"
            :class="selectedMode === 'realtime'
              ? 'bg-amber-500 text-white hover:bg-amber-600 shadow-md'
              : 'bg-[#0F7B6B] text-white hover:bg-[#006053] shadow-md'"
          >
            <template v-if="selectedMode === 'pipeline'">开始管线练习</template>
            <template v-else-if="selectedMode === 'realtime'">开始实时练习</template>
            <template v-else>请选择模式</template>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
