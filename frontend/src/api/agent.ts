/** 封装 Agent 工具、配置、记忆、会话和审批请求，并分发聊天事件流。 */

import { USE_MOCK } from '../config'
import type { KnowledgeSourceDTO } from '../types'
import { useAuth } from '../stores/auth'
import { http } from './http'
import {
  mockCreateAgentProfile,
  mockDeleteAgentProfile,
  mockListAgentProfiles,
  mockListAgentTools,
  mockStreamAgentChat,
  mockUpdateAgentProfile,
  mockCreateAgentSession,
  mockListAgentSessions,
  mockAgentSessionHistory,
  mockDeleteAgentSession,
  mockListAgentMemories,
  mockCorrectAgentMemory,
  mockDeleteAgentMemory,
} from './mock'
import { parseJsonFrame, readSseFrames } from './sse'

/**
 * Agent 对话与配置的 HTTP 客户端。
 *
 * 对话流式事件见后端 `AgentEvent`：
 *   accepted / round / delta / tool_call / tool_result / answer / done / error
 *
 * 关键语义（与后端一致，不要在 UI 层另立规则）：
 *   - `delta` 只是「本轮正在生成」，**不代表是答案**：模型发起工具调用前流出的文字是引子，
 *     收到 `tool_call` 就应把这段文字从答案区移入步骤区；
 *   - `answer` 是终止轮确认的整段答案，UI 以它为准收口；
 *   - `done` 一定在最后（stopReason / token 统计），`error` 表示回合失败；
 *   - `sources` 只来自服务端给出的获准片段，不能从工具摘要或原始候选自行推导。
 */

export type AgentKbSelectionMode = 'all' | 'selected' | 'none'
export type AgentAnswerPolicy = 'grounded' | 'general'

/** GET /agent/tools：当前服务实际注册的工具公开目录。 */
export interface AgentToolDTO {
  name: string
  description: string | null
  requiresApproval: boolean
}

/**
 * 读取当前后端提供的 Agent 工具及其说明。
 * 返回 AgentToolDTO[]。
 * 由 AgentProfileManager.vue 的 loadToolCatalog 触发。
 * 下游调用 mock 的 mockListAgentTools。
 */
export const listAgentTools = (): Promise<AgentToolDTO[]> =>
  USE_MOCK
    ? mockListAgentTools()
    : http.get<AgentToolDTO[], AgentToolDTO[]>('/agent/tools')

/** GET/POST/PUT /agent/profiles 的配置视图。 */
export interface AgentProfileDTO {
  id: number
  name: string
  chatModelId: string | null
  systemPrompt: string | null
  answerPolicy: AgentAnswerPolicy
  memoryReadEnabled: boolean
  memoryWriteEnabled: boolean
  memorySuggestionsEnabled?: boolean
  kbSelectionMode: AgentKbSelectionMode
  knowledgeBaseIds: number[]
  allowedTools: string[]
  createTime?: string
  updateTime?: string
}

/** 创建 / 更新 Agent 配置的请求体；知识库与工具都是执行上限，服务端会再次求交。 */
export interface AgentProfileRequestDTO {
  name: string
  chatModelId: string | null
  systemPrompt: string | null
  answerPolicy: AgentAnswerPolicy
  memoryReadEnabled: boolean
  memoryWriteEnabled: boolean
  memorySuggestionsEnabled?: boolean
  kbSelectionMode: AgentKbSelectionMode
  knowledgeBaseIds: number[]
  allowedTools: string[]
}

/**
 * 读取账号可用的 Agent 配置。
 * 返回 AgentProfileDTO[]。
 * 由 AgentView.vue 的 loadProfiles 触发。
 * 下游调用 mock 的 mockListAgentProfiles。
 */
export const listAgentProfiles = (): Promise<AgentProfileDTO[]> =>
  USE_MOCK
    ? mockListAgentProfiles()
    : http.get<AgentProfileDTO[], AgentProfileDTO[]>('/agent/profiles')

/**
 * 提交自建 Agent 的模型、权限与行为配置。
 * 接收request，返回Agent 配置记录。
 * 由 AgentProfileManager.vue 的 save 触发。
 * 下游调用 mock 的 mockCreateAgentProfile。
 */
export const createAgentProfile = (request: AgentProfileRequestDTO): Promise<AgentProfileDTO> =>
  USE_MOCK
    ? mockCreateAgentProfile(request)
    : http.post<AgentProfileDTO, AgentProfileDTO>('/agent/profiles', request)

