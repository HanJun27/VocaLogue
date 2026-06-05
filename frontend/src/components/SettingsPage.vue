<script setup lang="ts">
import { ref, watch, onMounted } from 'vue';
import { Settings, ArrowLeft, CheckCircle2, RefreshCw, Save, Wifi, WifiOff, Loader2 } from 'lucide-vue-next';
import { configService, type AppConfig } from '@/config';
import { voiceService } from '@/services/voice/VoiceService';
import api from '@/api';
import type { AgentInfo } from '@/types';

const emit = defineEmits<{
  goBack: [];
}>();

const config = ref<AppConfig>(configService.getConfig());

const saving = ref(false);
const saved = ref(false);
const showResetConfirm = ref(false);
const loadingAgents = ref(false);
const savingPipeline = ref(false);

// AI 角色列表 - 从后端获取
const agentList = ref<AgentInfo[]>([]);

// 从后端加载 Agent 列表
const loadAgents = async () => {
  loadingAgents.value = true;
  try {
    const agents = await api.getAiAgents();
    agentList.value = agents;
  } catch (e) {
    console.warn('无法从后端加载 Agent 列表，使用默认值');
    agentList.value = [
      { name: 'Ava', description: '专业英语教师', language: 'en-US', ttsVoice: 'alloy', ttsModel: 'tts-1' },
      { name: 'Andrew', description: '美国朋友', language: 'en-US', ttsVoice: 'onyx', ttsModel: 'tts-1' },
      { name: 'Emma', description: '英式英语伙伴', language: 'en-GB', ttsVoice: 'shimmer', ttsModel: 'tts-1' },
      { name: 'Interviewer', description: '面试教练', language: 'en-US', ttsVoice: 'echo', ttsModel: 'tts-1' },
    ];
  } finally {
    loadingAgents.value = false;
  }
};

// 保存 Pipeline 配置到后端
const savePipelineConfig = async () => {
  savingPipeline.value = true;
  try {
    // 使用一个临时的 userId（实际项目中应该从用户登录信息获取）
    const userId = 'default-user';
    await api.saveAiPipelineConfig(userId, {
      useAsr: config.value.pipelineUseAsr,
      useTts: config.value.pipelineUseTts,
      agentName: config.value.pipelineAgentName,
      llmModel: config.value.pipelineLlmModel,
      ttsVoice: config.value.pipelineTtsVoice,
      ttsEngine: config.value.pipelineTtsEngine,
    });
  } catch (e) {
    console.warn('保存 Pipeline 配置到后端失败:', e);
    // 继续执行，因为本地配置已经保存
  } finally {
    savingPipeline.value = false;
  }
};

onMounted(() => {
  loadAgents();
});

// 测试连接相关状态
const testing = ref(false);
const testResult = ref<{ success: boolean; message: string; latency?: number } | null>(null);

// LLM 引擎测试状态
const testingLlm = ref(false);
const llmTestResult = ref<{ success: boolean; message: string; latency?: number } | null>(null);

// 表单验证
const validateConfig = () => {
  const errors: string[] = [];
  
  if (config.value.apiKey.trim().length === 0) {
    errors.push('API Key 不能为空');
  }
  
  // 豆包需要 App ID
  if (config.value.modelProvider === 'doubao' && !config.value.appId) {
    errors.push('豆包模型需要配置 App ID');
  }
  
  return errors.length > 0 ? errors.join('; ') : null;
};

const error = ref<string | null>(null);

const saveConfig = async () => {
  const validationError = validateConfig();
  if (validationError) {
    error.value = validationError;
    return;
  }

  error.value = null;
  saving.value = true;

  try {
    configService.updateConfig(config.value);
    
    // 如果启用了 AI 管线，同步配置到后端
    if (config.value.enableAiPipeline) {
      await savePipelineConfig();
    }

    saved.value = true;
    setTimeout(() => {
      saved.value = false;
    }, 2000);
  } catch (e) {
    error.value = '保存配置时出错';
  } finally {
    saving.value = false;
  }
};

const resetConfig = () => {
  showResetConfirm.value = false;
  configService.resetConfig();
  config.value = configService.getConfig();
  testResult.value = null;
};

// 测试 API 连接
const testConnection = async () => {
  const validationError = validateConfig();
  if (validationError) {
    error.value = validationError;
    testResult.value = {
      success: false,
      message: validationError
    };
    return;
  }
  
  console.log('[SettingsPage] 开始测试连接...');
  console.log('[SettingsPage] 配置信息:', {
    modelProvider: config.value.modelProvider,
    hasApiKey: !!config.value.apiKey,
    hasAppId: !!config.value.appId,
    apiKeyPrefix: config.value.apiKey ? config.value.apiKey.substring(0, 10) + '...' : '未设置'
  });
  
  testing.value = true;
  testResult.value = null;
  
  try {
    // 先保存当前配置到 configService
    configService.updateConfig(config.value);
    console.log('[SettingsPage] 配置已保存到 configService');
    
    const result = await voiceService.testConnection();
    console.log('[SettingsPage] 测试完成，结果:', result);
    testResult.value = result;
  } catch (error) {
    console.error('[SettingsPage] 测试连接异常:', error);
    testResult.value = {
      success: false,
      message: `测试失败: ${error instanceof Error ? error.message : '未知错误'}`
    };
  } finally {
    testing.value = false;
  }
};

