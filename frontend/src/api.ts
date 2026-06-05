import type { Scenario, PronunciationScore, GrammarFeedback, AiChatResponse, AgentInfo, PipelineConfig, ConversationSummary } from './types'

// API 基础配置
export const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080'

// 统一响应类型
interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

// 会话响应类型
interface ConversationResponse {
  sessionId: string
  startTime: string
}

// 消息响应类型
interface MessageResponse {
  id: string
  conversationId: string
  role: string
  text: string
  timestamp: string
}

// 练习总结类型
interface PracticeSummaryResponse {
  overallScore: number
  dimensions: {
    pronunciation: number
    grammar: number
    fluency: number
    vocabulary: number
    interactivity: number
  }
  errors: {
    original: string
    corrected: string
    type: string
  }[]
  suggestions: string[]
}

// 请求头配置
export const getHeaders = () => {
  return {
    'Content-Type': 'application/json',
  }
}

/**
 * API 服务层
 */
export const api = {
  /**
   * 获取所有场景列表
   */
  async getScenarios(): Promise<Scenario[]> {
    const response = await fetch(`${API_BASE_URL}/api/scenarios`, {
      method: 'GET',
      headers: getHeaders(),
    })
    const result: ApiResponse<Scenario[]> = await response.json()
    if (result.code !== 200) {
      throw new Error(result.message)
    }
    return result.data
  },

  /**
   * 获取单个场景详情（包含问题列表）
   */
  async getScenarioById(id: string): Promise<Scenario> {
    const response = await fetch(`${API_BASE_URL}/api/scenarios/${id}`, {
      method: 'GET',
      headers: getHeaders(),
    })
    const result: ApiResponse<Scenario> = await response.json()
    if (result.code !== 200) {
      throw new Error(result.message)
    }
    return result.data
  },

  /**
   * 创建新会话
   */
  async createConversation(scenarioId: string, userId?: string): Promise<ConversationResponse> {
    const response = await fetch(`${API_BASE_URL}/api/conversations`, {
      method: 'POST',
      headers: getHeaders(),
      body: JSON.stringify({ scenarioId, userId }),
    })
    const result: ApiResponse<ConversationResponse> = await response.json()
    if (result.code !== 200) {
      throw new Error(result.message)
    }
    return result.data
  },

  /**
   * 保存用户消息
   */
  async saveMessage(
    sessionId: string,
    text: string,
    useVoice: boolean = false,
    pronunciationScore?: number,
    grammarFeedback?: GrammarFeedback
  ): Promise<MessageResponse> {
    const response = await fetch(`${API_BASE_URL}/api/conversations/${sessionId}/messages`, {
      method: 'POST',
      headers: getHeaders(),
      body: JSON.stringify({
        text,
        useVoice,
        pronunciationScore,
        grammarFeedback,
      }),
    })
    const result: ApiResponse<MessageResponse> = await response.json()
    if (result.code !== 200) {
      throw new Error(result.message)
    }
    return result.data
  },

  /**
   * 获取对话历史
   */
  async getConversationHistory(sessionId: string): Promise<MessageResponse[]> {
    const response = await fetch(`${API_BASE_URL}/api/conversations/${sessionId}`, {
      method: 'GET',
      headers: getHeaders(),
    })
    const result: ApiResponse<MessageResponse[]> = await response.json()
    if (result.code !== 200) {
      throw new Error(result.message)
    }
    return result.data
  },

  /**
   * 获取练习总结
   */
  async getSummary(sessionId: string): Promise<PracticeSummaryResponse> {
    const response = await fetch(`${API_BASE_URL}/api/conversations/${sessionId}/summary`, {
      method: 'GET',
      headers: getHeaders(),
    })
    const result: ApiResponse<PracticeSummaryResponse> = await response.json()
    if (result.code !== 200) {
      throw new Error(result.message)
    }
    return result.data
  },

  /**
   * 结束会话（生成总结，不是删除）
   */
  async endConversation(sessionId: string): Promise<void> {
    const response = await fetch(`${API_BASE_URL}/api/conversations/${sessionId}`, {
      method: 'DELETE',
      headers: getHeaders(),
    })
    const result: ApiResponse<null> = await response.json()
    if (result.code !== 200) {
      throw new Error(result.message)
    }
  },

  /**
   * 获取用户的会话列表
   */
  async getConversations(userId: string = 'anonymous'): Promise<ConversationSummary[]> {
    const response = await fetch(
      `${API_BASE_URL}/api/conversations?userId=${encodeURIComponent(userId)}`,
      { method: 'GET', headers: getHeaders() }
    )
    const result: ApiResponse<ConversationSummary[]> = await response.json()
    if (result.code !== 200) throw new Error(result.message)
    return result.data
  },

  /**
   * 硬删除会话记录（含所有消息）
   */
  async deleteConversationRecords(sessionId: string): Promise<void> {
    const response = await fetch(`${API_BASE_URL}/api/conversations/${sessionId}/records`, {
      method: 'DELETE',
      headers: getHeaders(),
    })
    const result: ApiResponse<null> = await response.json()
    if (result.code !== 200) throw new Error(result.message)
  },

  /**
   * 发音评测
   */
  async evaluatePronunciation(
    audioBase64: string,
    referenceText: string
  ): Promise<PronunciationScore> {
    const response = await fetch(`${API_BASE_URL}/api/pronunciation/evaluate`, {
      method: 'POST',
      headers: getHeaders(),
      body: JSON.stringify({ audioBase64, referenceText }),
    })
    const result: ApiResponse<PronunciationScore> = await response.json()
    if (result.code !== 200) {
      throw new Error(result.message)
    }
    return result.data
  },

  // ====== ASR→LLM→TTS 管线 API (来自 everyone-can-use-english) ======

  /**
   * 获取可用 AI 角色列表
   * GET /api/practice/agents
   */
  async getAiAgents(): Promise<AgentInfo[]> {
    const response = await fetch(`${API_BASE_URL}/api/practice/agents`, {
      method: 'GET',
      headers: getHeaders(),
    })
    const result: ApiResponse<AgentInfo[]> = await response.json()
    if (result.code !== 200) throw new Error(result.message)
    return result.data
  },

  /**
   * AI 口语练习对话（触发 ASR→LLM→TTS 管线）
   * POST /api/practice/chat
   */
  async sendAiPracticeChat(
    sessionId: string,
    text: string,
    userId?: string,
    useAsr?: boolean,
    useTts?: boolean,
    pipelineConfig?: Partial<PipelineConfig>
  ): Promise<AiChatResponse> {
    const response = await fetch(`${API_BASE_URL}/api/practice/chat`, {
      method: 'POST',
      headers: getHeaders(),
      body: JSON.stringify({
        sessionId,
        text,
        userId,
        useAsr,
        useTts,
        pipelineConfig,
      }),
    })
    const result: ApiResponse<AiChatResponse> = await response.json()
    if (result.code !== 200) throw new Error(result.message)
    return result.data
  },

  /**
   * 流式 AI 口语练习对话（Server-Sent Events）
   * GET /api/practice/chat/stream
   */
  async *streamAiPracticeChat(
    sessionId: string,
    text: string,
    options?: {
      agentName?: string;
      llmEngine?: string;
      llmModel?: string;
      llmApiKey?: string;
      llmBaseUrl?: string;
    }
  ): AsyncGenerator<string, void, unknown> {
    const params = new URLSearchParams({
      sessionId,
      text,
      ...(options?.agentName && { agentName: options.agentName }),
      ...(options?.llmEngine && { llmEngine: options.llmEngine }),
      ...(options?.llmModel && { llmModel: options.llmModel }),
      ...(options?.llmApiKey && { llmApiKey: options.llmApiKey }),
      ...(options?.llmBaseUrl && { llmBaseUrl: options.llmBaseUrl }),
    })

    const response = await fetch(
      `${API_BASE_URL}/api/practice/chat/stream?${params}`,
      { headers: getHeaders() }
    )

    if (!response.ok) {
      throw new Error(`Stream request failed: ${response.status}`)
    }

    const reader = response.body?.getReader()
    if (!reader) {
      throw new Error('No response body')
    }

    const decoder = new TextDecoder()
    let buffer = ''

    try {
      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })

        // 处理 SSE 格式的数据
        // 格式: data: {...}\n\n
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (line.startsWith('data: ')) {
            const data = line.slice(6).trim()
            if (data && data !== '[DONE]') {
              yield data
            }
          }
        }
      }
    } finally {
      reader.releaseLock()
    }
  },

  /**
   * 获取用户管线配置
   * GET /api/settings/ai-pipeline?userId=xxx
   */
  async getAiPipelineConfig(userId: string): Promise<PipelineConfig> {
    const response = await fetch(
      `${API_BASE_URL}/api/settings/ai-pipeline?userId=${userId}`,
      { method: 'GET', headers: getHeaders() }
    )
    const result: ApiResponse<PipelineConfig> = await response.json()
    if (result.code !== 200) throw new Error(result.message)
    return result.data
  },

  /**
   * 保存用户管线配置
   * POST /api/settings/ai-pipeline?userId=xxx
   */
  async saveAiPipelineConfig(
    userId: string,
    config: Partial<PipelineConfig>
  ): Promise<void> {
    const response = await fetch(
      `${API_BASE_URL}/api/settings/ai-pipeline?userId=${userId}`,
      {
        method: 'POST',
        headers: getHeaders(),
        body: JSON.stringify(config),
      }
    )
    const result: ApiResponse<null> = await response.json()
    if (result.code !== 200) throw new Error(result.message)
  },

  /**
   * 测试 LLM 引擎连接
   * POST /api/practice/test-llm
   */
  async testLlmConnection(params: {
    engine: string
    apiKey: string
    baseUrl: string
    model: string
  }): Promise<{ success: boolean; message: string }> {
    const response = await fetch(`${API_BASE_URL}/api/practice/test-llm`, {
      method: 'POST',
      headers: getHeaders(),
      body: JSON.stringify(params),
    })
    const result: ApiResponse<{ success: boolean; message: string }> = await response.json()
    if (result.code !== 200) throw new Error(result.message)
    return result.data
  },
}

export default api
