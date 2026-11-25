/** 为本地模拟模式提供认证、知识库、Agent、Trace 和指标的内存数据与交互。 */

/**
 * 开发调试用的 mock 数据层（DEV ONLY）。
 *
 * 作用：真实后端不可用时，按与后端一致的数据结构返回同构样例数据，便于 UI 独立开发与验收。
 * 覆盖范围：登录、知识库与文档接入、知识问答、观测 Trace / 状态、Actuator 健康与指标。
 *
 * 启用方式：dev 启动时设 `VITE_USE_MOCK=true`；后端就绪后去掉该变量即全部走真实接口。
 * 本文件与正式 API 客户端（auth/observability/actuator）在 `USE_MOCK` 分支处隔离，正式数据流零侵入。
 */
import type { AgentProfileDTO, AgentProfileRequestDTO, AgentStreamHandlers, AgentToolDTO, AgentSessionDTO, AgentSessionPageDTO, AgentSessionHistoryDTO, AgentSessionTurnDTO, AgentMemoryDTO, AgentMemoryListDTO, AgentMemoryCorrectionDTO } from './agent'
import type {
  TraceDTO,
  TraceSummaryDTO,
  ObservabilityStatusDTO,
  HealthDTO,
  MetricsListDTO,
  MetricDetailDTO,
  LoginVO,
  KnowledgeAskResponseDTO,
  KnowledgeBaseDTO,
  DocumentDTO,
  DocumentPageDTO,
  DocumentChunkPageDTO,
  DocumentTaskDTO,
  DocumentUploadReceiptDTO,
  UploadReceiptLookupDTO,
  CapabilityReadinessResponseDTO,
  PipelineEngine,
} from '../types'

/** 创建指定毫秒后的模拟响应延迟。接收ms。由 mockRegister、mockLogin 触发。 */
const delay = (ms: number) => new Promise((r) => setTimeout(r, ms))

// ---------- 固定 trace 样本（模拟 RAG + Agent 各入口） ----------
type SpanSeed = [stage: string, durationMs: number, extra?: Partial<TraceDTO['spans'][number]>]

/** 将测试种子转换为固定结构的追踪阶段。接收seed，返回 TraceDTO['spans'][number]。由 buildTrace 触发。 */
function span(seed: SpanSeed): TraceDTO['spans'][number] {
  const [stage, durationMs, extra = {}] = seed
  return {
    stage,
    durationMs,
    totalTokens: null,
    costUsd: null,
    inputTokens: null,
    outputTokens: null,
    success: true,
    errorMessage: null,
    ...extra,
  }
}

const SAMPLE_QUERIES = [
  '如何配置 pgvector 向量检索？',
  '知识库上传文档后多久可以检索？',
  'Agent 调用知识库搜索的流程是怎样的？',
  'Rerank 模型在哪里配置？',
  'trace 环形缓冲的保留条数是多少？',
]

/**
 * 按样本号和时间组装模拟追踪详情。
 * 接收i、now，返回带阶段数据的追踪详情。
 * 由 ensureTraces 触发。
 * 下游调用 span。
 */
function buildTrace(i: number, now: number): TraceDTO {
  const entrypoint = ['chat', 'ask', 'agent_chat', 'search', 'knowledge_search'][i % 5]
  const rewritten = i % 3 !== 0 // 部分请求发生改写
  const generationTokens = 200 + i * 90
  const inputTokens = Math.round(generationTokens * 0.55)
  const outputTokens = generationTokens - inputTokens
  const costPer1m = 2.0 // 示意单价 $/1M tokens
  const spans: TraceDTO['spans'][number][] = []
  const rejected = i === 3

  spans.push(
    span(['QUERY_REWRITE', 180 + i * 20, {
      totalTokens: 40 + i * 5,
      costUsd: ((40 + i * 5) / 1_000_000) * costPer1m,
    }]),
  )
  spans.push(span(['VECTOR_RETRIEVAL', 120 + i * 15]))
  spans.push(span(['KEYWORD_RETRIEVAL', 90 + i * 10]))
  spans.push(span(['FUSION', 30 + i * 5]))
  // 样例中给一条"卡在 Rerank"的慢 trace（i===1），便于验收瀑布图
  spans.push(span(['RERANK', i === 1 ? 5300 : 200 + i * 40]))
  if (rejected) {
    spans.push(span(['EVIDENCE_GATE', 8, { success: false, errorMessage: 'threshold_not_configured' }]))
  } else {
    spans.push(span(['LLM_GENERATION', 900 + i * 300, {
      totalTokens: generationTokens,
      costUsd: (generationTokens / 1_000_000) * costPer1m,
      inputTokens,
      outputTokens,
    }]))
  }

  if (i === 4) {
    spans.push(span(['AI_RESPONSE_CONTRACT', 1, { success: false, errorMessage: 'ai_contract_mismatch' }]))
  }

  return {
    traceId: `mock-${(1000 + i).toString(16)}${(Date.now() % 1000).toString(16)}`,
    entrypoint,
    originalQuery: SAMPLE_QUERIES[i % SAMPLE_QUERIES.length],
    rewrittenQuery: rewritten
      ? `[改写] ${SAMPLE_QUERIES[i % SAMPLE_QUERIES.length]}`
      : SAMPLE_QUERIES[i % SAMPLE_QUERIES.length],
    startTime: now - (i + 1) * 30_000,
    totalMs: spans.reduce((s, sp) => s + sp.durationMs, 0),
    spans,
    // Agent and one older RAG sample omit metadata to exercise the historical-record UI.
    ...(entrypoint === 'agent_chat' || i === 6 ? {} : {
      pipelineEngine: i % 2 === 0 ? 'fixed' : 'langchain',
      requestedPipelineEngine: i === 4 ? 'langchain' : (i % 2 === 0 ? 'fixed' : 'langchain'),
      requestedRetrievalVersion: 'hybrid-rerank-rewrite',
      retrievalVersion: 'hybrid-rerank-rewrite',
      providerUsed: rejected ? 'none' : 'mock',
      evidenceScorer: 'mock',
      outcome: rejected || i === 4 ? 'dependency_unavailable' : 'success',
      reasonCode: i === 4 ? 'ai_contract_mismatch' : (rejected ? 'threshold_not_configured' : 'none'),
    }),
  }
}