// 当 API Key 改变时，清除之前的测试结果
watch(() => config.value.apiKey, () => {
  testResult.value = null;
});
watch(() => config.value.modelProvider, () => {
  testResult.value = null;
});
watch(() => config.value.appId, () => {
  testResult.value = null;
});

// LLM 配置改变时清除测试结果
watch(() => config.value.pipelineLlmEngine, () => {
  llmTestResult.value = null;
});
watch(() => config.value.pipelineLlmModel, () => {
  llmTestResult.value = null;
});
watch(() => config.value.deepseekApiKey, () => {
  llmTestResult.value = null;
});
watch(() => config.value.glmApiKey, () => {
  llmTestResult.value = null;
});
watch(() => config.value.glmApiUrl, () => {
  llmTestResult.value = null;
});
watch(() => config.value.qianwenApiKey, () => {
  llmTestResult.value = null;
});
watch(() => config.value.qianwenApiUrl, () => {
  llmTestResult.value = null;
});

// 测试 LLM 引擎连接
const testLlmConnection = async () => {
  const engine = config.value.pipelineLlmEngine;
  let apiKey = '';
  let baseUrl = '';
  
  // 根据引擎获取对应的 API Key 和 URL
  switch (engine) {
    case 'deepseek':
      apiKey = config.value.deepseekApiKey;
      baseUrl = 'https://api.deepseek.com/v1';
      break;
    case 'glm':
      apiKey = config.value.glmApiKey;
      baseUrl = config.value.glmApiUrl || 'https://open.bigmodel.cn/api/paas/v4';
      break;
    case 'qianwen':
      apiKey = config.value.qianwenApiKey;
      baseUrl = config.value.qianwenApiUrl || 'https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation';
      break;
    case 'doubao':
      apiKey = config.value.apiKey;
      baseUrl = 'https://api.doubao.com/v1';
      break;
    case 'openai':
      apiKey = config.value.apiKey;
      baseUrl = 'https://api.openai.com/v1';
      break;
    default:
      llmTestResult.value = { success: false, message: '未知的 LLM 引擎' };
      return;
  }
  
  // 验证 API Key
  if (!apiKey || apiKey.trim().length === 0) {
    llmTestResult.value = { success: false, message: '请先配置 API Key' };
    return;
  }
  
  testingLlm.value = true;
  llmTestResult.value = null;
  
  try {
    const startTime = Date.now();
    console.log(`[SettingsPage] 开始测试 ${engine} LLM 连接...`);
    
    // 先保存当前配置
    configService.updateConfig(config.value);
    
    // 调用后端测试 API
    const result = await api.testLlmConnection({
      engine,
      apiKey,
      baseUrl,
      model: config.value.pipelineLlmModel
    });
    
    const latency = Date.now() - startTime;
    llmTestResult.value = {
      success: true,
      message: result.message || '连接成功',
      latency
    };
    console.log(`[SettingsPage] ${engine} LLM 测试完成`, llmTestResult.value);
  } catch (error) {
    console.error(`[SettingsPage] ${engine} LLM 测试失败`, error);
    llmTestResult.value = {
      success: false,
      message: `测试失败: ${error instanceof Error ? error.message : '未知错误'}`
    };
  } finally {
    testingLlm.value = false;
  }
};

</script>

