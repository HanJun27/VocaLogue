<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { MessageCircle, Trash2, ArrowLeft, Clock, Award, ChevronRight, Loader2 } from 'lucide-vue-next'
import type { ConversationSummary, Scenario } from '@/types'
import api from '@/api'
import { SCENARIOS } from '@/scenariosData'

interface Props {
  conversations: ConversationSummary[]
  loading: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  viewConversation: [sessionId: string]
  deleteConversation: [sessionId: string]
  close: []
  createNew: []
}>()

const formatDate = (dateStr: string) => {
  const date = new Date(dateStr)
  const month = (date.getMonth() + 1).toString().padStart(2, '0')
  const day = date.getDate().toString().padStart(2, '0')
  const hours = date.getHours().toString().padStart(2, '0')
  const minutes = date.getMinutes().toString().padStart(2, '0')
  return `${month}-${day} ${hours}:${minutes}`
}

const getScenarioInfo = (scenarioId: string) => {
  const sc = SCENARIOS.find(s => s.id === scenarioId)
  return {
    emoji: sc?.emoji || '💬',
    title: sc?.title || scenarioId
  }
}

const getDuration = (start: string, end: string | null) => {
  if (!end) return '进行中'
  const startDate = new Date(start)
  const endDate = new Date(end)
  const diffMs = endDate.getTime() - startDate.getTime()
  const diffMin = Math.floor(diffMs / 60000)
  if (diffMin < 1) return '不到1分钟'
  return `${diffMin}分钟`
}

const confirmDelete = (sessionId: string) => {
  emit('deleteConversation', sessionId)
}
</script>

<template>
  <div class="flex-1 w-full max-w-[900px] mx-auto px-4 md:px-8 py-6 flex flex-col gap-6 animate-in fade-in duration-200">
    <!-- 头部 -->
    <div class="flex items-center justify-between mb-2">
      <div class="flex items-center gap-3">
        <button
          @click="emit('close')"
          class="w-9 h-9 flex items-center justify-center rounded-full bg-white border border-slate-200 hover:bg-slate-50 transition-colors shadow-sm cursor-pointer"
          title="返回"
        >
          <ArrowLeft class="w-4 h-4 text-slate-600" />
        </button>
        <div>
          <h2 class="font-display text-2xl md:text-3xl font-extrabold text-[#121c2a] tracking-tight">
            对话记录
          </h2>
          <p class="font-sans text-sm text-slate-500 font-medium mt-0.5">
            查看和管理你的历史对话练习记录
          </p>
        </div>
      </div>
      <button
        @click="emit('createNew')"
        class="bg-[#006053] hover:bg-[#0f7b6b] text-white font-sans text-xs font-bold px-5 py-2.5 rounded-xl flex items-center gap-2 shadow-md transition-all cursor-pointer active:scale-95"
      >
        <MessageCircle class="w-4 h-4" />
        <span>新建对话</span>
      </button>
    </div>

    <!-- 加载状态 -->
    <div v-if="loading" class="flex flex-col items-center justify-center py-20 gap-3">
      <Loader2 class="w-8 h-8 text-[#006053] animate-spin" />
      <span class="font-sans text-sm text-slate-400">加载对话记录中...</span>
    </div>

    <!-- 空状态 -->
    <div v-else-if="conversations.length === 0" class="flex flex-col items-center justify-center py-20 gap-4">
      <div class="w-16 h-16 rounded-full bg-[#b4ffed]/30 flex items-center justify-center">
        <MessageCircle class="w-8 h-8 text-[#006053]" />
      </div>
      <div class="text-center">
        <h3 class="font-display font-bold text-slate-800 text-lg mb-1">暂无对话记录</h3>
        <p class="font-sans text-sm text-slate-500 max-w-sm">
          你还没有完成任何口语练习对话。选择一个场景开始你的第一次练习吧！
        </p>
      </div>
      <button
        @click="emit('createNew')"
        class="bg-[#006053] hover:bg-[#0f7b6b] text-white font-sans text-xs font-bold px-6 py-2.5 rounded-xl shadow-md transition-all cursor-pointer"
      >
        开始第一次练习
      </button>
    </div>

    <!-- 对话列表 -->
    <div v-else class="flex flex-col gap-3">
      <div
        v-for="conv in conversations"
        :key="conv.sessionId"
        class="bg-white rounded-2xl border border-slate-100 hover:border-[#006053]/20 hover:shadow-[0_4px_16px_rgba(15,123,107,0.08)] transition-all duration-200 overflow-hidden group"
      >
        <div class="p-5 flex items-center gap-4">
          <!-- 场景图标 -->
          <div class="w-12 h-12 rounded-xl bg-[#0f7b6b]/10 flex items-center justify-center text-2xl shrink-0 select-none">
            {{ getScenarioInfo(conv.scenarioId).emoji }}
          </div>

          <!-- 信息区 -->
          <div class="flex-1 min-w-0">
            <div class="flex items-center gap-2 mb-1">
              <h3 class="font-display font-bold text-slate-800 text-sm md:text-base truncate">
                {{ conv.scenarioTitle || getScenarioInfo(conv.scenarioId).title }}
              </h3>
              <span v-if="conv.scenarioTag" class="px-1.5 py-0.5 bg-slate-100 rounded text-[9px] font-bold text-slate-500 uppercase tracking-wider shrink-0">
                {{ conv.scenarioTag }}
              </span>
            </div>
            <div class="flex items-center gap-3 text-xs text-slate-400 font-sans">
              <span class="flex items-center gap-1">
                <Clock class="w-3 h-3" />
                {{ formatDate(conv.startTime) }}
              </span>
              <span>·</span>
              <span>{{ getDuration(conv.startTime, conv.endTime) }}</span>
              <span>·</span>
              <span>{{ conv.messageCount }} 条消息</span>
            </div>
          </div>

          <!-- 分数 -->
          <div v-if="conv.overallScore != null" class="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-[#b4ffed]/20 border border-[#0f7b6b]/10 shrink-0">
            <Award class="w-4 h-4 text-[#006053]" />
            <span class="font-sans font-bold text-[#006053] text-sm">{{ conv.overallScore }}</span>
          </div>

          <!-- 操作按钮 -->
          <div class="flex items-center gap-1 shrink-0">
            <button
              @click="emit('viewConversation', conv.sessionId)"
              class="w-9 h-9 flex items-center justify-center rounded-full bg-[#E6F4F1] hover:bg-[#b4ffed]/40 text-[#006053] transition-colors cursor-pointer"
              title="查看对话详情"
            >
              <ChevronRight class="w-4 h-4" />
            </button>
            <button
              @click="confirmDelete(conv.sessionId)"
              class="w-9 h-9 flex items-center justify-center rounded-full hover:bg-red-50 text-slate-400 hover:text-red-500 transition-colors cursor-pointer opacity-0 group-hover:opacity-100"
              title="删除对话记录"
            >
              <Trash2 class="w-4 h-4" />
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
