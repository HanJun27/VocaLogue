<script setup lang="ts">
import { History, Trash2, Plus, MessageCircle, Clock } from 'lucide-vue-next'
import type { ConversationSummary, Scenario } from '@/types'

interface Props {
  conversations: ConversationSummary[]
  loading: boolean
  currentSessionId: string | null
  scenarios: Scenario[]
}

const props = defineProps<Props>()
const emit = defineEmits<{
  selectConversation: [sessionId: string]
  deleteConversation: [sessionId: string]
  createNewConversation: []
}>()

const getScenarioInfo = (scenarioId: string) => {
  const sc = props.scenarios.find(s => s.id === scenarioId)
  return {
    emoji: sc?.emoji || '💼',
    title: sc?.title || '未知场景',
    tag: sc?.tag || 'OTHER'
  }
}

const formatDate = (dateStr: string) => {
  try {
    const date = new Date(dateStr)
    const now = new Date()
    const diffTime = now.getTime() - date.getTime()
    const diffDays = Math.floor(diffTime / (1000 * 60 * 60 * 24))

    if (diffDays === 0) {
      return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    } else if (diffDays === 1) {
      return '昨天'
    } else if (diffDays < 7) {
      return `${diffDays}天前`
    } else {
      return date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
    }
  } catch {
    return dateStr
  }
}

const handleDelete = (e: Event, sessionId: string) => {
  e.stopPropagation()
  emit('deleteConversation', sessionId)
}
</script>

<template>
  <div class="w-72 h-full bg-white border-r border-slate-200 flex flex-col shadow-[4px_0_24px_rgba(15,123,107,0.04)]">
    <!-- 头部 -->
    <div class="p-4 border-b border-slate-100">
      <button
        @click="emit('create-new-conversation')"
        class="w-full bg-[#006053] hover:bg-[#0f7b6b] text-white font-sans text-sm font-bold py-3 px-4 rounded-xl flex items-center justify-center gap-2 shadow-md transition-all cursor-pointer active:scale-95"
      >
        <Plus class="w-4 h-4" />
        <span>新建对话</span>
      </button>
    </div>

    <!-- 历史对话列表 -->
    <div class="flex-1 overflow-y-auto p-3 space-y-2">
      <div class="px-2 pb-2">
        <h3 class="text-xs font-bold text-slate-400 uppercase tracking-wider">历史对话</h3>
      </div>

      <div v-if="loading" class="flex items-center justify-center py-8">
        <div class="w-6 h-6 border-2 border-[#006053] border-t-transparent rounded-full animate-spin"></div>
      </div>

      <div v-else-if="conversations.length === 0" class="text-center py-12 px-4">
        <div class="w-16 h-16 mx-auto mb-4 bg-slate-100 rounded-full flex items-center justify-center">
          <MessageCircle class="w-8 h-8 text-slate-400" />
        </div>
        <p class="text-sm text-slate-500">还没有历史对话</p>
        <p class="text-xs text-slate-400 mt-1">开始一个新对话吧</p>
      </div>

      <div v-else class="space-y-1">
        <div
          v-for="conv in conversations"
          :key="conv.sessionId"
          @click="emit('select-conversation', conv.sessionId)"
          :class="[
            'group rounded-xl p-3 cursor-pointer transition-all duration-200 border border-transparent',
            currentSessionId === conv.sessionId
              ? 'bg-[#E6F4F1] border-[#006053]/20'
              : 'hover:bg-slate-50 hover:border-slate-200'
          ]"
        >
          <div class="flex items-start gap-3">
            <!-- 场景图标 -->
            <div class="w-10 h-10 rounded-xl bg-[#b4ffed]/30 flex items-center justify-center text-xl shrink-0 select-none">
              {{ getScenarioInfo(conv.scenarioId).emoji }}
            </div>

            <!-- 内容区域 -->
            <div class="flex-1 min-w-0">
              <div class="flex items-center justify-between gap-2 mb-1">
                <h4 class="font-sans font-bold text-sm text-slate-800 truncate">
                  {{ conv.scenarioTitle || getScenarioInfo(conv.scenarioId).title }}
                </h4>
                <button
                  @click="handleDelete($event, conv.sessionId)"
                  class="w-7 h-7 flex items-center justify-center rounded-full opacity-0 group-hover:opacity-100 hover:bg-red-50 text-slate-400 hover:text-red-500 transition-all shrink-0"
                  title="删除对话"
                >
                  <Trash2 class="w-3.5 h-3.5" />
                </button>
              </div>
              <div class="flex items-center gap-2 text-xs text-slate-400">
                <span class="flex items-center gap-1">
                  <Clock class="w-3 h-3" />
                  {{ formatDate(conv.startTime) }}
                </span>
                <span>·</span>
                <span>{{ conv.messageCount }} 条</span>
                <span v-if="conv.overallScore !== null" class="text-[#006053] font-bold ml-auto">
                  {{ conv.overallScore }}分
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
