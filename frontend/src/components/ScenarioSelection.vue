<script setup lang="ts">
import { Star, Mic, History } from 'lucide-vue-next'
import type { Scenario } from '@/types'

interface Props {
  scenarios: Scenario[]
}

defineProps<Props>()
const emit = defineEmits<{
  selectScenario: [scenario: Scenario]
  viewChange: [view: 'scenarios' | 'practice' | 'summary']
  openHistory: []
}>()

const handleSelectScenario = (sc: Scenario) => {
  emit('selectScenario', sc)
  emit('viewChange', 'practice')
}
</script>

<template>
  <div class="flex-1 w-full max-w-[1240px] mx-auto px-4 md:px-8 py-6 flex flex-col gap-6 animate-in fade-in duration-200">
    <section class="mt-2 md:mt-6 mb-4">
      <h2 class="font-display text-3xl md:text-4xl font-extrabold text-[#121c2a] tracking-tight mb-2">
        选择练习场景
      </h2>
      <p class="font-sans text-sm md:text-base text-slate-500 max-w-2xl leading-relaxed font-medium">
        请挑选最贴近你实际成长所需的对话场景环境。本沙箱支持内置的智能流利度评分，带给您真实的面对面无压力式英文演练。
      </p>
    </section>

    <section class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
      <article
        v-for="sc in scenarios"
        :key="sc.id"
        @click="handleSelectScenario(sc)"
        class="bg-white rounded-3xl p-6 shadow-[0px_4px_20px_rgba(15,123,107,0.06)] border border-transparent hover:border-[#0f7b6b]/20 hover:shadow-[0px_8px_24px_rgba(15,123,107,0.12)] transition-all duration-300 transform active:scale-[0.98] cursor-pointer flex flex-col h-full group"
      >
        <div class="flex justify-between items-start mb-6">
          <div class="w-14 h-14 bg-[#0f7b6b]/10 rounded-2xl flex items-center justify-center text-3xl group-hover:bg-[#0f7b6b]/20 transition-colors shadow-sm select-none">
            {{ sc.emoji || '💼' }}
          </div>

          <div class="flex gap-0.5 bg-slate-50/70 border border-slate-100 px-3 py-1 rounded-full items-center select-none shadow-sm">
            <span class="font-sans text-[10px] font-bold text-slate-400 mr-1 uppercase">难度</span>
            <Star
              v-for="val in 3"
              :key="val"
              :class="['w-3 h-3', val <= (sc.difficulty || 2) ? 'text-amber-500 fill-amber-500' : 'text-slate-200']"
            />
          </div>
        </div>

        <div class="mb-2">
          <span class="text-[10px] font-mono text-[#006053] font-bold uppercase tracking-wider bg-[#b4ffed]/30 px-2 py-0.5 rounded">
            {{ sc.tag }}
          </span>
          <h3 class="font-display font-extrabold text-slate-800 text-lg md:text-xl mt-2 group-hover:text-[#006053] transition-colors leading-tight">
            {{ sc.title }}
          </h3>
        </div>

        <p class="font-sans text-xs md:text-sm text-slate-500 tracking-wide leading-relaxed flex-grow mb-6">
          {{ sc.description || '技术与价值观情景模拟训练，多方位磨炼高难度抗压英语口语。' }}
        </p>

        <button
          @click.stop="handleSelectScenario(sc)"
          class="w-full py-3 px-4 bg-slate-800 text-white font-sans text-xs font-bold rounded-xl group-hover:bg-[#006053] transition-colors flex items-center justify-center gap-2 cursor-pointer shadow-md"
        >
          <Mic class="w-3.5 h-3.5" />
          <span>开始无压力练习</span>
        </button>
      </article>
    </section>

    <section class="mt-4 flex justify-center pb-12">
      <button
        @click="emit('openHistory')"
        class="bg-white text-[#00201b] border-2 border-slate-200 hover:border-[#006053]/40 px-8 py-3 rounded-full font-sans text-xs font-bold hover:bg-slate-50 transition-all duration-200 shadow-sm flex items-center gap-2 cursor-pointer"
      >
        <History class="w-4 h-4 text-[#006053]" />
        <span>查看历史成绩记录</span>
      </button>
    </section>
  </div>
</template>