<template>
  <div class="bg-slate-50 min-h-screen flex flex-col">
    <!-- Header -->
    <header class="glass-panel sticky top-0 z-50 h-16 px-4 md:px-12 flex items-center gap-4 shadow-sm">
      <button @click="emit('goBack')" class="flex items-center gap-2 p-2 rounded-lg hover:bg-slate-100 transition-colors">
        <ArrowLeft class="w-5 h-5 text-slate-600" />
        <span class="font-semibold text-slate-700">返回</span>
      </button>
      <div class="flex items-center gap-3">
        <Settings class="w-6 h-6 text-[#006053]" />
        <h1 class="font-display text-xl font-bold text-slate-800">设置</h1>
      </div>
    </header>

    <!-- Main Content -->
    <main class="flex-1 p-4 md:p-8 max-w-4xl mx-auto w-full">
      <!-- Success Message -->
      <div v-if="saved" class="fixed top-20 right-4 bg-green-100 border border-green-300 text-green-800 px-4 py-2 rounded-lg shadow-md animate-in slide-in-from-right-4 fade-in">
        <CheckCircle2 class="w-5 h-5 inline mr-2" />
        配置已保存！
      </div>

      <!-- Error Message -->
      <div v-if="error" class="mb-6 bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-lg">
        {{ error }}
      </div>

      <div class="space-y-8">
        <!-- 语音大模型设置 -->
        <section class="bg-white rounded-2xl p-6 shadow-sm border border-slate-100">
          <h2 class="font-display text-lg font-bold text-slate-800 mb-6 flex items-center gap-2">
            <span class="w-3 h-3 rounded-full bg-[#006053]"></span>
            语音大模型设置
          </h2>

          <div class="space-y-6">
            <!-- 模型提供商 -->
            <div>
              <label class="block text-sm font-semibold text-slate-700 mb-2">模型提供商</label>
              <div class="flex gap-4">
                <button
                  @click="config.modelProvider = 'doubao'"
                  :class="[
                    'flex-1 py-3 px-4 rounded-xl border-2 transition-all font-medium',
                    config.modelProvider === 'doubao'
                      ? 'border-[#006053] bg-[#E6F4F1] text-[#006053]'
                      : 'border-slate-200 hover:border-slate-300 text-slate-600'
                  ]"
                >
                  豆包 Realtime
                </button>
                <button
                  @click="config.modelProvider = 'gpt-4o'"
                  :class="[
                    'flex-1 py-3 px-4 rounded-xl border-2 transition-all font-medium',
                    config.modelProvider === 'gpt-4o'
                      ? 'border-[#006053] bg-[#E6F4F1] text-[#006053]'
                      : 'border-slate-200 hover:border-slate-300 text-slate-600'
                  ]"
                >
                  GPT-4o Realtime
                </button>
              </div>
            </div>

            <!-- API Key -->
            <div>
              <label class="block text-sm font-semibold text-slate-700 mb-2">
                API Key
                <span class="text-slate-400 font-normal ml-1">* 必填</span>
              </label>
              <div class="relative">
                <input
                  v-model="config.apiKey"
                  type="password"
                  class="w-full py-3 px-4 border border-slate-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-[#006053]/30 focus:border-[#006053] font-mono text-sm"
                  :placeholder="config.modelProvider === 'doubao' ? '请输入豆包 API Key' : '请输入 OpenAI API Key'"
                />
              </div>
              <p class="mt-2 text-xs text-slate-500">
                您的 API Key 将安全存储在本地浏览器中
              </p>
            </div>

            <!-- App ID (仅豆包需要) -->
            <div v-if="config.modelProvider === 'doubao'">
              <label class="block text-sm font-semibold text-slate-700 mb-2">
                App ID
                <span class="text-slate-400 font-normal ml-1">* 豆包必填</span>
              </label>
              <div class="relative">
                <input
                  v-model="config.appId"
                  type="text"
                  class="w-full py-3 px-4 border border-slate-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-[#006053]/30 focus:border-[#006053] font-mono text-sm"
                  placeholder="请输入豆包 App ID"
                />
              </div>
              <p class="mt-2 text-xs text-slate-500">
                在火山引擎控制台获取 App ID
              </p>
            </div>

            <!-- Secret Key (仅豆包需要，与 Access Token 不同) -->
            <div v-if="config.modelProvider === 'doubao'" class="mt-4">
              <label class="block text-sm font-semibold text-slate-700 mb-2">
                Secret Key
                <span class="text-slate-400 font-normal ml-1">豆包推荐</span>
              </label>
              <div class="relative">
                <input
                  v-model="config.secretKey"
                  type="password"
                  class="w-full py-3 px-4 border border-slate-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-[#006053]/30 focus:border-[#006053] font-mono text-sm"
                  placeholder="请输入豆包 Secret Key（非 Access Token）"
                />
              </div>
              <p class="mt-2 text-xs text-slate-500">
                在火山引擎控制台获取 Secret Key。如果 Access Token 无效，系统会自动使用 Secret Key 重试
              </p>
            </div>
              <!-- 测试连接按钮 -->
              <div class="mt-4 flex items-center gap-3">
                <button
                  @click="testConnection"
                  :disabled="testing || !config.apiKey.trim()"
                  class="flex items-center gap-2 px-4 py-2 bg-[#E6F4F1] hover:bg-[#b4ffed]/50 border border-[#006053] text-[#006053] rounded-lg text-sm font-medium transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  <Loader2 v-if="testing" class="w-4 h-4 animate-spin" />
                  <Wifi v-else class="w-4 h-4" />
                  {{ testing ? '测试中...' : '测试连接' }}
                </button>
                
                <!-- 测试结果 -->
                <div v-if="testResult" :class="[
                  'flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm font-medium',
                  testResult.success ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
                ]">
                  <CheckCircle2 v-if="testResult.success" class="w-4 h-4" />
                  <WifiOff v-else class="w-4 h-4" />
                  {{ testResult.message }}
                  <span v-if="testResult.latency" class="text-xs opacity-70">
                    ({{ testResult.latency }}ms)
                  </span>
                </div>
              </div>
          </div>
        </section>

        <!-- 音频设置 -->
        <section class="bg-white rounded-2xl p-6 shadow-sm border border-slate-100">
          <h2 class="font-display text-lg font-bold text-slate-800 mb-6 flex items-center gap-2">
            <span class="w-3 h-3 rounded-full bg-amber-500"></span>
            音频设置
          </h2>

          <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
            <!-- 采样率 -->
            <div>
              <label class="block text-sm font-semibold text-slate-700 mb-2">采样率</label>
              <select
                v-model.number="config.sampleRate"
                class="w-full py-3 px-4 border border-slate-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-[#006053]/30 focus:border-[#006053]"
              >
                <option :value="16000">16000 Hz (推荐)</option>
                <option :value="24000">24000 Hz</option>
                <option :value="44100">44100 Hz</option>
              </select>
            </div>

            <!-- 音频块大小 -->
            <div>
              <label class="block text-sm font-semibold text-slate-700 mb-2">
                音频块大小 (ms)
              </label>
              <input
                v-model.number="config.audioChunkSize"
                type="number"
                min="100"
                max="1000"
                step="100"
                class="w-full py-3 px-4 border border-slate-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-[#006053]/30 focus:border-[#006053]"
              />
            </div>

            <!-- VAD 开关 -->
            <div class="md:col-span-2">
              <label class="flex items-center gap-3 cursor-pointer">
                <input
                  v-model="config.vadEnabled"
                  type="checkbox"
                  class="w-5 h-5 rounded border-slate-300 text-[#006053] focus:ring-[#006053]"
                />
                <span class="text-sm font-semibold text-slate-700">启用静音检测 (VAD)</span>
              </label>
            </div>

            <!-- VAD 静音持续时间 -->
            <div v-if="config.vadEnabled">
              <label class="block text-sm font-semibold text-slate-700 mb-2">
                静音检测时长 (ms)
              </label>
              <input
                v-model.number="config.vadSilenceDuration"
                type="number"
                min="200"
                max="2000"
                step="100"
                class="w-full py-3 px-4 border border-slate-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-[#006053]/30 focus:border-[#006053]"
              />
            </div>

            <!-- VAD 音量阈值 -->
            <div v-if="config.vadEnabled">
              <label class="block text-sm font-semibold text-slate-700 mb-2">
                音量阈值 (0-1)
              </label>
              <input
                v-model.number="config.vadThreshold"
                type="range"
                min="0"
                max="0.2"
                step="0.01"
                class="w-full accent-[#006053]"
              />
              <div class="text-center text-xs text-slate-500 mt-1">{{ config.vadThreshold }}</div>
            </div>
          </div>
        </section>

        <!-- ====== ASR→LLM→TTS 管线设置 (来自 everyone-can-use-english) ====== -->
        <section class="bg-white rounded-2xl p-6 shadow-sm border border-slate-100">
          <h2 class="font-display text-lg font-bold text-slate-800 mb-6 flex items-center gap-2">
            <span class="w-3 h-3 rounded-full bg-purple-500"></span>
            AI 口语陪练管线
          </h2>
          <p class="text-xs text-slate-500 mb-4 ml-1">
            启用后可体验 ASR→LLM→TTS 完整口语练习流程（来自 everyone-can-use-english 架构）
          </p>

          <div class="space-y-6">
            <!-- 总开关 -->
            <div>
              <label class="flex items-center gap-3 cursor-pointer">
                <input
                  v-model="config.enableAiPipeline"
                  type="checkbox"
                  class="w-5 h-5 rounded border-slate-300 text-purple-600 focus:ring-purple-500"
                />
                <span class="text-sm font-semibold text-slate-700">启用 ASR→LLM→TTS 管线</span>
              </label>
              <p class="text-xs text-slate-400 mt-1 ml-8">
                开启后，对话将经过"语音识别→AI 分析→语音合成"的完整流程
              </p>
            </div>

            <!-- 管线子开关（仅在总开关开启时显示） -->
            <template v-if="config.enableAiPipeline">
              <!-- ASR 开关 -->
              <div>
                <label class="flex items-center gap-3 cursor-pointer">
                  <input
                    v-model="config.pipelineUseAsr"
                    type="checkbox"
                    class="w-5 h-5 rounded border-slate-300 text-purple-600 focus:ring-purple-500"
                  />
                  <span class="text-sm font-semibold text-slate-700">ASR 语音识别后处理</span>
                </label>
                <p class="text-xs text-slate-400 mt-1 ml-8">
                  自动为语音识别结果添加标点符号，提升 AI 理解准确度
                </p>
              </div>

              <!-- TTS 开关 -->
              <div>
                <label class="flex items-center gap-3 cursor-pointer">
                  <input
                    v-model="config.pipelineUseTts"
                    type="checkbox"
                    class="w-5 h-5 rounded border-slate-300 text-purple-600 focus:ring-purple-500"
                  />
                  <span class="text-sm font-semibold text-slate-700">TTS 语音合成</span>
                </label>
                <p class="text-xs text-slate-400 mt-1 ml-8">
                  AI 回复自动转为语音播放（需要配置 OpenAI API Key）
                </p>
              </div>

              <!-- AI 角色选择 -->
              <div>
                <label class="block text-sm font-semibold text-slate-700 mb-2">AI 角色</label>
                <div class="grid grid-cols-2 gap-2">
                  <button
                    v-for="agent in agentList"
                    :key="agent.name"
                    @click="config.pipelineAgentName = agent.name"
                    :class="[
                      'py-2.5 px-3 rounded-xl border-2 transition-all text-sm font-medium text-left',
                      config.pipelineAgentName === agent.name
                        ? 'border-purple-500 bg-purple-50 text-purple-700'
                        : 'border-slate-200 hover:border-slate-300 text-slate-600'
                    ]"
                  >
                    <div class="font-bold">{{ agent.name }}</div>
                    <div class="text-[10px] mt-0.5 opacity-70">{{ agent.description }}</div>
                  </button>
                </div>
              </div>

              <!-- LLM 引擎选择 -->
              <div>
                <label class="block text-sm font-semibold text-slate-700 mb-2">LLM 引擎</label>
                <div class="grid grid-cols-2 md:grid-cols-3 gap-2">
                  <button
                    @click="config.pipelineLlmEngine = 'openai'"
                    :class="[
                      'py-2.5 px-3 rounded-xl border-2 transition-all text-sm font-medium text-center',
                      config.pipelineLlmEngine === 'openai'
                        ? 'border-purple-500 bg-purple-50 text-purple-700'
                        : 'border-slate-200 hover:border-slate-300 text-slate-600'
                    ]"
                  >
                    <div class="font-bold text-xs">OpenAI</div>
                    <div class="text-[10px] mt-0.5 opacity-70">GPT-4o/3.5</div>
                  </button>
                  <button
                    @click="config.pipelineLlmEngine = 'deepseek'"
                    :class="[
                      'py-2.5 px-3 rounded-xl border-2 transition-all text-sm font-medium text-center',
                      config.pipelineLlmEngine === 'deepseek'
                        ? 'border-purple-500 bg-purple-50 text-purple-700'
                        : 'border-slate-200 hover:border-slate-300 text-slate-600'
                    ]"
                  >
                    <div class="font-bold text-xs">DeepSeek</div>
                    <div class="text-[10px] mt-0.5 opacity-70">深度求索</div>
                  </button>
                  <button
                    @click="config.pipelineLlmEngine = 'glm'"
                    :class="[
                      'py-2.5 px-3 rounded-xl border-2 transition-all text-sm font-medium text-center',
                      config.pipelineLlmEngine === 'glm'
                        ? 'border-purple-500 bg-purple-50 text-purple-700'
                        : 'border-slate-200 hover:border-slate-300 text-slate-600'
                    ]"
                  >
                    <div class="font-bold text-xs">GLM</div>
                    <div class="text-[10px] mt-0.5 opacity-70">智谱清言</div>
                  </button>
                  <button
                    @click="config.pipelineLlmEngine = 'qianwen'"
                    :class="[
                      'py-2.5 px-3 rounded-xl border-2 transition-all text-sm font-medium text-center',
                      config.pipelineLlmEngine === 'qianwen'
                        ? 'border-purple-500 bg-purple-50 text-purple-700'
                        : 'border-slate-200 hover:border-slate-300 text-slate-600'
                    ]"
                  >
                    <div class="font-bold text-xs">Qianwen</div>
                    <div class="text-[10px] mt-0.5 opacity-70">通义千问</div>
                  </button>
                  <button
                    @click="config.pipelineLlmEngine = 'doubao'"
                    :class="[
                      'py-2.5 px-3 rounded-xl border-2 transition-all text-sm font-medium text-center',
                      config.pipelineLlmEngine === 'doubao'
                        ? 'border-purple-500 bg-purple-50 text-purple-700'
                        : 'border-slate-200 hover:border-slate-300 text-slate-600'
                    ]"
                  >
                    <div class="font-bold text-xs">Doubao</div>
                    <div class="text-[10px] mt-0.5 opacity-70">豆包</div>
                  </button>
                </div>
              </div>

              <!-- LLM 模型选择（根据引擎动态变化） -->
              <div>
                <label class="block text-sm font-semibold text-slate-700 mb-2">LLM 模型</label>
                <select
                  v-model="config.pipelineLlmModel"
                  class="w-full py-3 px-4 border border-slate-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-purple-500/30 focus:border-purple-500"
                >
                  <!-- OpenAI 模型 -->
                  <optgroup v-if="config.pipelineLlmEngine === 'openai'" label="OpenAI">
                    <option value="gpt-4o">GPT-4o (推荐)</option>
                    <option value="gpt-4o-mini">GPT-4o Mini (经济)</option>
                    <option value="gpt-4-turbo">GPT-4 Turbo</option>
                    <option value="gpt-3.5-turbo">GPT-3.5 Turbo</option>
                  </optgroup>

                  <!-- DeepSeek 模型 -->
                  <optgroup v-if="config.pipelineLlmEngine === 'deepseek'" label="DeepSeek">
                    <option value="deepseek-v4-pro">DeepSeek-V4-Pro (旗舰)</option>
                    <option value="deepseek-v4-flash">DeepSeek-V4-Flash (推荐)</option>
                    <option value="deepseek-chat">DeepSeek-Chat (稳定)</option>
                  </optgroup>

                  <!-- GLM 模型 -->
                  <optgroup v-if="config.pipelineLlmEngine === 'glm'" label="GLM-5 系列">
                    <option value="glm-5.1">GLM-5.1 (最新旗舰)</option>
                    <option value="glm-5">GLM-5 (高智能基座)</option>
                    <option value="glm-5-turbo">GLM-5-Turbo (龙虾增强)</option>
                  </optgroup>
                  <optgroup v-if="config.pipelineLlmEngine === 'glm'" label="GLM-4 系列">
                    <option value="glm-4.7">GLM-4.7 (高智能)</option>
                    <option value="glm-4.7-flashx">GLM-4.7-FlashX (轻量高速)</option>
                    <option value="glm-4.6">GLM-4.6 (超强性能)</option>
                    <option value="glm-4.5-air">GLM-4.5-Air (高性价比)</option>
                    <option value="glm-4.5-airx">GLM-4.5-AirX (极速版)</option>
                    <option value="glm-4-long">GLM-4-Long (超长输入)</option>
                    <option value="glm-4-flashx-250414">GLM-4-FlashX-250414 (高速低价)</option>
                    <option value="glm-4.7-flash">GLM-4.7-Flash (免费)</option>
                    <option value="glm-4-flash-250414">GLM-4-Flash-250414 (免费)</option>
                  </optgroup>

                  <!-- 通义千问模型 -->
                  <optgroup v-if="config.pipelineLlmEngine === 'qianwen'" label="通义千问">
                    <option value="qwen-2-max">Qwen-2-Max</option>
                    <option value="qwen-2-plus">Qwen-2-Plus</option>
                    <option value="qwen-2-turbo">Qwen-2-Turbo</option>
                    <option value="qwen-2-mini">Qwen-2-Mini</option>
                  </optgroup>

                  <!-- 豆包模型 -->
                  <optgroup v-if="config.pipelineLlmEngine === 'doubao'" label="豆包">
                    <option value="doubao-pro">豆包 Pro</option>
                    <option value="doubao-lite">豆包 Lite</option>
                    <option value="doubao-4">豆包 4.0</option>
                  </optgroup>
                </select>
              </div>

              <!-- 国产大模型 API Key 配置 -->
              <div v-if="config.pipelineLlmEngine === 'deepseek'" class="bg-amber-50 border border-amber-200 rounded-xl p-4">
                <label class="block text-sm font-semibold text-amber-800 mb-2">DeepSeek API Key</label>
                <input
                  v-model="config.deepseekApiKey"
                  type="password"
                  placeholder="sk-xxxxxxxxxxxxxxxxxxxxxxxx"
                  class="w-full py-3 px-4 border border-amber-300 rounded-xl focus:outline-none focus:ring-2 focus:ring-amber-500/30 focus:border-amber-500 bg-white"
                />
                <p class="text-xs text-amber-600 mt-1">获取地址: https://platform.deepseek.com/</p>
                <!-- 测试连接按钮 -->
                <div class="mt-3 flex items-center gap-3">
                  <button
                    @click="testLlmConnection"
                    :disabled="testingLlm || !config.deepseekApiKey.trim()"
                    class="flex items-center gap-2 px-4 py-2 bg-amber-100 hover:bg-amber-200 border border-amber-400 text-amber-700 rounded-lg text-sm font-medium transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    <Loader2 v-if="testingLlm" class="w-4 h-4 animate-spin" />
                    <Wifi v-else class="w-4 h-4" />
                    {{ testingLlm ? '测试中...' : '测试连接' }}
                  </button>
                  <div v-if="llmTestResult" :class="[
                    'flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm font-medium',
                    llmTestResult.success ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
                  ]">
                    <CheckCircle2 v-if="llmTestResult.success" class="w-4 h-4" />
                    <WifiOff v-else class="w-4 h-4" />
                    {{ llmTestResult.message }}
                    <span v-if="llmTestResult.latency" class="text-xs opacity-70">
                      ({{ llmTestResult.latency }}ms)
                    </span>
                  </div>
                </div>
              </div>

              <div v-if="config.pipelineLlmEngine === 'glm'" class="bg-blue-50 border border-blue-200 rounded-xl p-4">
                <div class="mb-3">
                  <label class="block text-sm font-semibold text-blue-800 mb-2">GLM API Key</label>
                  <input
                    v-model="config.glmApiKey"
                    type="password"
                    placeholder="sk-xxxxxxxxxxxxxxxxxxxxxxxx"
                    class="w-full py-3 px-4 border border-blue-300 rounded-xl focus:outline-none focus:ring-2 focus:ring-blue-500/30 focus:border-blue-500 bg-white"
                  />
                  <p class="text-xs text-blue-600 mt-1">获取地址: https://open.bigmodel.cn/</p>
                </div>
                <div class="mb-3">
                  <label class="block text-sm font-semibold text-blue-800 mb-2">GLM API URL</label>
                  <input
                    v-model="config.glmApiUrl"
                    type="text"
                    placeholder="https://open.bigmodel.cn/api/paas/v4/"
                    class="w-full py-3 px-4 border border-blue-300 rounded-xl focus:outline-none focus:ring-2 focus:ring-blue-500/30 focus:border-blue-500 bg-white"
                  />
                </div>
                <!-- 测试连接按钮 -->
                <div class="flex items-center gap-3">
                  <button
                    @click="testLlmConnection"
                    :disabled="testingLlm || !config.glmApiKey.trim()"
                    class="flex items-center gap-2 px-4 py-2 bg-blue-100 hover:bg-blue-200 border border-blue-400 text-blue-700 rounded-lg text-sm font-medium transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    <Loader2 v-if="testingLlm" class="w-4 h-4 animate-spin" />
                    <Wifi v-else class="w-4 h-4" />
                    {{ testingLlm ? '测试中...' : '测试连接' }}
                  </button>
                  <div v-if="llmTestResult" :class="[
                    'flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm font-medium',
                    llmTestResult.success ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
                  ]">
                    <CheckCircle2 v-if="llmTestResult.success" class="w-4 h-4" />
                    <WifiOff v-else class="w-4 h-4" />
                    {{ llmTestResult.message }}
                    <span v-if="llmTestResult.latency" class="text-xs opacity-70">
                      ({{ llmTestResult.latency }}ms)
                    </span>
                  </div>
                </div>
              </div>

              <div v-if="config.pipelineLlmEngine === 'qianwen'" class="bg-cyan-50 border border-cyan-200 rounded-xl p-4">
                <div class="mb-3">
                  <label class="block text-sm font-semibold text-cyan-800 mb-2">通义千问 API Key</label>
                  <input
                    v-model="config.qianwenApiKey"
                    type="password"
                    placeholder="sk-xxxxxxxxxxxxxxxxxxxxxxxx"
                    class="w-full py-3 px-4 border border-cyan-300 rounded-xl focus:outline-none focus:ring-2 focus:ring-cyan-500/30 focus:border-cyan-500 bg-white"
                  />
                  <p class="text-xs text-cyan-600 mt-1">获取地址: https://dashscope.aliyun.com/</p>
                </div>
                <div class="mb-3">
                  <label class="block text-sm font-semibold text-cyan-800 mb-2">通义千问 API URL</label>
                  <input
                    v-model="config.qianwenApiUrl"
                    type="text"
                    placeholder="https://dashscope.aliyuncs.com/api/v1/..."
                    class="w-full py-3 px-4 border border-cyan-300 rounded-xl focus:outline-none focus:ring-2 focus:ring-cyan-500/30 focus:border-cyan-500 bg-white"
                  />
                </div>
                <!-- 测试连接按钮 -->
                <div class="flex items-center gap-3">
                  <button
                    @click="testLlmConnection"
                    :disabled="testingLlm || !config.qianwenApiKey.trim()"
                    class="flex items-center gap-2 px-4 py-2 bg-cyan-100 hover:bg-cyan-200 border border-cyan-400 text-cyan-700 rounded-lg text-sm font-medium transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    <Loader2 v-if="testingLlm" class="w-4 h-4 animate-spin" />
                    <Wifi v-else class="w-4 h-4" />
                    {{ testingLlm ? '测试中...' : '测试连接' }}
                  </button>
                  <div v-if="llmTestResult" :class="[
                    'flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm font-medium',
                    llmTestResult.success ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
                  ]">
                    <CheckCircle2 v-if="llmTestResult.success" class="w-4 h-4" />
                    <WifiOff v-else class="w-4 h-4" />
                    {{ llmTestResult.message }}
                    <span v-if="llmTestResult.latency" class="text-xs opacity-70">
                      ({{ llmTestResult.latency }}ms)
                    </span>
                  </div>
                </div>
              </div>

              <div v-if="config.pipelineLlmEngine === 'doubao'" class="bg-green-50 border border-green-200 rounded-xl p-4">
                <p class="text-xs text-green-700 mb-3">
                  豆包 API Key 已在上方"语音大模型设置"中配置。如需单独使用豆包进行口语陪练，请确保已配置正确。
                </p>
                <!-- 测试连接按钮 -->
                <div class="flex items-center gap-3">
                  <button
                    @click="testLlmConnection"
                    :disabled="testingLlm || !config.apiKey.trim()"
                    class="flex items-center gap-2 px-4 py-2 bg-green-100 hover:bg-green-200 border border-green-400 text-green-700 rounded-lg text-sm font-medium transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    <Loader2 v-if="testingLlm" class="w-4 h-4 animate-spin" />
                    <Wifi v-else class="w-4 h-4" />
                    {{ testingLlm ? '测试中...' : '测试连接' }}
                  </button>
                  <div v-if="llmTestResult" :class="[
                    'flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm font-medium',
                    llmTestResult.success ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
                  ]">
                    <CheckCircle2 v-if="llmTestResult.success" class="w-4 h-4" />
                    <WifiOff v-else class="w-4 h-4" />
                    {{ llmTestResult.message }}
                    <span v-if="llmTestResult.latency" class="text-xs opacity-70">
                      ({{ llmTestResult.latency }}ms)
                    </span>
                  </div>
                </div>
              </div>

              <!-- OpenAI 测试连接 -->
              <div v-if="config.pipelineLlmEngine === 'openai'" class="bg-purple-50 border border-purple-200 rounded-xl p-4">
                <p class="text-xs text-purple-700 mb-3">
                  OpenAI API Key 已在上方"语音大模型设置"中配置。
                </p>
                <!-- 测试连接按钮 -->
                <div class="flex items-center gap-3">
                  <button
                    @click="testLlmConnection"
                    :disabled="testingLlm || !config.apiKey.trim()"
                    class="flex items-center gap-2 px-4 py-2 bg-purple-100 hover:bg-purple-200 border border-purple-400 text-purple-700 rounded-lg text-sm font-medium transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    <Loader2 v-if="testingLlm" class="w-4 h-4 animate-spin" />
                    <Wifi v-else class="w-4 h-4" />
                    {{ testingLlm ? '测试中...' : '测试连接' }}
                  </button>
                  <div v-if="llmTestResult" :class="[
                    'flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm font-medium',
                    llmTestResult.success ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
                  ]">
                    <CheckCircle2 v-if="llmTestResult.success" class="w-4 h-4" />
                    <WifiOff v-else class="w-4 h-4" />
                    {{ llmTestResult.message }}
                    <span v-if="llmTestResult.latency" class="text-xs opacity-70">
                      ({{ llmTestResult.latency }}ms)
                    </span>
                  </div>
                </div>
              </div>

              <!-- TTS 语音引擎和声音 -->
              <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                  <label class="block text-sm font-semibold text-slate-700 mb-2">TTS 引擎</label>
                  <select
                    v-model="config.pipelineTtsEngine"
                    class="w-full py-3 px-4 border border-slate-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-purple-500/30 focus:border-purple-500"
                  >
                    <option value="openai">OpenAI TTS</option>
                    <option value="enjoyai">EnjoyAI (Azure 代理)</option>
                  </select>
                </div>

                <div>
                  <label class="block text-sm font-semibold text-slate-700 mb-2">TTS 声音</label>
                  <select
                    v-model="config.pipelineTtsVoice"
                    class="w-full py-3 px-4 border border-slate-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-purple-500/30 focus:border-purple-500"
                  >
                    <option value="alloy">Alloy (中性温暖)</option>
                    <option value="echo">Echo (男性沉稳)</option>
                    <option value="fable">Fable (英式叙述)</option>
                    <option value="onyx">Onyx (男性深沉)</option>
                    <option value="nova">Nova (女性明亮)</option>
                    <option value="shimmer">Shimmer (女性柔和)</option>
                  </select>
                </div>
              </div>
            </template>
          </div>
        </section>

        <!-- 保存按钮 -->
        <div class="flex items-center gap-4">
          <button
            @click="saveConfig"
            :disabled="saving"
            class="flex-1 md:flex-none py-3 px-8 bg-[#006053] text-white rounded-xl font-semibold hover:bg-[#0F7B6B] transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
          >
            <Save class="w-5 h-5" />
            {{ saving ? '保存中...' : '保存配置' }}
          </button>

          <button
            @click="showResetConfirm = true"
            class="py-3 px-6 border border-slate-200 text-slate-600 rounded-xl font-medium hover:bg-slate-50 transition-colors flex items-center gap-2"
          >
            <RefreshCw class="w-5 h-5" />
            重置
          </button>
        </div>
      </div>
    </main>

    <!-- 重置确认弹窗 -->
    <div v-if="showResetConfirm" class="fixed inset-0 bg-slate-900/50 backdrop-blur-sm z-50 flex items-center justify-center p-4">
      <div class="bg-white rounded-2xl p-6 max-w-sm w-full shadow-2xl animate-in zoom-in-95">
        <h3 class="font-display text-lg font-bold text-slate-800 mb-2">确认重置？</h3>
        <p class="text-slate-600 text-sm mb-6">这将恢复所有设置为默认值，包括 API Key</p>
        <div class="flex gap-3">
          <button @click="showResetConfirm = false" class="flex-1 py-2 px-4 border border-slate-200 text-slate-600 rounded-lg font-medium hover:bg-slate-50">取消</button>
          <button @click="resetConfig" class="flex-1 py-2 px-4 bg-red-500 text-white rounded-lg font-medium hover:bg-red-600">重置</button>
        </div>
      </div>
    </div>
  </div>
</template>