/** 从模拟追踪详情提取摘要并推断是否降级。接收t，返回 TraceSummaryDTO。 */
function toSummary(t: TraceDTO): TraceSummaryDTO {
  return {
    traceId: t.traceId,
    entrypoint: t.entrypoint,
    originalQuery: t.originalQuery,
    totalMs: t.totalMs,
    spanCount: t.spans.length,
    success: t.spans.every((s) => s.success),
    startTime: t.startTime,
    requestedPipelineEngine: t.requestedPipelineEngine,
    requestedRetrievalVersion: t.requestedRetrievalVersion,
    pipelineEngine: t.pipelineEngine,
    retrievalVersion: t.retrievalVersion,
    providerUsed: t.providerUsed,
    evidenceScorer: t.evidenceScorer,
    outcome: t.outcome,
    reasonCode: t.reasonCode,
  }
}

let traces: TraceDTO[] = []

/**
 * 首次使用时生成追踪样本并在后续请求复用。
 * 返回 TraceDTO[]。
 * 由 mockListTraces、mockGetTrace 触发。
 * 下游调用 buildTrace。
 */
function ensureTraces(): TraceDTO[] {
  if (traces.length === 0) {
    const now = Date.now()
    traces = Array.from({ length: 8 }, (_, i) => buildTrace(i, now))
  }
  return traces
}

// ---------- 登录与注册 ----------
/**
 * 模拟注册延迟并回传成功文本。
 * 返回文本。
 * 由 auth.ts 的 register 触发。
 * 下游调用 delay。
 */
export function mockRegister(): Promise<string> {
  return delay(300).then(() => '注册成功')
}

// mock 模式没有 /user/refresh 端点：15 分钟后 http.ts 的 ensureFresh 必然失败一次。
// 按「旧 access 未过期则照常放行」的规则，此时界面不受影响，直到真的过期才走 401 → 登出。
// 这是预期行为，不是 bug。
/**
 * 返回带模拟令牌和过期时间的登录结果。
 * 接收_username、_password。
 * 由 auth.ts 的 login 触发。
 * 下游调用 delay。
 */
export function mockLogin(_username: string, _password: string): Promise<LoginVO> {
  return delay(300).then(() => ({
    token: `mock.jwt.${Math.random().toString(36).slice(2)}`,
    refreshToken: `mock.refresh.${Math.random().toString(36).slice(2)}`,
    expiresIn: 900,
  }))
}

// ---------- 知识问答 ----------
/**
 * 以问题和编排引擎为输入生成本地问答样本。
 * 接收问题正文、编排引擎，返回带业务终态的问答结果。
 * 由 knowledge.ts 的 askKnowledge 触发。
 * 下游调用 delay。
 */
export function mockKnowledgeAsk(question: string, pipelineEngine: PipelineEngine = 'fixed'): Promise<KnowledgeAskResponseDTO> {
  return delay(550).then(() => ({
    answer: `这是开发模拟回答。页面收到的问题是“${question}”。切换到真实 API 后，答案将由知识库检索证据生成。`,
    outcome: 'success',
    reasonCode: 'none',
    pipelineEngine,
    retrievalVersion: 'hybrid-rerank-rewrite',
    sources: [
      {
        documentId: 1,
        chunkId: 101,
        knowledgeBaseId: 1,
        chunkIndex: 0,
        content: '这是一条仅用于界面演示的模拟证据，不代表项目中的真实文档内容。',
        distance: null,
        headingPath: '开发演示数据',
        pageNo: null,
        sourceType: 'mock',
      },
    ],
  }))
}

// ---------- Agent 配置 ----------
// 仅用于 VITE_USE_MOCK=true 的界面演示；真实归属与白名单校验在 Java 服务端。
const mockAgentTools: AgentToolDTO[] = [
  { name: 'knowledge_ask', description: '基于获准知识库证据回答问题。', requiresApproval: false },
  { name: 'knowledge_search', description: '在当前知识库范围内检索获准片段。', requiresApproval: false },
  { name: 'list_documents', description: '分页列出当前知识范围内已完成且可阅读的文档元数据。', requiresApproval: false },
  { name: 'memory_save', description: '保存当前用户明确要求记住的事实或偏好。', requiresApproval: true },
  { name: 'memory_search', description: '检索当前用户自己的长期记忆。', requiresApproval: false },
  { name: 'read_document', description: '检索指定文档的获准片段，或按稳定块序和块内游标连续阅读获准窗口。', requiresApproval: false },
]

/** 返回 Agent 工具目录的可独立编辑副本。由 agent.ts 的 listAgentTools 触发。下游调用 delay。 */
export function mockListAgentTools(): Promise<AgentToolDTO[]> {
  return delay(120).then(() => mockAgentTools.map((tool) => ({ ...tool })))
}

/**
 * 去空白并去重，拒绝模拟工具目录中不存在的工具名。
 * 接收names，返回 string[]。
 * 由 mockCreateAgentProfile、mockUpdateAgentProfile 触发。
 */
function normalizeMockToolNames(names: string[]): string[] {
  const normalized = [...new Set(names.map((name) => name.trim()))]

  for (const name of normalized) {
    if (!mockAgentTools.some((tool) => tool.name === name)) throw new Error(`未知工具: ${name}`)
  }
  return normalized
}

