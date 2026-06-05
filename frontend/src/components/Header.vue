<script setup lang="ts">
import { ref } from 'vue'
import { Languages, Bell, User, MessageCircle } from 'lucide-vue-next'
import type { Scenario } from '@/types'

interface Props {
  currentScenario: Scenario
  scenarios: Scenario[]
  statusText: string
  currentView: 'scenarios' | 'practice' | 'summary'
}

defineProps<Props>()
const emit = defineEmits<{
  selectScenario: [scenario: Scenario]
  viewChange: [view: 'scenarios' | 'practice' | 'summary']
}>()

const dropdownOpen = ref(false)

const handleSelectScenario = (sc: Scenario) => {
  emit('selectScenario', sc)
  dropdownOpen.value = false
}

const handleViewChange = (view: 'scenarios' | 'practice' | 'summary') => {
  emit('viewChange', view)
}
</script>

<template>
  <header class="glass-panel sticky top-0 z-50 flex-none h-16 shadow-[0px_4px_20px_rgba(15,123,107,0.04)] px-4 md:px-12 flex justify-between items-center transition-all">
    <div class="flex items-center gap-4 md:gap-8">
      <div @click="handleViewChange('scenarios')" class="flex items-center gap-2.5 cursor-pointer">
        <div class="w-8 h-8 flex items-center justify-center bg-[#0F7B6B] rounded-xl text-white shadow-sm">
          <MessageCircle class="w-5 h-5 fill-white text-[#0F7B6B]" />
        </div>
        <span class="font-display text-xl font-bold tracking-tight text-[#006053]">LingoAI</span>
      </div>

      <nav class="hidden md:flex items-center gap-6">
        <div class="relative">
          <button
            @click="handleViewChange('scenarios')"
            :class="[
              'font-sans text-sm font-semibold transition-colors cursor-pointer',
              currentView === 'scenarios' ? 'text-[#006053]' : 'text-slate-500 hover:text-[#006053]'
            ]"
          >
            场景练习
          </button>
          <div v-if="currentView === 'scenarios'" class="absolute -bottom-[22px] left-0 w-full h-0.5 bg-[#006053] rounded-full"></div>
        </div>
        <div class="relative">
          <button
            @click="handleViewChange('practice')"
            :class="[
              'font-sans text-sm font-semibold transition-colors cursor-pointer',
              currentView === 'practice' ? 'text-[#006053]' : 'text-slate-500 hover:text-[#006053]'
            ]"
          >
            对话练习
          </button>
          <div v-if="currentView === 'practice'" class="absolute -bottom-[22px] left-0 w-full h-0.5 bg-[#006053] rounded-full"></div>
        </div>
        <div class="relative">
          <button
            @click="handleViewChange('summary')"
            :class="[
              'font-sans text-sm font-semibold transition-colors cursor-pointer',
              currentView === 'summary' ? 'text-[#006053]' : 'text-slate-500 hover:text-[#006053]'
            ]"
          >
            进度总结
          </button>
          <div v-if="currentView === 'summary'" class="absolute -bottom-[22px] left-0 w-full h-0.5 bg-[#006053] rounded-full"></div>
        </div>
      </nav>
    </div>

    <div class="flex items-center gap-3">
      <div class="relative">
        <button
          @click="dropdownOpen = !dropdownOpen"
          class="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-[#E6F4F1] hover:bg-[#b4ffed]/30 border border-[#bdc9c5] text-xs font-semibold text-[#006053] transition-all cursor-pointer shadow-sm"
        >
          <Languages class="w-3.5 h-3.5" />
          <span>切换场景: {{ currentScenario.title }}</span>
          <span class="ml-0.5 text-[9px] opacity-70">▼</span>
        </button>

        <template v-if="dropdownOpen">
          <div class="fixed inset-0 z-10" @click="dropdownOpen = false"></div>
          <div class="absolute right-0 mt-2 w-64 bg-white rounded-xl shadow-lg border border-slate-100 py-1.5 z-20 animate-in fade-in slide-in-from-top-2 duration-150">
            <div class="px-3 py-1 text-[10px] font-bold text-slate-400 tracking-wider uppercase border-b border-slate-50 mb-1">
              选择模拟练习场景
            </div>
            <button
              v-for="sc in scenarios"
              :key="sc.id"
              @click="handleSelectScenario(sc)"
              :class="[
                'w-full text-left px-4 py-2 text-xs flex flex-col gap-0.5 transition-colors',
                sc.id === currentScenario.id
                  ? 'bg-[#E6F4F1] text-[#006053] font-semibold'
                  : 'text-slate-600 hover:bg-slate-50'
              ]"
            >
              <span class="flex items-center gap-1.5">
                {{ sc.title }}
                <span class="px-1.5 py-0.2 bg-slate-100 rounded text-[9px] text-slate-500 font-medium">
                  {{ sc.tag }}
                </span>
              </span>
            </button>
          </div>
        </template>
      </div>

      <div class="hidden lg:flex flex-col items-end mr-1 text-right">
        <div class="flex items-center gap-1.5">
          <span class="text-xs font-semibold text-slate-700">{{ currentScenario.title }}匹配中</span>
          <span class="relative flex h-2 w-2">
            <span class="animate-ping absolute inline-flex h-full w-full rounded-full bg-[#006053] opacity-75"></span>
            <span class="relative inline-flex rounded-full h-2 w-2 bg-[#006053]"></span>
          </span>
        </div>
        <span class="text-[10px] font-mono text-slate-400 font-medium tracking-wide">
          {{ statusText }}
        </span>
      </div>

      <div class="flex items-center gap-1.5 border-l border-slate-200/60 pl-3">
        <button class="w-9 h-9 flex items-center justify-center rounded-full hover:bg-slate-100 text-slate-500 hover:text-slate-800 transition-colors cursor-pointer">
          <Bell class="w-4.5 h-4.5" />
        </button>
        
        <div class="w-9 h-9 rounded-full bg-[#d7e5e2] flex items-center justify-center border border-[#bdc9c5]/30 shadow-sm text-[#121e1c] font-bold text-xs select-none">
          <User class="w-4.5 h-4.5 text-slate-600" />
        </div>
      </div>
    </div>
  </header>
</template>