/**
 * 更新指定 Agent 配置并接收保存后的记录。
 * 接收Agent 配置 ID、request，返回Agent 配置记录。
 * 由 AgentProfileManager.vue 的 save 触发。
 * 下游调用 mock 的 mockUpdateAgentProfile。
 */
export const updateAgentProfile = (id: number, request: AgentProfileRequestDTO): Promise<AgentProfileDTO> =>
  USE_MOCK
    ? mockUpdateAgentProfile(id, request)
    : http.put<AgentProfileDTO, AgentProfileDTO>(`/agent/profiles/${id}`, request)

/**
 * 删除指定自建 Agent 并返回后端结果。
 * 接收Agent 配置 ID。
 * 由 AgentProfileManager.vue 的 remove 触发。
 * 下游调用 mock 的 mockDeleteAgentProfile。
 */
export const deleteAgentProfile = (id: number): Promise<string> =>
  USE_MOCK
    ? mockDeleteAgentProfile(id)
    : http.delete<string, string>(`/agent/profiles/${id}`)

export type AgentMemoryKind = 'FACT' | 'PREFERENCE' | 'PROFILE'

export interface AgentMemoryDTO {
  id: string
  kind: AgentMemoryKind
  content: string
  createdAt: string
  updatedAt: string | null
}

export interface AgentMemoryListDTO {
  enabled: boolean
  maxItems: number
  maxContentChars: number
  items: AgentMemoryDTO[]
}

export interface AgentMemoryCorrectionDTO { content: string; kind: AgentMemoryKind }

/**
 * 读取本账号的长期记忆及其状态。
 * 返回 AgentMemoryListDTO。
 * 由 AgentMemoryManager.vue 的 load 触发。
 * 下游调用 mock 的 mockListAgentMemories。
 */
export const listAgentMemories = (): Promise<AgentMemoryListDTO> =>
  USE_MOCK ? mockListAgentMemories()
    : http.get<AgentMemoryListDTO, AgentMemoryListDTO>('/agent/memories')

/**
 * 提交指定记忆的修订内容并返回新记录。
 * 接收记忆 ID、request。
 * 由 AgentMemoryManager.vue 的 save 触发。
 * 下游调用 mock 的 mockCorrectAgentMemory。
 */
export const correctAgentMemory = (id: string, request: AgentMemoryCorrectionDTO): Promise<AgentMemoryDTO> =>
  USE_MOCK ? mockCorrectAgentMemory(id, request)
    : http.put<AgentMemoryDTO, AgentMemoryDTO>(`/agent/memories/${encodeURIComponent(id)}`, request)

/**
 * 删除账号下的指定长期记忆。
 * 接收记忆 ID，返回文本。
 * 由 AgentMemoryManager.vue 的 remove 触发。
 * 下游调用 mock 的 mockDeleteAgentMemory。
 */
export const deleteAgentMemory = (id: string): Promise<string> =>
  USE_MOCK ? mockDeleteAgentMemory(id)
    : http.delete<string, string>(`/agent/memories/${encodeURIComponent(id)}`)

export interface AgentStep {
  round: number
  phase: string
  inputTokensBefore?: number
  inputTokensAfter?: number
  inputBudget?: number
}

export interface AgentToolCallEvent {
  round: number
  tool: string
  args?: string | null
}

export interface AgentToolResultEvent {
  round: number
  tool: string
  ok: boolean
  /** 知识工具的检索结果；与 ok 分开，no_evidence 不表示依赖故障。 */
  outcome?: 'success' | 'no_evidence' | 'dependency_unavailable' | 'timeout' | null
  reasonCode?: string | null
  summary?: string | null
  error?: string | null
}

/** `answer` 与 `done` 表达同一来源集合；字段沿用 RAG 的公开来源 DTO。 */
export interface AgentAnswerEvent {
  text: string
  synthesized?: boolean
  sources?: KnowledgeSourceDTO[]
  outcome?: string
  reasonCode?: string | null
}

export interface AgentDoneEvent {
  stopReason?: string
  iterations?: number
  toolCalls?: number
  promptTokens?: number
  completionTokens?: number
  synthesized?: boolean
  degraded?: boolean
  degradeReason?: string
  sources?: KnowledgeSourceDTO[]
  outcome?: string
  reasonCode?: string | null
  memoryApproval?: MemorySaveApproval
}

export interface MemorySaveApproval {
  id: string
  content: string | null
  kind: string | null
  state: 'PENDING' | 'PROCESSING' | 'SAVED' | 'DUPLICATE' | 'REJECTED' | 'FAILED' | 'EXPIRED'
  expiresAt: number
}