const mockAgentCreatedAt = new Date().toISOString()
let mockAgentProfiles: AgentProfileDTO[] = [
  {
    id: 1,
    name: '知识库助手（示例）',
    chatModelId: null,
    systemPrompt: '优先引用知识库证据，证据不足时明确说明。',
    answerPolicy: 'grounded',
    memoryReadEnabled: true,
    memoryWriteEnabled: true,
    kbSelectionMode: 'all',
    knowledgeBaseIds: [],
    allowedTools: ['knowledge_search', 'knowledge_ask'],
    createTime: mockAgentCreatedAt,
    updateTime: mockAgentCreatedAt,
  },
]
let nextMockAgentProfileId = 2

/** 复制配置及其数组字段以隔离调用方修改。接收profile，返回Agent 配置记录。由 mockCreateAgentProfile、mockUpdateAgentProfile 触发。 */
function cloneAgentProfile(profile: AgentProfileDTO): AgentProfileDTO {
  return {
    ...profile,
    knowledgeBaseIds: [...profile.knowledgeBaseIds],
    allowedTools: [...profile.allowedTools],
  }
}

/** 返回模拟账号下可编辑的 Agent 配置副本。由 agent.ts 的 listAgentProfiles 触发。下游调用 delay。 */
export function mockListAgentProfiles(): Promise<AgentProfileDTO[]> {
  return delay(180).then(() => mockAgentProfiles.map(cloneAgentProfile))
}

/**
 * 从请求创建新的模拟 Agent 配置并写入列表。
 * 接收request，返回Agent 配置记录。
 * 由 agent.ts 的 createAgentProfile 触发。
 * 下游调用 delay、normalizeMockToolNames。
 */
export function mockCreateAgentProfile(request: AgentProfileRequestDTO): Promise<AgentProfileDTO> {
  return delay(260).then(() => {
    const now = new Date().toISOString()
    const profile: AgentProfileDTO = {
      id: nextMockAgentProfileId++,
      ...request,
      memoryReadEnabled: request.memoryReadEnabled ?? true,
      memoryWriteEnabled: request.memoryWriteEnabled ?? true,
      knowledgeBaseIds: [...request.knowledgeBaseIds],
      allowedTools: normalizeMockToolNames(request.allowedTools),
      createTime: now,
      updateTime: now,
    }
    mockAgentProfiles = [profile, ...mockAgentProfiles]
    return cloneAgentProfile(profile)
  })
}

/**
 * 按 ID 更新已有模拟 Agent 配置并返回独立副本。
 * 接收Agent 配置 ID、request。
 * 由 agent.ts 的 updateAgentProfile 触发。
 * 下游调用 delay、normalizeMockToolNames。
 */
export function mockUpdateAgentProfile(id: number, request: AgentProfileRequestDTO): Promise<AgentProfileDTO> {
  return delay(240).then(() => {
    const index = mockAgentProfiles.findIndex((item) => item.id === id)
    if (index < 0) throw new Error('Agent 配置不存在或无权访问')
    const updated: AgentProfileDTO = {
      ...mockAgentProfiles[index],
      ...request,
      memoryReadEnabled: request.memoryReadEnabled ?? true,
      memoryWriteEnabled: request.memoryWriteEnabled ?? true,
      knowledgeBaseIds: [...request.knowledgeBaseIds],
      allowedTools: normalizeMockToolNames(request.allowedTools),
      updateTime: new Date().toISOString(),
    }
    mockAgentProfiles = mockAgentProfiles.map((item) => (item.id === id ? updated : item))
    return cloneAgentProfile(updated)
  })
}

/**
 * 从模拟配置列表移除指定 Agent。
 * 接收Agent 配置 ID，返回文本。
 * 由 agent.ts 的 deleteAgentProfile 触发。
 * 下游调用 delay。
 */
export function mockDeleteAgentProfile(id: number): Promise<string> {
  return delay(200).then(() => {
    mockAgentProfiles = mockAgentProfiles.filter((item) => item.id !== id)
    return 'Agent 配置已删除'
  })
}

// 开发模拟遵守与服务端相同的停用/清理边界；不表示真实存储状态。
const mockLongTermMemoryEnabled = true

/**
 * 从模拟账号的会话键派生记忆存储键。
 * 返回文本。
 * 由 loadMockMemories、mockCorrectAgentMemory 触发。
 * 下游调用 mockSessionKey。
 */
function mockMemoryKey(): string { return `${mockSessionKey()}:long-term` }

/**
 * 读取当前账号在标签页存储中的模拟记忆。
 * 返回 AgentMemoryDTO[]。
 * 由 mockListAgentMemories、mockCorrectAgentMemory 触发。
 * 下游调用 mockMemoryKey。
 */
function loadMockMemories(): AgentMemoryDTO[] {
  return JSON.parse(sessionStorage.getItem(mockMemoryKey()) ?? '[]') as AgentMemoryDTO[]
}

/**
 * 把本地模拟记忆包装为记忆列表响应。
 * 返回 AgentMemoryListDTO。
 * 由 agent.ts 的 listAgentMemories 触发。
 * 下游调用 loadMockMemories。
 */
export async function mockListAgentMemories(): Promise<AgentMemoryListDTO> {
  return { enabled: mockLongTermMemoryEnabled, maxItems: 50, maxContentChars: 500, items: loadMockMemories() }
}

/**
 * 修订指定模拟记忆并保存到标签页存储。
 * 接收记忆 ID、request，返回 AgentMemoryDTO。
 * 由 agent.ts 的 correctAgentMemory 触发。
 * 下游调用 loadMockMemories、mockMemoryKey。
 */
