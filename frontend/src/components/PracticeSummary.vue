<script setup lang="ts">
import { ref, computed } from 'vue'
import { Lightbulb, AlertTriangle, RefreshCw, Share2, ArrowLeft, ArrowUpRight, Info } from 'lucide-vue-next'
import type { Scenario, PronunciationScore, DialectMessage, PracticeSummaryResult } from '@/types'

interface Props {
  currentScenario: Scenario
  score: PronunciationScore
  messages: DialectMessage[]
  /** 可选的 LLM 详细评估结果（后端 EvaluationService 生成） */
  summaryResult?: PracticeSummaryResult | null
}

const props = defineProps<Props>()
const emit = defineEmits<{
  restartPractice: []
  goBackToScenarios: []
}>()

const copied = ref(false)
/** 当前展开评价文本的维度索引 */
const activeEvalIndex = ref<number | null>(null)

// ====== 统一数据适配层（同时支持旧版 PronunciationScore 和新版 PracticeSummaryResult） ======

/** 综合得分 */
const overallScore = computed(() => {
  return props.summaryResult?.overallScore ?? props.score.overall
})

/** 带详细评价的五维轴数据 */
const axes = computed(() => {
  if (props.summaryResult?.dimensions) {
    const d = props.summaryResult.dimensions
    return [
      { label: '发音', value: d.pronunciation.score, evaluation: d.pronunciation.evaluation, xOffset: 0, yOffset: -8 },
      { label: '语法', value: d.grammar.score, evaluation: d.grammar.evaluation, xOffset: 12, yOffset: 2 },
      { label: '流利度', value: d.fluency.score, evaluation: d.fluency.evaluation, xOffset: 8, yOffset: 10 },
      { label: '词汇', value: d.vocabulary.score, evaluation: d.vocabulary.evaluation, xOffset: -12, yOffset: 10 },
      { label: '互动', value: d.interactivity.score, evaluation: d.interactivity.evaluation, xOffset: -12, yOffset: 2 },
    ]
  }
  // 旧版 PronunciationScore 回退
  return [
    { label: '发音', value: props.score.accuracy || 85, evaluation: '', xOffset: 0, yOffset: -8 },
    { label: '语法', value: props.score.grammar || 82, evaluation: '', xOffset: 12, yOffset: 2 },
    { label: '流利度', value: props.score.fluency || 90, evaluation: '', xOffset: 8, yOffset: 10 },
    { label: '词汇', value: Math.round(((props.score.accuracy + props.score.grammar) / 2)) || 88, evaluation: '', xOffset: -12, yOffset: 10 },
    { label: '互动', value: 95, evaluation: '', xOffset: -12, yOffset: 2 },
  ]
})

/** 行动力提升建议列表 */
const suggestionsList = computed(() => {
  if (props.summaryResult?.suggestions && props.summaryResult.suggestions.length > 0) {
    return props.summaryResult.suggestions.map((s, i) => ({
      index: i + 1,
      title: s.title,
      description: s.description,
    }))
  }
  // 旧版硬编码回退
  return [
    {
      index: 1,
      title: '注意时态的连贯一致性 (Consistent Tense)',
      description: '在表达已发生的事情（如项目业绩、过往履历或刚才点完的牛排熟度）时，部分动词忘记使用过去式，建议刻意进行动词变化练习。'
    },
    {
      index: 2,
      title: '丰富句型逻辑连接词 (Cohesive Connectors)',
      description: '避免高频重复使用简单单一词 "and" 或 "but" 来串联语句。尝试加入如 "furthermore" (此外), "nonetheless" (然而) 等过渡词，赋予对话层层递进的逻辑感。'
    },
    {
      index: 3,
      title: '保持稳步调，着眼辅元音连读 (Phonetic Liaison)',
      description: '尽管您的表达非常流利，但过快的语速易造成少量音节吞咽现象。建议深呼吸保持声调节奏平缓，尤其注意如 "depends on" 一类弱读连写的音程过渡。'
    }
  ]
})