export interface AgentSessionDTO {
  id: string
  agentId: number | null
  answerPolicy: AgentAnswerPolicy
  title: string
  createdAt: number
  updatedAt: number
  expiresAt: number
}

export interface AgentSessionTurnDTO extends AgentDoneEvent {
  id: string
  question: string
  answer: string | null
  steps: { tool: string; ok: boolean; summary: string | null; outcome?: AgentToolResultEvent['outcome']; reasonCode?: string | null }[]
  completedAt: number
}

export interface AgentSessionPageDTO {
  items: AgentSessionDTO[]
  nextOffset: number
  hasMore: boolean
}

/** items 按时间正序；offset 从最新回合向前翻页，每次最多 50 轮。 */
export interface AgentSessionHistoryDTO {
  session: AgentSessionDTO
  items: AgentSessionTurnDTO[]
  nextOffset: number
  hasMore: boolean
}

/**
 * 为所选 Agent 创建服务端对话会话。
 * 接收Agent 配置 ID，返回已创建的会话记录。
 * 由 AgentView.vue 的 newConversation、AgentView.vue 的 send 触发。
 * 下游调用 mock 的 mockCreateAgentSession。
 */
export const createAgentSession = (agentId: number | null): Promise<AgentSessionDTO> =>
  USE_MOCK ? mockCreateAgentSession(agentId)
    : http.post<AgentSessionDTO, AgentSessionDTO>('/agent/sessions', { agentId })

/**
 * 按偏移量读取 Agent 会话目录。
 * 接收查询偏移量，返回 AgentSessionPageDTO。
 * 由 AgentView.vue 的 loadSessions 触发。
 * 下游调用 mock 的 mockListAgentSessions。
 */
export const listAgentSessions = (offset = 0): Promise<AgentSessionPageDTO> =>
  USE_MOCK ? mockListAgentSessions(offset)
    : http.get<AgentSessionPageDTO, AgentSessionPageDTO>('/agent/sessions', { params: { offset, limit: 30 } })

/**
 * 分页读取指定 Agent 会话已经完成的回合。
 * 接收会话 ID、查询偏移量，返回 AgentSessionHistoryDTO。
 * 由 AgentView.vue 的 loadHistory 触发。
 * 下游调用 mock 的 mockAgentSessionHistory。
 */
export const getAgentSessionHistory = (id: string, offset = 0): Promise<AgentSessionHistoryDTO> =>
  USE_MOCK ? mockAgentSessionHistory(id, offset)
    : http.get<AgentSessionHistoryDTO, AgentSessionHistoryDTO>(`/agent/sessions/${encodeURIComponent(id)}/history`,
      { params: { offset, limit: 20 } })

/**
 * 删除指定 Agent 会话及其历史。
 * 接收会话 ID，返回文本。
 * 由 AgentView.vue 的 removeConversation 触发。
 * 下游调用 mock 的 mockDeleteAgentSession。
 */
export const deleteAgentSession = (id: string): Promise<string> =>
  USE_MOCK ? mockDeleteAgentSession(id)
    : http.delete<string, string>(`/agent/sessions/${encodeURIComponent(id)}`)

/**
 * 用会话 ID 和申请 ID 构造记忆审批接口路径。
 * 接收会话 ID、记忆申请 ID，返回文本。
 * 由 getMemorySaveApproval、decideMemorySaveApproval 触发。
 */
function memoryApprovalPath(sessionId: string, approvalId: string): string {
  return `/agent/sessions/${encodeURIComponent(sessionId)}/memory-approvals/${encodeURIComponent(approvalId)}`
}

/**
 * 读取指定会话的记忆申请与当前判定状态。
 * 接收会话 ID、记忆申请 ID，返回 MemorySaveApproval。
 * 由 MemorySaveApprovalCard.vue 的 refresh 触发。
 * 下游调用 MemorySaveApprovalController、memoryApprovalPath。
 */
export const getMemorySaveApproval = (sessionId: string, approvalId: string): Promise<MemorySaveApproval> =>
  http.get<MemorySaveApproval, MemorySaveApproval>(memoryApprovalPath(sessionId, approvalId))

/**
 * 提交申请的批准或拒绝并返回服务端状态。
 * 接收会话 ID、记忆申请 ID、审批决定。
 * 由 MemorySaveApprovalCard.vue 的 decide 触发。
 * 下游调用 MemorySaveApprovalController、memoryApprovalPath。
 */
export const decideMemorySaveApproval = (sessionId: string, approvalId: string, decision: 'APPROVE' | 'REJECT'):
Promise<MemorySaveApproval> =>
  http.post<MemorySaveApproval, MemorySaveApproval>(memoryApprovalPath(sessionId, approvalId) + '/decision', { decision })