export async function mockCorrectAgentMemory(id: string, request: AgentMemoryCorrectionDTO): Promise<AgentMemoryDTO> {
  if (!mockLongTermMemoryEnabled) throw new Error('长期记忆已全局停用，仍可查看和删除已有条目')

  const items = loadMockMemories()
  const existing = items.find((item) => item.id === id)
  if (!existing) throw new Error('记忆不存在或无权访问')

  const content = request.content.trim()
  if (!content || content.length > 500) throw new Error('记忆内容须为 1 到 500 个字符')

  const corrected: AgentMemoryDTO = { ...existing, content, kind: request.kind, updatedAt: new Date().toISOString() }
  sessionStorage.setItem(mockMemoryKey(), JSON.stringify(items.map((item) => item.id === id ? corrected : item)))
  return corrected
}

/**
 * 按 ID 删除账号下的模拟记忆并写回列表。
 * 接收记忆 ID，返回文本。
 * 由 agent.ts 的 deleteAgentMemory 触发。
 * 下游调用 loadMockMemories、mockMemoryKey。
 */
export async function mockDeleteAgentMemory(id: string): Promise<string> {
  const items = loadMockMemories()
  if (!items.some((item) => item.id === id)) throw new Error('记忆不存在或无权访问')
  sessionStorage.setItem(mockMemoryKey(), JSON.stringify(items.filter((item) => item.id !== id)))
  return '记忆已删除'
}

// 会话 mock 使用 sessionStorage，以便同一开发标签页刷新后恢复；真实模式只读服务端。
interface MockSessionState { session: AgentSessionDTO; policy: string; turns: AgentSessionTurnDTO[] }

/** 从本地令牌派生标签页中的账号会话键。返回文本。由 mockMemoryKey、loadMockSessions 触发。 */
function mockSessionKey(): string {
  const token = localStorage.getItem('token') ?? 'anonymous'
  let hash = 0

  for (const char of token) hash = ((hash << 5) - hash + char.charCodeAt(0)) | 0
  return `ekip:mock-agent-sessions:${hash}`
}

/**
 * 读取当前账号的模拟会话并过滤已过期记录。
 * 返回 MockSessionState[]。
 * 由 ownedMockSession、mockCreateAgentSession 触发。
 * 下游调用 mockSessionKey。
 */
function loadMockSessions(): MockSessionState[] {
  const states = JSON.parse(sessionStorage.getItem(mockSessionKey()) ?? '[]') as MockSessionState[]
  return states.filter((state) => state.session.expiresAt > Date.now())
}

/**
 * 将模拟会话状态写入当前账号的标签页存储。
 * 接收states。
 * 由 mockCreateAgentSession、mockDeleteAgentSession 触发。
 * 下游调用 mockSessionKey。
 */
function saveMockSessions(states: MockSessionState[]) {
  sessionStorage.setItem(mockSessionKey(), JSON.stringify(states))
}

/**
 * 用 Agent 关联的配置和工具选择生成用于会话比对的策略值。
 * 接收Agent 配置 ID，返回文本。
 * 由 ownedMockSession、mockCreateAgentSession 触发。
 */
function mockSessionPolicy(agentId: number | null): string {
  const profile = agentId == null ? null : mockAgentProfiles.find((entry) => entry.id === agentId)
  if (agentId != null && !profile) throw new Error('Agent 配置不存在或无权访问')
  return JSON.stringify({ longTermMemoryEnabled: mockLongTermMemoryEnabled,
    memoryReadEnabled: profile?.memoryReadEnabled ?? true, memoryWriteEnabled: profile?.memoryWriteEnabled ?? true,
    profile: profile == null ? 'default' : {
    systemPrompt: profile.systemPrompt, chatModelId: profile.chatModelId, answerPolicy: profile.answerPolicy, kbSelectionMode: profile.kbSelectionMode,
    knowledgeBaseIds: [...profile.knowledgeBaseIds].sort((a, b) => a - b), allowedTools: [...profile.allowedTools].sort(),
  } })
}

/**
 * 读取当前账号的指定模拟会话并核对当前策略。
 * 接收会话 ID，返回 MockSessionState。
 * 由 mockAgentSessionHistory、completeMockSession 触发。
 * 下游调用 loadMockSessions、mockSessionPolicy。
 */
function ownedMockSession(id: string): MockSessionState {
  const state = loadMockSessions().find((entry) => entry.session.id === id)
  if (!state) throw new Error('会话不存在或无权访问')
  if (state.policy !== mockSessionPolicy(state.session.agentId)) throw new Error('Agent 配置已变化，请创建新会话')
  return state
}

/**
 * 生成具有独立 ID、期限和策略的本地会话。
 * 接收Agent 配置 ID，返回已创建的会话记录。
 * 由 agent.ts 的 createAgentSession 触发。
 * 下游调用 mockSessionPolicy、saveMockSessions。
 */
export async function mockCreateAgentSession(agentId: number | null): Promise<AgentSessionDTO> {
  const policy = mockSessionPolicy(agentId)
  const now = Date.now()
  const session: AgentSessionDTO = { id: `as_${crypto.randomUUID()}`, agentId, title: '新会话',
    answerPolicy: agentId == null ? 'general' : mockAgentProfiles.find((entry) => entry.id === agentId)!.answerPolicy,
    createdAt: now, updatedAt: now, expiresAt: now + 30 * 86400000 }
  saveMockSessions([{ session, policy, turns: [] }, ...loadMockSessions()])
  return session
}

/**
 * 按更新时间和游标返回仍符合策略的本地会话。
 * 接收查询偏移量。
 * 由 agent.ts 的 listAgentSessions 触发。
 * 下游调用 loadMockSessions、mockSessionPolicy。
 */