/** 语法/表达错误纠正列表 */
const correctionsList = computed(() => {
  if (props.summaryResult?.errors && props.summaryResult.errors.length > 0) {
    return props.summaryResult.errors.map(e => ({
      original: e.original,
      suggested: e.corrected,
      explanation: e.type,
    }))
  }
  // 从消息反馈中提取
  const realCorrections = props.messages
    .filter((msg) => msg.role === 'user' && msg.feedback)
    .map((msg) => ({
      original: msg.feedback!.original,
      suggested: msg.feedback!.suggested,
      explanation: msg.feedback!.explanation.split(' ')[0] || '语法 - 表达升级'
    }))
  if (realCorrections.length > 0) return realCorrections
  // 默认示例
  return [
    { original: "He go to the store yesterday.", suggested: "He went to the store yesterday.", explanation: "语法 - 时态" },
    { original: "I am very boring right now.", suggested: "I am very bored right now.", explanation: "词汇 - 词性区分" },
    { original: "It depends of the weather.", suggested: "It depends on the weather.", explanation: "词法 - 介词搭配" }
  ]
})

/** 是否有来自 LLM 的详细评价文本 */
const hasDetailedEvaluations = computed(() => {
  return props.summaryResult?.dimensions != null &&
    axes.value.some(a => a.evaluation && a.evaluation.length > 0)
})

/** 是否有来自 LLM 的真实语法纠正（非示例） */
const hasRealErrors = computed(() => {
  return props.summaryResult?.errors != null && props.summaryResult.errors.length > 0
})

// ====== 雷达图坐标计算 ======

const center = 100
const maxVal = 100
const radiusRange = 70

const getCoordinates = (index: number, val: number) => {
  const angle = (index * 2 * Math.PI) / 5 - Math.PI / 2
  const r = (val / maxVal) * radiusRange
  const x = center + r * Math.cos(angle)
  const y = center + r * Math.sin(angle)
  return { x, y }
}

const dataPoints = computed(() => axes.value.map((axis, i) => getCoordinates(i, axis.value)))
const dataPolygonPath = computed(() => dataPoints.value.map((p) => `${p.x},${p.y}`).join(' '))

const bgGridPoints100 = computed(() => axes.value.map((_, i) => getCoordinates(i, 100)))
const gridPath100 = computed(() => bgGridPoints100.value.map((p) => `${p.x},${p.y}`).join(' '))

const bgGridPoints70 = computed(() => axes.value.map((_, i) => getCoordinates(i, 70)))
const gridPath70 = computed(() => bgGridPoints70.value.map((p) => `${p.x},${p.y}`).join(' '))

const bgGridPoints40 = computed(() => axes.value.map((_, i) => getCoordinates(i, 40)))
const gridPath40 = computed(() => bgGridPoints40.value.map((p) => `${p.x},${p.y}`).join(' '))

const handleShareReport = () => {
  const reportText = `我在 LingoAI 完成了「${props.currentScenario.title}」场景的英语练习，综合得分 ${overallScore.value} 分！口语分析、时态订正和流利度均获得显著提高，快来一起体验AI面试演练吧！`
  navigator.clipboard.writeText(reportText)
  copied.value = true
  setTimeout(() => { copied.value = false }, 2000)
}

const toggleEvalDetail = (index: number) => {
  if (activeEvalIndex.value === index) {
    activeEvalIndex.value = null
  } else {
    activeEvalIndex.value = index
  }
}
</script>