export interface AgentErrorEvent {
  code?: string
  message?: string
  outcome?: string
  reasonCode?: string
}

export interface AgentStreamHandlers {
  onAccepted?: (payload: { maxIterations: number; maxToolCalls: number }) => void
  onRound?: (step: AgentStep) => void
  onDelta?: (payload: { round: number; text: string; channel?: string }) => void
  onToolCall?: (payload: AgentToolCallEvent) => void
  onToolResult?: (payload: AgentToolResultEvent) => void
  onAnswer?: (payload: AgentAnswerEvent) => void
  onDone?: (payload: AgentDoneEvent) => void
  onError?: (payload: AgentErrorEvent) => void
}

/** 流式请求本身的失败（连接、HTTP 状态、配置/授权被拒），与 SSE 的 error 帧区分。 */
export class AgentStreamError extends Error {
  /** 保留 Agent 流式请求错误的提示、HTTP 状态和业务原因码。接收待显示的消息、状态、code。下游调用 function Object() { [native code] }。 */
  constructor(
    message: string,
    readonly status?: number,
    readonly code?: string,
  ) {
    super(message)
    this.name = 'AgentStreamError'
  }
}

export interface StreamAgentChatOptions {
  handlers: AgentStreamHandlers
  signal?: AbortSignal
  /** 为空时使用默认 Agent；非空时必须属于当前用户。 */
  agentId?: number | null
}

/**
 * 发起一次流式对话，直到 `done` / `error` 或连接结束。
 *
 * 注意：调用方应传入 `AbortSignal`，用户点「停止」或组件卸载时 abort——
 * 服务端据此取消回合（`SseEmitter` 断连 → `AgentExecutionContext.cancel`），
 * 否则模型与工具会继续在后台跑。
 */

/**
 * 打开 Agent 会话的 SSE 回合，将阶段、工具、答案和终态事件分发给页面。
 * 接收问题正文、会话 ID、可选的取消信号和进度回调。
 * 由 AgentView.vue 的 send 触发。
 * 下游调用 AgentController 的回合流入口、mock 的 mockStreamAgentChat。
 */
export async function streamAgentChat(
  question: string,
  sessionId: string | null,
  options: StreamAgentChatOptions,
): Promise<void> {
  const { handlers, signal, agentId } = options

  if (USE_MOCK) {
    return mockStreamAgentChat(question, sessionId, agentId ?? null, handlers, signal)
  }

  const response = await fetch('/api/agent/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      Authorization: `Bearer ${useAuth().token}`,
    },
    body: JSON.stringify({
      question,
      sessionId,
      ...(agentId != null ? { agentId } : {}),
    }),
    signal,
  })

  if (!response.ok) {
    throw new AgentStreamError(await readErrorMessage(response), response.status)
  }

  await readSseFrames(response, (frame) => {
    const payload = parseJsonFrame<Record<string, unknown>>(frame)
    if (payload === null) return
    switch (frame.event) {
      case 'accepted':
        handlers.onAccepted?.(payload as unknown as { maxIterations: number; maxToolCalls: number })
        break
      case 'round':
        handlers.onRound?.(payload as unknown as AgentStep)
        break
      case 'delta':
        handlers.onDelta?.(payload as unknown as { round: number; text: string; channel?: string })
        break
      case 'tool_call':
        handlers.onToolCall?.(payload as unknown as AgentToolCallEvent)
        break
      case 'tool_result':
        handlers.onToolResult?.(payload as unknown as AgentToolResultEvent)
        break
      case 'answer':
        handlers.onAnswer?.(payload as unknown as AgentAnswerEvent)
        break
      case 'done':
        handlers.onDone?.(payload as unknown as AgentDoneEvent)
        return false
      case 'error':
        handlers.onError?.(payload as unknown as AgentErrorEvent)
        return false
      default:
        break
    }
  })
}

/** 从非成功的流式响应提取安全的页面错误正文。接收HTTP 响应，返回文本。由 streamAgentChat 触发。 */
async function readErrorMessage(response: Response): Promise<string> {
  try {
    const body = await response.json() as { message?: unknown; data?: { message?: unknown } }
    const nested = body?.data?.message
    if (typeof nested === 'string' && nested.trim()) return nested
    if (typeof body?.message === 'string' && body.message.trim()) return body.message
  } catch {
    // 非 JSON 响应（代理层错误页）保持状态码兜底。
  }
  return `Agent 对话请求失败（HTTP ${response.status}）`
}