export async function mockListAgentSessions(offset = 0): Promise<AgentSessionPageDTO> {
  const candidates = loadMockSessions().sort((a, b) => b.session.updatedAt - a.session.updatedAt).slice(offset, offset + 30)
  const items = candidates.filter((entry) => {
    try { return entry.policy === mockSessionPolicy(entry.session.agentId) } catch { return false }
  }).map((entry) => entry.session)
  return { items, nextOffset: offset + candidates.length, hasMore: candidates.length === 30 }
}

/**
 * 按偏移量读取指定模拟会话的历史回合。
 * 接收会话 ID、查询偏移量，返回 AgentSessionHistoryDTO。
 * 由 agent.ts 的 getAgentSessionHistory 触发。
 * 下游调用 ownedMockSession。
 */
export async function mockAgentSessionHistory(id: string, offset = 0): Promise<AgentSessionHistoryDTO> {
  const state = ownedMockSession(id)
  const end = Math.max(0, state.turns.length - offset)
  const start = Math.max(0, end - 20)
  const items = state.turns.slice(start, end)
  return { session: state.session, items, nextOffset: offset + items.length, hasMore: start > 0 }
}
const mockActiveSessions = new Set<string>()

/**
 * 删除当前未运行的模拟会话。
 * 接收会话 ID，返回文本。
 * 由 agent.ts 的 deleteAgentSession 触发。
 * 下游调用 saveMockSessions、loadMockSessions。
 */
export async function mockDeleteAgentSession(id: string): Promise<string> {
  if (mockActiveSessions.has(id)) throw new Error('会话仍有执行中的回合，请停止并等待结束后删除')
  saveMockSessions(loadMockSessions().filter((entry) => entry.session.id !== id))
  return '会话已删除'
}

/**
 * 将本轮结果加入模拟会话历史并更新会话时间。
 * 接收会话 ID、问题正文、turn。
 * 由 mockStreamAgentChat 触发。
 * 下游调用 ownedMockSession、saveMockSessions。
 */
function completeMockSession(id: string | null, question: string, turn: Omit<AgentSessionTurnDTO, 'id' | 'question' | 'completedAt'>) {
  if (!id?.startsWith('as_')) return

  const state = ownedMockSession(id)
  const now = Date.now()
  state.turns.push({ ...turn, question, id: crypto.randomUUID(), completedAt: now })
  state.session.updatedAt = now
  state.session.expiresAt = now + 30 * 86400000
  if (state.session.title === '新会话') state.session.title = question.slice(0, 40)
  saveMockSessions(loadMockSessions().map((entry) => entry.session.id === id ? state : entry))
}

/**
 * 模拟一次 Agent SSE 回合：按 accepted → round/delta → tool_call/tool_result → answer/done
 * 的顺序回调，用于界面联调。它不证明真实模型、Redis 或工具链路可用。
 */

/**
 * 发出模拟回合、工具结果和正文事件，并响应取消信号。
 * 接收问题正文、会话 ID、Agent 配置 ID、事件处理回调、取消信号。
 * 由 agent.ts 的 streamAgentChat 触发。
 * 下游调用 delay、abortIfNeeded。
 */
export async function mockStreamAgentChat(
  question: string,
  sessionId: string | null,
  agentId: number | null,
  handlers: AgentStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  /** 在回合取消时抛出中止错误。由 mockStreamAgentChat 触发。 */
  const abortIfNeeded = () => {
    if (signal?.aborted) throw new DOMException('Aborted', 'AbortError')
  }

  /**
   * 等待模拟步进间隔并在恢复时检查取消。
   * 接收ms。
   * 由 mockStreamAgentChat 触发。
   * 下游调用 delay、abortIfNeeded。
   */
  const wait = async (ms: number) => {
    await delay(ms)
    abortIfNeeded()
  }

  /**
   * 分段发出模拟正文，并在发送前检查取消。
   * 接收文本、onDelta。
   * 由 mockStreamAgentChat 触发。
   * 下游调用 abortIfNeeded、delay。
   */
  const streamText = async (text: string, onDelta: (chunk: string) => void) => {
    for (let cursor = 0; cursor < text.length; cursor += 6) {
      abortIfNeeded()
      onDelta(text.slice(cursor, cursor + 6))
      await delay(24)
    }
  }

  const session = sessionId?.startsWith('as_') ? ownedMockSession(sessionId) : null

  if (session && sessionId) {
    if (session.session.agentId !== agentId) throw new Error('会话 Agent 不匹配')
    if (mockActiveSessions.has(sessionId)) throw new Error('会话已有执行中的回合')
    mockActiveSessions.add(sessionId)
  }

  try {
    const profile = agentId == null ? null : mockAgentProfiles.find((item) => item.id === agentId)
    if (agentId != null && !profile) throw new Error('Agent 配置不存在或无权访问')
    const grounded = (profile?.answerPolicy ?? session?.session.answerPolicy ?? 'general') === 'grounded'
    const canSearch = !profile || (profile.allowedTools.includes('knowledge_search') && profile.kbSelectionMode !== 'none')
    if (!canSearch || (!profile && !grounded)) {
      handlers.onAccepted?.({ maxIterations: 8, maxToolCalls: 12 })
      await wait(120)
      const text = grounded
        ? '本轮没有可用的知识库检索工具或知识范围，未获得可用于回答的证据（开发模拟）。'
        : '这是允许通用回答的开发模拟结果，未检索知识库。'
      const outcome = grounded ? 'no_evidence' : undefined
      abortIfNeeded()
      completeMockSession(sessionId, question, { answer: text, steps: [], sources: [], outcome,
        stopReason: 'FINAL_ANSWER', iterations: 1, toolCalls: 0 })
      handlers.onAnswer?.({ text, sources: [], outcome })
      handlers.onDone?.({ stopReason: 'FINAL_ANSWER', iterations: 1, toolCalls: 0, sources: [], outcome })
      return
    }
    const sources = [
      {
        citationId: 'S1_101',
        documentId: 1,
        chunkId: 101,
        knowledgeBaseId: 1,
        chunkIndex: 0,
        content: '这是一条仅用于界面演示的模拟获准证据，不代表项目中的真实文档内容。',
        distance: null,
        headingPath: '开发演示数据',
        pageNo: null,
        sourceType: 'mock',
      },
    ]
    const answer = `这是开发模拟的 Agent 回答${profile ? `（配置：${profile.name}）` : ''}。页面收到的问题是“${question}”。`
      + '这是示例来源的引用 [[cite:S1_101]]。切换到真实 API 后，回答将由服务端 Agent 在授权知识库内检索并生成。'

    handlers.onAccepted?.({ maxIterations: 8, maxToolCalls: 12 })
    await wait(120)
    handlers.onRound?.({ round: 1, phase: 'think' })
    await streamText('我先检索一下知识库。', (text) => {
      handlers.onDelta?.({ round: 1, text, channel: 'answer' })
    })
    handlers.onToolCall?.({ round: 1, tool: 'knowledge_search', args: JSON.stringify({ query: question }) })
    await wait(360)
    handlers.onToolResult?.({ round: 1, tool: 'knowledge_search', ok: true, summary: `命中 ${sources.length} 个获准片段` })
    handlers.onRound?.({ round: 1, phase: 'observe' })
    handlers.onRound?.({ round: 2, phase: 'think' })
    await wait(160)
    await streamText(answer, (text) => {
      handlers.onDelta?.({ round: 2, text, channel: 'answer' })
    })
    abortIfNeeded()
    completeMockSession(sessionId, question, { answer, sources, outcome: 'success', stopReason: 'FINAL_ANSWER',
      iterations: 2, toolCalls: 1, promptTokens: 420, completionTokens: 160,
      steps: [{ tool: 'knowledge_search', ok: true, summary: `命中 ${sources.length} 个获准片段` }] })
    handlers.onAnswer?.({ text: answer, synthesized: false, sources, outcome: 'success' })
    handlers.onDone?.({
      stopReason: 'FINAL_ANSWER',
      iterations: 2,
      toolCalls: 1,
      promptTokens: 420,
      completionTokens: 160,
      synthesized: false,
      sources,
      outcome: 'success',
    })
  } finally { if (sessionId) mockActiveSessions.delete(sessionId) }
}