<template>
  <div class="flex-1 w-full max-w-[1240px] mx-auto px-4 md:px-8 py-6 flex flex-col gap-6 animate-in fade-in duration-300">
    <div class="mb-4 text-center md:text-left flex flex-col md:flex-row md:items-end justify-between gap-4 border-b border-slate-100 pb-5">
      <div>
        <h2 class="font-display text-4xl font-extrabold text-[#121c2a] mb-1">
          练习总结
        </h2>
        <p class="font-sans text-sm text-slate-500 font-medium">
          干得漂亮！本轮模拟练习已顺利结束。这是 LingoAI 为你量身制定的口语成长档案。
        </p>
      </div>

      <button
        @click="emit('goBackToScenarios')"
        class="px-3.5 py-1.5 bg-white hover:bg-slate-50 border border-slate-200 text-xs font-bold text-slate-700 rounded-xl flex items-center justify-center gap-1.5 shadow-sm transition-all duration-150 cursor-pointer self-center md:self-end"
      >
        <ArrowLeft class="w-3.5 h-3.5 text-[#006053]" />
        <span>返回选择场景</span>
      </button>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-12 gap-8">
      <section class="lg:col-span-5 flex flex-col gap-6">
        <div class="bg-white rounded-3xl shadow-[0px_4px_24px_rgba(15,123,107,0.06)] border border-slate-50 p-6 md:p-8 text-center relative overflow-hidden group">
          <div class="absolute -right-8 -top-8 w-28 h-28 bg-[#0F7B6B]/5 rounded-full blur-2xl group-hover:bg-[#0F7B6B]/10 transition-colors"></div>
          <div class="absolute -left-8 -bottom-8 w-24 h-24 bg-amber-500/5 rounded-full blur-xl group-hover:bg-amber-500/10 transition-colors"></div>

          <h3 class="font-sans text-xs font-bold text-slate-400 tracking-widest uppercase mb-4 relative z-10">
            综合评估得分 (Overall Performance)
          </h3>

          <div class="flex items-end justify-center gap-1 relative z-10 mb-2">
            <span class="text-6xl md:text-7xl font-sans font-black text-[#006053] leading-none tracking-tight">
              {{ overallScore }}
            </span>
            <span class="font-display font-bold text-slate-400 text-sm md:text-base mb-1 md:mb-2">分</span>
          </div>

          <div class="inline-flex items-center gap-1.5 bg-[#b4ffed]/30 text-[#006053] px-3.5 py-1 rounded-full text-xs font-bold relative z-10 shadow-sm border border-[#0f7b6b]/10">
            <ArrowUpRight class="w-3.5 h-3.5 stroke-[2.5px]" />
            <span>本轮发音与词汇击败 91% 的同水平学员</span>
          </div>
        </div>

        <div class="bg-white rounded-3xl shadow-[0px_4px_24px_rgba(15,123,107,0.06)] border border-slate-50 p-6 md:p-8 flex flex-col items-center">
          <h3 class="font-display font-extrabold text-slate-800 text-sm md:text-base mb-6 text-center">
            五维能力评估模型分析
          </h3>

          <div class="w-full max-w-[280px] aspect-square relative select-none">
            <svg class="w-full h-full" viewBox="0 0 200 200">
              <polygon :points="gridPath100" class="stroke-slate-200 fill-none" stroke-width="1" />
              <polygon :points="gridPath70" class="stroke-slate-200 fill-none" stroke-width="1" stroke-dasharray="2,3" />
              <polygon :points="gridPath40" class="stroke-slate-200/60 fill-none" stroke-width="1" />

              <line
                v-for="(p, i) in bgGridPoints100"
                :key="i"
                :x1="center"
                :y1="center"
                :x2="p.x"
                :y2="p.y"
                class="stroke-slate-200"
                stroke-width="1"
              />

              <polygon
                :points="dataPolygonPath"
                class="fill-[#0f7b6b]/15 stroke-[#006053]"
                stroke-width="2.5"
                stroke-linecap="round"
                stroke-linejoin="round"
              />

              <circle
                v-for="(p, i) in dataPoints"
                :key="i"
                :cx="p.x"
                :cy="p.y"
                r="4.5"
                class="fill-[#006053] stroke-white"
                stroke-width="1.5"
              />
            </svg>

            <div
              v-for="(axis, i) in axes"
              :key="i"
              class="absolute font-sans font-bold text-[10px] md:text-xs text-slate-700 bg-white border border-slate-100 px-2 py-0.5 rounded-md shadow-sm cursor-pointer transition-all hover:border-[#006053] hover:shadow-md"
              :style="{
                left: `${(getCoordinates(i, 100).x / 200) * 100}%`,
                top: `${(getCoordinates(i, 100).y / 200) * 100}%`,
                transform: 'translate(-50%, -50%)',
                marginTop: `${axis.yOffset}px`,
                marginLeft: `${axis.xOffset}px`,
              }"
              @click="toggleEvalDetail(i)"
              :title="axis.evaluation ? '点击查看详细评价' : ''"
            >
              <span>{{ axis.label }}</span>
              <span class="font-mono text-[#006053] ml-1">{{ axis.value }}</span>
            </div>
          </div>

          <!-- 维度的详细评价文本 -->
          <div v-if="hasDetailedEvaluations && activeEvalIndex !== null && axes[activeEvalIndex]?.evaluation" class="mt-4 w-full">
            <div class="bg-[#eff4ff] rounded-xl p-4 border border-slate-100 animate-in fade-in slide-in-from-bottom-2 duration-200">
              <div class="flex items-start gap-2">
                <Info class="w-4 h-4 text-[#006053] mt-0.5 shrink-0" />
                <div>
                  <h4 class="font-sans text-xs font-bold text-slate-800 mb-1">{{ axes[activeEvalIndex].label }} 详细评价</h4>
                  <p class="font-sans text-xs text-slate-600 leading-relaxed">{{ axes[activeEvalIndex].evaluation }}</p>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section class="lg:col-span-7 flex flex-col gap-6">
        <div class="bg-white rounded-3xl shadow-[0px_4px_24px_rgba(15,123,107,0.06)] border border-slate-50 p-6 md:p-8">
          <h3 class="font-display font-extrabold text-slate-800 text-base mb-6 flex items-center gap-2">
            <Lightbulb class="w-5 h-5 text-amber-500 fill-amber-300" />
            <span>行动力提升建议</span>
          </h3>

          <div class="flex flex-col gap-4">
            <div
              v-for="suggestion in suggestionsList"
              :key="suggestion.index"
              class="flex gap-4 p-4 rounded-2xl bg-[#eff4ff] border border-slate-100 hover:border-[#0f7b6b]/10 transition-colors"
            >
              <div class="w-8 h-8 rounded-xl bg-[#0f7b6b]/10 text-[#006053] flex items-center justify-center shrink-0 font-display font-bold text-sm">
                {{ suggestion.index }}
              </div>
              <div>
                <h4 class="font-sans text-xs md:text-sm font-bold text-slate-800 mb-1">{{ suggestion.title }}</h4>
                <p class="font-sans text-xs text-slate-500 leading-relaxed">{{ suggestion.description }}</p>
              </div>
            </div>
          </div>
        </div>

        <div class="bg-white rounded-3xl shadow-[0px_4px_24px_rgba(15,123,107,0.06)] border border-slate-50 overflow-hidden flex flex-col pr-1">
          <div class="p-6 md:p-8 border-b border-slate-100 flex items-center gap-2">
            <AlertTriangle class="w-5 h-5 text-red-500" />
            <h3 class="font-display font-extrabold text-slate-800 text-base">
              本轮重点语法偏误及调优分析
            </h3>
          </div>

          <div class="overflow-x-auto">
            <table class="w-full text-left border-collapse min-w-[500px]">
              <thead>
                <tr class="bg-slate-50 font-sans text-[10px] font-bold text-slate-400 tracking-wider uppercase">
                  <th class="p-4 pl-6 md:pl-8 font-semibold">你曾说过或误用的表达</th>
                  <th class="p-4 font-semibold">LingoAI 推荐的高级替代表达</th>
                  <th class="p-4 pr-6 md:pr-8 font-semibold text-right">错误类型 / 标签</th>
                </tr>
              </thead>
              <tbody class="font-sans text-xs select-text">
                <tr
                  v-for="(row, idx) in correctionsList"
                  :key="idx"
                  class="border-b border-slate-100 hover:bg-slate-50/50 transition-all duration-150"
                >
                  <td class="p-4 pl-6 md:pl-8 font-mono text-xs text-slate-500 line-through decoration-red-400/70">
                    "{{ row.original }}"
                  </td>
                  <td class="p-4 text-emerald-800 font-semibold leading-relaxed">
                    "{{ row.suggested }}"
                  </td>
                  <td class="p-4 pr-6 md:pr-8 text-right">
                    <span class="inline-block px-2.5 py-0.5 bg-[#b4ffed]/30 text-[#006053] font-sans font-bold text-[9px] rounded-full uppercase tracking-wider">
                      {{ row.explanation }}
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div v-if="!hasRealErrors" class="font-sans text-[10px] md:text-xs text-slate-400 p-4 pl-8 border-t border-slate-50">
            💡 以上列出的是典型对话避坑词条。由于您在这次对话中未产生语法偏误，LingoAI 默认展示了常见黄金测试模板供您扩展积累！
          </div>
        </div>

        <div class="flex flex-col sm:flex-row gap-4 mt-2 mb-12">
          <button
            @click="emit('restartPractice')"
            class="flex-1 bg-[#E6F4F1] hover:bg-[#b4ffed]/30 text-[#006053] border border-[#bdc9c5]/30 font-sans text-xs font-bold py-3.5 rounded-xl flex items-center justify-center gap-1.5 active:scale-98 transition-all cursor-pointer shadow-sm"
          >
            <RefreshCw class="w-3.5 h-3.5" />
            <span>进入场景重新练习</span>
          </button>

          <button
            @click="handleShareReport"
            class="flex-1 bg-[#006053] hover:bg-[#0f7b6b] text-white font-sans text-xs font-bold py-3.5 rounded-xl flex items-center justify-center gap-1.5 shadow-md active:scale-98 transition-all cursor-pointer"
          >
            <Share2 class="w-3.5 h-3.5" />
            <span>{{ copied ? '链接复制成功！已黏贴' : '分享口语数据报告' }}</span>
          </button>
        </div>
      </section>
    </div>
  </div>
</template>
