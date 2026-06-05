<script setup lang="ts">
import { ref, watch, onMounted } from 'vue';
import { Settings, ArrowLeft, CheckCircle2, RefreshCw, Save, Wifi, WifiOff, Loader2 } from 'lucide-vue-next';
import { configService, type AppConfig } from '@/config';
import { voiceService } from '@/services/voice/VoiceService';
import { VoiceServiceFactory } from '@/services/voice/VoiceServiceFactory';

const emit = defineEmits<{
  goBack: [];
}>();

const config = ref<AppConfig>(configService.getConfig());

const saving = ref(false);
const saved = ref(false);
const showResetConfirm = ref(false);

// 测试连接相关状态
const testing = ref(false);
const testResult = ref<{ success: boolean; message: string; latency?: number } | null>(null);

// 获取支持的模型提供商
const supportedProviders = VoiceServiceFactory.getSupportedProviders();

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
  
  testing.value = true;
  testResult.value = null;
  
  try {
    // 先保存当前配置到 configService
    configService.updateConfig(config.value);
    
    const result = await voiceService.testConnection();
    testResult.value = result;
  } catch (error) {
    testResult.value = {
      success: false,
      message: error instanceof Error ? error.message : '测试失败'
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