// ---------- 知识库与文档接入 ----------
const mockCreatedAt = new Date().toISOString()
let mockKnowledgeBases: KnowledgeBaseDTO[] = [
  {
    id: 1,
    name: '员工手册示例库',
    description: '用于演示上传、解析状态和问答入口。',
    creatorId: 1,
    createTime: mockCreatedAt,
    updateTime: mockCreatedAt,
  },
]
let mockDocuments: DocumentDTO[] = [
  {
    id: 1,
    knowledgeBaseId: 1,
    fileName: '员工手册.pdf',
    fileType: 'application/pdf',
    fileSize: 72_000,
    status: 2,
    createTime: mockCreatedAt,
  },
]
const mockDocumentTasks = new Map<number, DocumentTaskDTO>([
  [1, { documentId: 1, status: 'SUCCESS', attempt: 1, errorMessage: null, startTime: mockCreatedAt, finishTime: mockCreatedAt }],
])
let nextMockKnowledgeBaseId = 2
let nextMockDocumentId = 2
const mockUploadReceipts = new Map<string, DocumentUploadReceiptDTO>()

/** 返回模拟知识库列表副本。由 library.ts 的 listKnowledgeBases 触发。下游调用 delay。 */
export function mockListKnowledgeBases(): Promise<KnowledgeBaseDTO[]> {
  return delay(220).then(() => mockKnowledgeBases.map((item) => ({ ...item })))
}

/**
 * 在模拟列表中创建知识库记录。
 * 接收名称、说明，返回文本。
 * 由 library.ts 的 createKnowledgeBase 触发。
 * 下游调用 delay。
 */
export function mockCreateKnowledgeBase(name: string, description: string): Promise<string> {
  return delay(350).then(() => {
    const now = new Date().toISOString()
    mockKnowledgeBases = [
      {
        id: nextMockKnowledgeBaseId++,
        name,
        description: description || null,
        creatorId: 1,
        createTime: now,
        updateTime: now,
      },
      ...mockKnowledgeBases,
    ]
    return '知识库创建成功'
  })
}

/**
 * 从文档样本中构造有来源元数据的分页切片。
 * 接收文档 ID、页码、页大小，返回含正文和元数据的切片分页。
 * 由 library.ts 的 listDocumentChunks 触发。
 * 下游调用 delay。
 */
export function mockListDocumentChunks(documentId: number, page: number, size: number): Promise<DocumentChunkPageDTO> {
  return delay(150).then(() => {
    const document = mockDocuments.find(item => item.id === documentId)
    if (!document) throw new Error('文档不存在')
    const content = [
      '本手册适用于公司全体正式员工及实习人员。',
      '标准工作时间为每周一至周五 9:00 至 18:00。',
      '请假需经直属上级审批并在考勤系统留痕。',
    ]
    const records = content.map((text, index) => ({
      id: documentId * 1000 + index, documentId, knowledgeBaseId: document.knowledgeBaseId,
      chunkIndex: index, content: text, charCount: text.length, sourceType: 'text',
      pageNo: null, headingPath: null, tableId: null, rowIndex: null, parentChunkId: null,
    }))
    return { records: records.slice((page - 1) * size, page * size), current: page, size,
      total: records.length, pages: Math.ceil(records.length / size) }
  })
}

