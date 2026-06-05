import type { Scenario, PronunciationScore, GrammarFeedback } from './types'

// API 基础配置
const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080'

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
const getHeaders = () => {
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
   * 结束会话
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
}

export default api