/**
 * 按知识库过滤模拟文档并返回分页契约。
 * 接收知识库 ID。
 * 由 library.ts 的 listDocuments 触发。
 * 下游调用 delay。
 */
export function mockListDocuments(knowledgeBaseId: number): Promise<DocumentPageDTO> {
  return delay(250).then(() => {
    const records = mockDocuments.filter((item) => item.knowledgeBaseId === knowledgeBaseId)
    return { records: records.map((item) => ({ ...item })), current: 1, size: 100, total: records.length, pages: records.length ? 1 : 0 }
  })
}

/**
 * 移除模拟文档及对应任务与上传回执。
 * 接收文档 ID，返回文本。
 * 由 library.ts 的 deleteDocument 触发。
 * 下游调用 delay。
 */
export function mockDeleteDocument(documentId: number): Promise<string> {
  return delay(180).then(() => {
    const document = mockDocuments.find((item) => item.id === documentId)
    if (!document) throw new Error('文档不存在或已删除，请刷新列表。')
    mockDocuments = mockDocuments.filter((item) => item.id !== documentId)
    mockDocumentTasks.delete(documentId)
    for (const [requestKey, receipt] of mockUploadReceipts) {
      if (receipt.documentId === documentId) mockUploadReceipts.delete(requestKey)
    }
    return '文档删除成功'
  })
}

/**
 * 按请求键复用或创建回执，并安排模拟的任务结果。
 * 接收知识库 ID、浏览器文件、上传请求键，返回文档与任务的受理回执。
 * 由 library.ts 的 uploadDocument 触发。
 * 下游调用 delay。
 */
export function mockUploadDocument(knowledgeBaseId: number, file: File, requestKey: string): Promise<DocumentUploadReceiptDTO> {
  return delay(400).then(() => {
    const existing = mockUploadReceipts.get(requestKey)
    if (existing) return { ...existing, reused: true }

    const now = new Date().toISOString()
    const documentId = nextMockDocumentId++
    const document: DocumentDTO = {
      id: documentId,
      knowledgeBaseId,
      fileName: file.name,
      fileType: file.type || null,
      fileSize: file.size,
      status: 1,
      createTime: now,
    }
    mockDocuments = [document, ...mockDocuments]
    mockDocumentTasks.set(documentId, {
      documentId,
      status: 'WAITING',
      attempt: 1,
      errorMessage: null,
      startTime: now,
      finishTime: null,
    })

    const receipt = {
      requestKey,
      documentId,
      taskId: documentId,
      documentStatus: 'UPLOADED' as const,
      taskStatus: 'WAITING' as const,
      reused: false,
    }
    mockUploadReceipts.set(requestKey, receipt)

    setTimeout(() => {
      const task = mockDocumentTasks.get(documentId)
      if (task) mockDocumentTasks.set(documentId, { ...task, status: 'PROCESSING', startTime: new Date().toISOString() })
    }, 500)

    setTimeout(() => {
      const task = mockDocumentTasks.get(documentId)
      if (task) mockDocumentTasks.set(documentId, { ...task, status: 'SUCCESS', finishTime: new Date().toISOString() })
      mockDocuments = mockDocuments.map((item) => item.id === documentId ? { ...item, status: 2 } : item)
    }, 2200)

    return { ...receipt }
  })
}

/**
 * 按请求键返回模拟回执及对应文档和任务的当前状态。
 * 接收_knowledgeBaseId、上传请求键。
 * 由 library.ts 的 lookupUploadReceipt 触发。
 * 下游调用 delay。
 */
export function mockLookupUploadReceipt(_knowledgeBaseId: number, requestKey: string): Promise<UploadReceiptLookupDTO> {
  return delay(120).then(() => {
    const receipt = mockUploadReceipts.get(requestKey)
    if (!receipt) return { found: false }
    const task = mockDocumentTasks.get(receipt.documentId)
    const document = mockDocuments.find((item) => item.id === receipt.documentId)
    return {
      found: true,
      receipt: {
        ...receipt,
        documentStatus: document?.status === 2 ? 'COMPLETED' as const : document?.status === 1 ? 'PROCESSING' as const : 'UPLOADED' as const,
        taskStatus: (task?.status as DocumentUploadReceiptDTO['taskStatus'] | undefined) ?? receipt.taskStatus,
        reused: true,
      },
    }
  })
}

/**
 * 按文档 ID 读取模拟任务状态。
 * 接收文档 ID，返回任务状态快照。
 * 由 library.ts 的 getDocumentTask、library.ts 的 retryDocumentTask 触发。
 * 下游调用 delay。
 */
export function mockGetDocumentTask(documentId: number): Promise<DocumentTaskDTO> {
  return delay(160).then(() => {
    const task = mockDocumentTasks.get(documentId)
    if (!task) throw new Error('找不到该文档的处理任务')
    return { ...task }
  })
}

// ---------- 观测 trace ----------
/**
 * 按入口过滤并限制追踪摘要数量。
 * 接收limit、entrypoint，返回 TraceSummaryDTO[]。
 * 由 observability.ts 的 listTraces 触发。
 * 下游调用 delay、ensureTraces。
 */
export function mockListTraces(limit = 50, entrypoint?: string): Promise<TraceSummaryDTO[]> {
  return delay(400).then(() => {
    const all = ensureTraces()
    const filtered = entrypoint ? all.filter((t) => t.entrypoint === entrypoint) : all
    return filtered.slice(0, limit).map(toSummary)
  })
}

/**
 * 按 ID 返回完整模拟追踪详情。
 * 接收追踪 ID。
 * 由 observability.ts 的 getTrace 触发。
 * 下游调用 delay、ensureTraces。
 */
export function mockGetTrace(id: string): Promise<TraceDTO> {
  return delay(300).then(() => {
    const found = ensureTraces().find((t) => t.traceId === id)
    if (!found) throw new Error(`trace 不存在: ${id}`)
    return found
  })
}

// ---------- 代码状态 ----------
/** 返回模拟的服务依赖状态。由 observability.ts 的 getStatus 触发。下游调用 delay。 */
export function mockGetStatus(): Promise<ObservabilityStatusDTO> {
  return delay(200).then(() => ({
    appVersion: '0.1.0',
    branch: 'feat/observability',
    commitSha: '9757b66',
    retrievalVersion: 'hybrid-rerank-rewrite',
    compressionMode: 'none',
    vectorStore: 'pgvector',
    traceRetention: 1000,
  }))
}

/** 返回带采样时间的模拟能力就绪结果。由 observability.ts 的 getCapabilities 触发。下游调用 delay。 */
export function mockGetCapabilities(): Promise<CapabilityReadinessResponseDTO> {
  return delay(180).then(() => ({
    status: 'degraded',
    checkedAt: new Date().toISOString(),
    capabilities: {
      backend: { status: 'ready', reasonCode: 'none' },
      database: { status: 'ready', reasonCode: 'none' },
      redis: { status: 'ready', reasonCode: 'none' },
      minio: { status: 'ready', reasonCode: 'none' },
      ingest: { status: 'ready', reasonCode: 'none' },
      search: { status: 'ready', reasonCode: 'none' },
      answer: { status: 'ready', reasonCode: 'none' },
      chat: { status: 'ready', reasonCode: 'none' },
      evidenceGate: { status: 'ready', reasonCode: 'none', policyVersion: 'mock', scorer: 'mock' },
      rerank: { status: 'degraded', reasonCode: 'rerank_fallback' },
    },
  }))
}

// ---------- 健康 ----------
/** 返回用于本地页面的模拟健康状态。由 actuator.ts 的 getHealth、actuator.ts 的 getAiHealth 触发。下游调用 delay。 */
export function mockGetHealth(): Promise<HealthDTO> {
  return delay(150).then(() => ({ status: 'UP' }))
}

// ---------- 指标 ----------
let metricTick = 1
const METRIC_NAMES = [
  'rag.requests.total',
  'rag.requests.active',
  'rag.rewrite.latency',
  'rag.rerank.latency',
  'rag.compression.latency',
  'rag.generation.latency',
  'rag.llm.tokens.consumed',
  'rag.cost.usd',
  'agent.requests.total',
  'agent.chat.latency',
  'system.cpu.usage',
  'jvm.memory.used',
  'http.server.requests',
]

// Micrometer Timer：TOTAL_TIME/MAX 单位为秒（与真实 /actuator/metrics 契约一致）
/** 用给定平均耗时构造模拟计时器样本。接收avgMs。由 metricDetail 触发。 */
const LATENCY_STATS = (avgMs: number) => [
  { statistic: 'COUNT', value: metricTick * 3 },
  { statistic: 'TOTAL_TIME', value: ((avgMs * metricTick * 3) / 1000) },
  { statistic: 'MAX', value: (avgMs * 1.7) / 1000 },
]

/**
 * 根据指标类型构造统计量和采样值。
 * 接收名称，返回 MetricDetailDTO。
 * 由 mockGetMetric 触发。
 * 下游调用 LATENCY_STATS。
 */
function metricDetail(name: string): MetricDetailDTO {
  const tick = metricTick
  switch (name) {
    case 'rag.requests.total':
      return { name, measurements: [{ statistic: 'COUNT', value: tick * 5 }], availableTags: [{ tag: 'entrypoint', values: ['chat', 'ask', 'search'] }] }
    case 'rag.requests.active':
      return { name, measurements: [{ statistic: 'VALUE', value: (tick % 4) }] }
    case 'rag.rewrite.latency':
      return { name, measurements: LATENCY_STATS(220) }
    case 'rag.rerank.latency':
      return { name, measurements: LATENCY_STATS(340) }
    case 'rag.compression.latency':
      return { name, measurements: LATENCY_STATS(180) }
    case 'rag.generation.latency':
      return { name, measurements: LATENCY_STATS(2400) }
    case 'rag.llm.tokens.consumed':
      return { name, measurements: [{ statistic: 'COUNT', value: tick * 5200 }] }
    case 'rag.cost.usd':
      return { name, measurements: [{ statistic: 'COUNT', value: Number((tick * 0.012).toFixed(4)) }] }
    case 'agent.requests.total':
      return { name, measurements: [{ statistic: 'COUNT', value: tick * 2 }] }
    case 'agent.chat.latency':
      return { name, measurements: LATENCY_STATS(3800) }
    case 'system.cpu.usage':
      return { name, measurements: [{ statistic: 'VALUE', value: 0.23 + (tick % 3) * 0.05 }] }
    case 'jvm.memory.used':
      return { name, measurements: [{ statistic: 'VALUE', value: 412_000_000 + tick * 1_200_000 }] }
    case 'http.server.requests':
      return { name, measurements: LATENCY_STATS(85) }
    default:
      return { name, measurements: [{ statistic: 'COUNT', value: tick }] }
  }
}

/**
 * 提供模拟指标页面使用的指标名称集合。
 * 返回 MetricsListDTO。
 * 由 actuator.ts 的 getMetrics 触发。
 * 下游调用 delay。
 */
export function mockGetMetrics(): Promise<MetricsListDTO> {
  return delay(200).then(() => ({ names: METRIC_NAMES }))
}

/**
 * 推进模拟采样序号并返回一个指标详情。
 * 接收名称。
 * 由 actuator.ts 的 getMetric 触发。
 * 下游调用 delay、metricDetail。
 */
export function mockGetMetric(name: string): Promise<MetricDetailDTO> {
  return delay(150).then(() => {
    metricTick += 1
    return metricDetail(name)
  })
}
