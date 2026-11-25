<!-- 管理 Agent 对话与历史恢复，将回合事件、工具步骤和来源映射为交互状态。 -->

<script setup lang="ts">
import { Select as TSelect } from 'tdesign-vue-next'
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { RefreshIcon, RobotIcon } from 'tdesign-icons-vue-next'
import {
  AgentStreamError,
  createAgentSession,
  listAgentSessions,
  getAgentSessionHistory,
  deleteAgentSession,
  type AgentSessionDTO,
  type AgentSessionTurnDTO,
  listAgentProfiles,
  streamAgentChat,
  type AgentAnswerPolicy,
  type AgentDoneEvent,
  type AgentProfileDTO,
  type MemorySaveApproval,
} from '../api/agent'
import { listKnowledgeBases } from '../api/library'
import AgentProfileManager from '../components/AgentProfileManager.vue'
import AgentMemoryManager from '../components/AgentMemoryManager.vue'
import MemorySaveApprovalCard from '../components/MemorySaveApprovalCard.vue'
import { USE_MOCK } from '../config'
import type { KnowledgeBaseDTO, KnowledgeSourceDTO } from '../types'
import { agentAnswerParts } from '../agentCitations'
import { contextCapacityMessage } from '../ragOutcome'
import { agentStopReasonLabel } from '../agentOutcome'
import { isChatSendKey } from '../chatInput'

type NoticeTheme = 'success' | 'info' | 'warning' | 'error'
type AssistantState = 'pending' | 'done' | 'stopped' | 'failed'
type AgentSelection = number | 'default'

interface ToolStep {
  id: string
  round: number
  tool: string
  args?: string | null
  status: 'running' | 'ok' | 'no_evidence' | 'failed' | 'approval' | 'skipped'
  outcome?: 'success' | 'no_evidence' | 'dependency_unavailable' | 'timeout' | null
  reasonCode?: string | null
  summary?: string | null
  error?: string | null
}

interface AgentNotice {
  theme: NoticeTheme
  text: string
  detail?: string
}

interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  status: AssistantState
  streamText?: string
  preambles?: { round: number; text: string }[]
  steps?: ToolStep[]
  sources?: KnowledgeSourceDTO[]
  notice?: AgentNotice
  error?: { message: string; code?: string }
  phase?: string
  contextNote?: string
  limits?: { maxIterations: number; maxToolCalls: number }
  stats?: AgentDoneEvent
  answerReceived?: boolean
  approvalId?: string
  memoryApproval?: MemorySaveApproval
}

interface Conversation {
  id: string
  /** 服务端签发并绑定归属/策略；空字符串仅表示尚未发送的草稿。 */
  sessionId: string
  title: string
  agentId: AgentSelection
  answerPolicy?: AgentAnswerPolicy
  messages: ChatMessage[]
  createdAt: number
  loaded: boolean
  historyOffset: number
  hasMoreHistory: boolean
}

const DEFAULT_TITLE = '新会话'
const MAX_DRAFT = 2000
const SAMPLE_QUESTIONS = [
  '帮我总结员工手册里与年假相关的规定。',
  '这个知识库里有哪些关于检索流程的说明？',
  '先找到合同模板，再说明签署页的填写要求。',
]
const PHASE_LABELS: Record<string, string> = {
  think: '模型正在思考…',
  squeeze: '上下文超限，正在压缩后重试…',
  context_organize: '正在整理上下文…',
  context_ready: '上下文已整理，正在继续处理…',
  act: '正在调用工具…',
  observe: '正在整理工具结果…',
  finalize: '正在整理最终回答…',
}
const TOOL_LABELS: Record<string, string> = {
  knowledge_search: '检索知识库',
  knowledge_ask: '知识库问答',
  read_document: '阅读文档片段',
  memory_search: '检索长期记忆',
  memory_save: '保存长期记忆',
}

/** 生成仅用于前端消息或占位会话的本地标识。返回文本。由 createConversation、send 触发。 */
function uid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

/**
 * 为指定 Agent 配置建立尚无服务端会话 ID 的对话状态。
 * 接收Agent 配置 ID，返回 Conversation。
 * 由 loadSessions、removeConversation 触发。
 * 下游调用 uid。
 */
function createConversation(agentId: AgentSelection = 'default'): Conversation {
  return {
    id: uid(),
    sessionId: '',
    title: DEFAULT_TITLE,
    agentId,
    messages: [],
    createdAt: Date.now(),
    loaded: true,
    historyOffset: 0,
    hasMoreHistory: false,
  }
}

const conversations = ref<Conversation[]>([createConversation()])
const activeId = ref(conversations.value[0].id)
const activeConv = computed(() =>
  conversations.value.find((item) => item.id === activeId.value) ?? conversations.value[0],
)

const draft = ref('')
const route = useRoute()
const sending = ref(false)
const profiles = ref<AgentProfileDTO[]>([])
const knowledgeBases = ref<KnowledgeBaseDTO[]>([])
const profileLoading = ref(false)
const profileWarning = ref('')
const managerVisible = ref(false)
const memoryManagerVisible = ref(false)
const sessionLoading = ref(false)
const sessionWarning = ref('')
const sessionOffset = ref(0)
const moreSessions = ref(false)
const expandedSources = ref<(string | number)[]>([])
const scrollEl = ref<HTMLElement | null>(null)

const streamControllers = new Map<string, AbortController>()
const activeMessageId = ref<string | null>(null)

const agentOptions = computed(() => [
  // 侧栏宽度只有 224px，选项文案保持短名；可读范围由下方 scope 行单独说明。
  { label: '默认 Agent', value: 'default' as const },
  ...profiles.value.map((profile) => ({ label: profile.name, value: profile.id })),
])

const selectedProfile = computed(() => {
  const id = activeConv.value.agentId
  if (id === 'default') return null
  return profiles.value.find((profile) => profile.id === id) ?? null
})

const selectedScope = computed(() => {
  const profile = selectedProfile.value
  if (!profile) {
    return activeConv.value.agentId === 'default'
      ? `${activeConv.value.answerPolicy === 'grounded' ? '基于证据回答' : '允许通用回答'} · 全部可读知识库`
      : '该 Agent 配置已不存在，请重新选择。'
  }
  const scope = profile.kbSelectionMode === 'all'
    ? '全部可读知识库'
    : profile.kbSelectionMode === 'none'
      ? '不检索知识库'
      : `${profile.knowledgeBaseIds.length} 个指定知识库`
  return `${profile.name}：${scope} · ${profile.allowedTools.length} 个工具`
})

// ---------- 会话管理 ----------
/** 将已保存的 Agent 会话条目转换为页面对话状态。接收session，返回 Conversation。由 newConversation 触发。 */
function fromSession(session: AgentSessionDTO): Conversation {
  return { id: session.id, sessionId: session.id, title: session.title,
    agentId: session.agentId ?? 'default', answerPolicy: session.answerPolicy, createdAt: session.createdAt, messages: [],
    loaded: false, historyOffset: 0, hasMoreHistory: false }
}

/** 清空当前输入并打开所选 Agent 的新会话。由 当前模板的 @click 事件 触发。下游调用 agent 的 createAgentSession、fromSession。 */
async function newConversation() {
  if (sending.value || sessionLoading.value) return
  sessionLoading.value = true
  sessionWarning.value = ''
  try {
    const session = await createAgentSession(activeConv.value.agentId === 'default' ? null : activeConv.value.agentId)
    const conv = fromSession(session)
    conv.loaded = true
    conversations.value = [conv, ...conversations.value.filter((entry) => entry.sessionId)]
    activeId.value = conv.id
  } catch (cause) {
    sessionWarning.value = messageOf(cause, '新建会话失败。')
  } finally { sessionLoading.value = false }
}

/** 把已完成的服务端回合还原成用户和助手消息。接收turn，返回 ChatMessage[]。下游调用 toolStatus、applyDoneState。 */
function restoredMessages(turn: AgentSessionTurnDTO): ChatMessage[] {
  const assistant: ChatMessage = { id: `${turn.id}-assistant`, role: 'assistant', content: turn.answer ?? '',
    status: 'pending', answerReceived: turn.answer != null, sources: [],
    steps: (turn.steps ?? []).map<ToolStep>((step, index) => ({ id: `${turn.id}-step-${index}`, round: 0,
      tool: step.tool, status: toolStatus(step.outcome, step.reasonCode, step.ok),
      summary: step.summary, outcome: step.outcome, reasonCode: step.reasonCode })) }
  applyDoneState(assistant, turn)
  if (turn.stopReason === 'MEMORY_APPROVAL_REQUIRED') assistant.approvalId = turn.id
  return [{ id: `${turn.id}-user`, role: 'user', content: turn.question, status: 'done' }, assistant]
}

/**
 * 读取服务端已保存的对话轮次，并转换为当前会话的消息。
 * 接收页面对话状态、older。
 * 由 loadSessions、selectConversation 触发。
 * 下游调用 agent 的 getAgentSessionHistory、messageOf。
 */
async function loadHistory(conv: Conversation, older = false) {
  if (!conv.sessionId) return
  sessionLoading.value = true
  sessionWarning.value = ''
  try {
    // 首次恢复和继续翻页使用不同的偏移起点，已恢复消息保留当前顺序。
    const page = await getAgentSessionHistory(conv.sessionId, older ? conv.historyOffset : 0)
    const messages = page.items.flatMap(restoredMessages)
    conv.messages = older ? [...messages, ...conv.messages] : messages
    conv.title = page.session.title
    conv.answerPolicy = page.session.answerPolicy
    conv.loaded = true
    conv.historyOffset = page.nextOffset
    conv.hasMoreHistory = page.hasMore
  } catch (cause) {
    // 读取失败或策略变化时不继续展示缓存历史，也不静默换成新会话续写。
    conv.messages = []
    conv.loaded = false
    sessionWarning.value = messageOf(cause, '会话历史读取失败，请重试或创建新会话。')
  } finally { sessionLoading.value = false }
}

/**
 * 从服务端读取已有会话目录并恢复页面列表。
 * 接收append。
 * 由 当前模板的 @click 事件 触发。
 * 下游调用 agent 的 listAgentSessions、createConversation。
 */
async function loadSessions(append = false) {
  sessionLoading.value = true
  sessionWarning.value = ''
  try {
    const page = await listAgentSessions(append ? sessionOffset.value : 0)
    const incoming = page.items.map(fromSession)
    conversations.value = append
      ? [...conversations.value, ...incoming.filter((entry) => !conversations.value.some((old) => old.sessionId === entry.sessionId))]
      : incoming.length ? incoming : [createConversation()]
    sessionOffset.value = page.nextOffset
    moreSessions.value = page.hasMore
    if (!append) {
      activeId.value = conversations.value[0].id
      if (conversations.value[0].sessionId) await loadHistory(conversations.value[0])
    }
  } catch (cause) {
    sessionWarning.value = messageOf(cause, '会话目录读取失败，请重试。')
  } finally { sessionLoading.value = false }
}

/**
 * 切换到指定 Agent 会话并按需恢复历史。
 * 接收记录 ID。
 * 由 当前模板的 @click 事件 触发。
 * 下游调用 loadHistory。
 */
async function selectConversation(id: string) {
  if (sending.value || sessionLoading.value) return
  activeId.value = id
  const conv = activeConv.value
  // 每次重新打开都让后端重验当前权限与策略，避免沿用页面缓存。
  if (conv.sessionId) await loadHistory(conv)
}

/**
 * 删除或移除指定会话并维护当前对话选择。
 * 接收记录 ID。
 * 由 当前模板的 @confirm 事件 触发。
 * 下游调用 agent 的 deleteAgentSession、createConversation。
 */
async function removeConversation(id: string) {
  if (sending.value || sessionLoading.value) return

  const index = conversations.value.findIndex((item) => item.id === id)
  if (index === -1) return

  const conv = conversations.value[index]
  sessionLoading.value = true
  sessionWarning.value = ''
  try {
    if (conv.sessionId) {
      await deleteAgentSession(conv.sessionId)
      sessionOffset.value = Math.max(0, sessionOffset.value - 1)
    }
    conversations.value.splice(index, 1)
    if (activeId.value === id) {
      const next = conversations.value[Math.min(index, conversations.value.length - 1)] ?? createConversation()
      if (!conversations.value.includes(next)) conversations.value.unshift(next)
      activeId.value = next.id
      if (next.sessionId) await loadHistory(next)
    }
  } catch (cause) { sessionWarning.value = messageOf(cause, '会话删除失败。') }
  finally { sessionLoading.value = false }
}

onUnmounted(() => {
  // 组件卸载即取消在途回合：服务端据此停止模型与工具，避免后台继续消耗。
  for (const controller of streamControllers.values()) controller.abort()
  streamControllers.clear()
})

// ---------- Agent 配置 ----------
/** 从失败对象提取用户可读消息，缺少消息时使用当前操作提示。接收失败原因、兜底文案，返回文本。由 newConversation、loadHistory 触发。 */
function messageOf(cause: unknown, fallback: string): string {
  return cause instanceof Error && cause.message ? cause.message : fallback
}

/** 读取可用 Agent 配置并校正当前选择。由 onProfilesChanged 触发。下游调用 agent 的 listAgentProfiles、messageOf。 */
async function loadProfiles() {
  profileLoading.value = true
  try {
    const list = await listAgentProfiles()
    profiles.value = list
  } catch (cause) {
    profileWarning.value = messageOf(cause, 'Agent 配置加载失败；仍可使用默认 Agent。')
  } finally {
    profileLoading.value = false
  }
}

/** 读取当前账号可见的知识库，并同步到页面的范围选择。下游调用 library 的 listKnowledgeBases、messageOf。 */
async function loadKnowledgeBases() {
  try {
    knowledgeBases.value = await listKnowledgeBases()
  } catch (cause) {
    profileWarning.value = messageOf(cause, '知识库列表加载失败；Agent 配置暂不可编辑。')
  }
}

/** 配置维护结束后刷新 Agent 列表及相关会话状态。由 当前模板的 @changed 事件 触发。下游调用 loadProfiles。 */
function onProfilesChanged() {
  void loadProfiles()
}

/**
 * 按所选 Agent 复用合适会话或打开新会话。
 * 接收value、forceNew。
 * 由 onProfileSelected、当前模板的 @change 事件 触发。
 * 下游调用 createConversation。
 */
function switchAgent(value: unknown, forceNew = false) {
  if (value !== 'default' && (typeof value !== 'number' || !Number.isInteger(value))) return

  const nextAgent = value as AgentSelection
  const previous = activeConv.value
  if (!forceNew && previous.agentId === nextAgent) return

  // 切换或保存配置时创建新草稿；首轮发送前由服务端签发会话 ID。
  const next = createConversation(nextAgent)
  conversations.value.unshift(next)
  activeId.value = next.id
  draft.value = ''
  expandedSources.value = []
  if (previous.messages.length === 0) {
    conversations.value = conversations.value.filter((conv) => conv.id !== previous.id)
  }
}

/**
 * 处理选择器传入的 Agent 标识并切换当前配置。
 * 接收记录 ID。
 * 由 当前模板的 @select 事件 触发。
 * 下游调用 switchAgent。
 */
function onProfileSelected(id: number) {
  switchAgent(id, true)
}

onMounted(() => {
  if (route.query.panel === 'profiles') managerVisible.value = true
  if (route.query.panel === 'memory') memoryManagerVisible.value = true
  void loadProfiles()
  void loadKnowledgeBases()
  void loadSessions()
})

// ---------- 发送 ----------
/**
 * 合并工具和答案返回的来源，维持同一文档片段的稳定标识。
 * 接收待显示的消息、新到达的来源。
 * 由 applyDoneState、send 触发。
 * 下游调用 sourceKey。
 */
function applySources(message: ChatMessage, incoming?: KnowledgeSourceDTO[]) {
  if (!Array.isArray(incoming) || incoming.length === 0) return

  const merged = [...(message.sources ?? [])]
  const keys = new Set(merged.map(sourceKey))

  for (const source of incoming) {
    if (!source || typeof source !== 'object') continue
    if (typeof source.documentId !== 'number' || typeof source.chunkId !== 'number') continue
    if (typeof source.content !== 'string') continue
    const key = sourceKey(source)
    if (keys.has(key)) continue
    keys.add(key)
    merged.push(source)
  }
  message.sources = merged
}

/** 从文档位置和引用信息构造证据合并键。接收来源记录，返回文本。由 applySources、sourceDomId 触发。 */
function sourceKey(source: KnowledgeSourceDTO): string {
  return source.citationId && /^S\d+_\d+(?:_O\d+)?$/.test(source.citationId)
    ? source.citationId : `${source.documentId}:${source.chunkId}`
}

/**
 * 为某条消息中的来源计算页面定位标识。
 * 接收待显示的消息、来源记录，返回文本。
 * 由 locateSource 触发。
 * 下游调用 sourceKey。
 */
function sourceDomId(message: ChatMessage, source: KnowledgeSourceDTO): string {
  return `agent-source-${message.id}-${sourceKey(source)}`
}

/**
 * 展开来源并将对应证据滚动到可见位置。
 * 接收待显示的消息、来源记录。
 * 由 当前模板的 @click 事件 触发。
 * 下游调用 sourceKey、sourceDomId。
 */
async function locateSource(message: ChatMessage, source?: KnowledgeSourceDTO) {
  if (!source) return

  const key = sourceKey(source)
  if (!expandedSources.value.includes(key)) expandedSources.value.push(key)
  await nextTick()
  const element = document.getElementById(sourceDomId(message, source))
  element?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  element?.focus({ preventScroll: true })
}

/** 将模型停止原因转换为当前回合的提示级别和文本。接收reason，返回 AgentNotice | null。由 applyDoneState 触发。 */
function stopReasonNotice(reason?: string): AgentNotice | null {
  switch (reason) {
    case 'FINAL_ANSWER':
      return null
    case 'CANCELLED':
      return { theme: 'info', text: '本轮已取消。' }
    case 'FINAL_ANSWER_TRUNCATED':
      return { theme: 'warning', text: '回答在输出上限处被截断，可能不完整。' }
    case 'CONTENT_FILTERED':
      return { theme: 'warning', text: '回答被内容安全策略拦截，请换一种问法。' }
    case 'EMPTY_RESPONSE':
      return { theme: 'warning', text: '模型本轮未生成可展示内容。' }
    case 'STUCK_REPEATED':
      return { theme: 'warning', text: '模型反复给出相同内容且无进展，已中止本轮。' }
    case 'MAX_ITERATIONS':
      return { theme: 'warning', text: '达到最大轮次仍未得出结论，请重试或换一种问法。' }
    case 'MAX_TOOL_CALLS':
      return { theme: 'warning', text: '工具调用次数已达上限，未能得出结论。' }
    case 'BUDGET_EXCEEDED':
      return { theme: 'error', text: '本轮超出时间预算，未能得出结论。' }
    case 'LLM_DEGRADED':
      return { theme: 'error', text: 'AI 服务本轮不可用，返回的是降级提示。' }
    default:
      return null
  }
}

/**
 * 根据 SSE 终态更新回合状态、停止提示和统计信息。
 * 接收待显示的消息、done。
 * 由 restoredMessages、send 触发。
 * 下游调用 applySources、ragOutcome 的 contextCapacityMessage。
 */
function applyDoneState(message: ChatMessage, done: AgentDoneEvent) {
  message.stats = done
  applySources(message, done.sources)
  message.phase = undefined
  if (done.memoryApproval) {
    message.approvalId = done.memoryApproval.id
    message.memoryApproval = done.memoryApproval
  }
  if (done.stopReason === 'MEMORY_APPROVAL_REQUIRED') {
    message.status = 'done'
    return
  }

  if (done.outcome === 'dependency_unavailable') {
    message.notice = {
      theme: 'error',
      text: contextCapacityMessage(done.reasonCode) || contextCapacityMessage(done.degradeReason)
        || '依赖服务暂时不可用，本轮没有产出可靠回答。',
      detail: done.reasonCode ?? done.degradeReason ?? undefined,
    }
  } else if (done.outcome === 'timeout') {
    message.notice = {
      theme: 'error',
      text: '本轮处理超时，回答可能不完整。',
      detail: done.reasonCode ?? undefined,
    }
  } else if (done.degraded) {
    message.notice = {
      theme: 'error',
      text: 'AI 服务本轮不可用，返回的是降级提示，不是知识库回答。',
      detail: done.degradeReason ?? undefined,
    }
  } else if (done.outcome === 'no_evidence') {
    // 只有服务端明确给出 no_evidence 才提示没有证据；空 sources 本身不代表无证据。
    message.notice = { theme: 'warning', text: '本轮没有找到可进入回答的获准证据。' }
  } else if (done.stopReason === 'CANCELLED') {
    message.status = 'stopped'
    message.notice = { theme: 'info', text: '本轮已取消。' }
  } else {
    const notice = stopReasonNotice(done.stopReason)
    if (notice) {
      message.notice = notice
    } else if (done.synthesized) {
      message.notice = {
        theme: 'info',
        text: '本轮达到轮次或时间上限，回答由兜底合成生成，可能承认证据不足。',
      }
    }
  }

  if (message.status === 'pending') message.status = 'done'
}

/** 从流式异常提取安全的错误文字和原因码。接收失败原因，返回 { message: string; code?: string }。由 send 触发。 */
function describeStreamFailure(cause: unknown): { message: string; code?: string } {
  if (cause instanceof AgentStreamError) {
    if (cause.status === 401) return { message: '登录已失效，请重新登录。', code: 'unauthorized' }
    if (cause.status === 403) return { message: '无权使用当前 Agent 配置或知识库。', code: 'forbidden' }
    if (cause.status === 429) return { message: 'Agent 并发已满，请稍后重试。', code: 'busy' }
    return {
      message: cause.message || 'Agent 请求失败，请稍后重试。',
      code: cause.code ?? (cause.status ? `http_${cause.status}` : undefined),
    }
  }
  if (cause instanceof Error && cause.message) {
    return { message: cause.message }
  }
  return { message: 'Agent 请求失败，请稍后重试。' }
}

/**
 * 确定 Agent 会话并启动流式回合，将不同事件并入目标消息。
 * 由 onTextareaKeydown、当前模板的 @click 事件 触发。
 * 下游调用 loadHistory、agent 的 createAgentSession。
 */
async function send() {
  const conv = activeConv.value
  const question = draft.value.trim()
  if (!question || sending.value || sessionLoading.value) return
  if (conv.sessionId && !conv.loaded) {
    await loadHistory(conv)
    if (!conv.loaded) return
  }
  if (!conv.sessionId) {
    sessionLoading.value = true
    try {
      const session = await createAgentSession(conv.agentId === 'default' ? null : conv.agentId)
      conv.sessionId = session.id
      conv.answerPolicy = session.answerPolicy
    } catch (cause) {
      sessionWarning.value = messageOf(cause, '新建会话失败，本轮尚未发送。')
      return
    } finally { sessionLoading.value = false }
  }

  conv.messages.push({ id: uid(), role: 'user', content: question, status: 'done' })
  if (conv.title === DEFAULT_TITLE) conv.title = question.slice(0, 18)

  // 必须是 reactive 对象：push 后再直接修改原始对象不会触发渲染，流式事件会“静默丢失”。
  const message = reactive<ChatMessage>({
    id: uid(),
    role: 'assistant',
    content: '',
    status: 'pending',
    streamText: '',
    preambles: [],
    steps: [],
    sources: [],
  })
  conv.messages.push(message)
  draft.value = ''
  sending.value = true
  activeMessageId.value = message.id

  const controller = new AbortController()
  streamControllers.set(message.id, controller)
  let doneSeen = false
  let errorSeen = false

  await nextTick()
  scrollToBottom()

  try {
    // 回调与当前发送捕获的会话和消息关联，避免把迟到事件合并到另一个对话。
    await streamAgentChat(question, conv.sessionId, {
      agentId: conv.agentId === 'default' ? null : conv.agentId,
      signal: controller.signal,
      handlers: {
        /** 在服务端接受回合后更新当前消息的阶段信息。接收事件帧载荷。由 send 触发。 */
        onAccepted: (payload) => {
          message.limits = payload
        },

        /** 更新本次回合的运行轮次和阶段展示。接收step。由 send 触发。 */
        onRound: (step) => {
          message.phase = step.phase
          if (step.phase === 'context_ready') {
            const stillOverBudget = step.inputTokensAfter != null && step.inputBudget != null
              && step.inputTokensAfter > step.inputBudget
            message.contextNote = `${stillOverBudget ? '已尝试整理上下文，必需内容仍超限' : '已整理上下文'}：单次输入估算 ${step.inputTokensBefore} → ${step.inputTokensAfter} Token，可用 ${step.inputBudget}。`
          } else if (step.phase === 'context_organize') {
            message.contextNote = '本次单次输入超出容量，已触发上下文整理。'
          }
        },

        /**
         * 向已建立的助手消息附加模型正文增量。
         * 接收事件帧载荷。
         * 由 send 触发。
         * 下游调用 scrollSoon。
         */
        onDelta: (payload) => {
          if (payload.channel && payload.channel !== 'answer') return
          if (!payload.text) return
          message.streamText = (message.streamText ?? '') + payload.text
          scrollSoon()
        },

        /**
         * 按调用标识登记正在执行的工具步骤。
         * 接收事件帧载荷。
         * 由 send 触发。
         * 下游调用 uid、scrollSoon。
         */
        onToolCall: (payload) => {
          // 工具调用前的文字是引子，不是答案：移到执行过程，答案区从零开始累积。
          const preamble = (message.streamText ?? '').trim()

          if (preamble) {
            message.preambles ??= []
            message.preambles.push({ round: payload.round, text: preamble })
            message.streamText = ''
          }
          message.steps ??= []
          message.steps.push({
            id: uid(),
            round: payload.round,
            tool: payload.tool,
            args: payload.args ?? null,
            status: 'running',
          })
          message.phase = 'act'
          scrollSoon()
        },

        /**
         * 用同一调用标识合并工具结果、证据和执行状态。
         * 接收事件帧载荷。
         * 由 send 触发。
         * 下游调用 toolStatus、scrollSoon。
         */
        onToolResult: (payload) => {
          const steps = message.steps ?? []
          const step = [...steps].reverse().find(
            (item) => item.status === 'running' && item.round === payload.round && item.tool === payload.tool,
          ) ?? [...steps].reverse().find((item) => item.status === 'running')

          if (step) {
            step.status = toolStatus(payload.outcome, payload.reasonCode, payload.ok)
            step.outcome = payload.outcome ?? null
            step.reasonCode = payload.reasonCode ?? null
            step.summary = payload.summary ?? null
            step.error = payload.error ?? null
          }
          scrollSoon()
        },

        /**
         * 应用助手最终答案及其来源、说明或待确认记忆。
         * 接收事件帧载荷。
         * 由 send 触发。
         * 下游调用 applySources、scrollSoon。
         */
        onAnswer: (payload) => {
          message.answerReceived = true
          message.content = payload.text
          message.streamText = ''
          applySources(message, payload.sources)
          if (payload.outcome) {
            message.stats = {
              ...(message.stats ?? {}),
              outcome: payload.outcome,
              reasonCode: payload.reasonCode ?? null,
            }
          }
          scrollSoon()
        },

        /**
         * 将最终状态及统计写到对应消息，并结束页面等待。
         * 接收事件帧载荷。
         * 由 send 触发。
         * 下游调用 applyDoneState、scrollSoon。
         */
        onDone: (payload) => {
          doneSeen = true
          if (payload.stopReason !== 'CANCELLED') conv.historyOffset += 1
          applyDoneState(message, payload)
          scrollSoon()
        },

        /**
         * 将 SSE 失败帧转换成流式请求的业务异常。
         * 接收事件帧载荷。
         * 由 send 触发。
         * 下游调用 scrollSoon。
         */
        onError: (payload) => {
          errorSeen = true
          message.status = 'failed'
          message.phase = undefined
          message.error = {
            message: payload.message || 'Agent 回合失败。',
            code: payload.code ?? payload.reasonCode ?? payload.outcome,
          }
          scrollSoon()
        },
      },
    })

    if (!doneSeen && !errorSeen) {
      if (controller.signal.aborted) {
        markStopped(message)
      } else {
        message.status = 'failed'
        message.error = { message: '连接意外结束，未能确认本轮回答是否完整。', code: 'stream_interrupted' }
      }
    }
  } catch (cause) {
    if (controller.signal.aborted && !doneSeen) {
      markStopped(message)
    } else if (!doneSeen && !errorSeen) {
      message.status = 'failed'
      message.error = describeStreamFailure(cause)
    }
  } finally {
    streamControllers.delete(message.id)
    if (activeMessageId.value === message.id) {
      activeMessageId.value = null
      sending.value = false
    }
    message.phase = undefined
    // 目录元数据由服务端保存；当前答案仍使用已收到的 done，不额外轮询回合状态。
    if (doneSeen) conv.loaded = true
    await nextTick()
    scrollToBottom()
  }
}

/** 把当前助手消息及未完工具步骤标记为已经停止。接收待显示的消息。由 send 触发。 */
function markStopped(message: ChatMessage) {
  message.status = 'stopped'
  message.phase = undefined
  if (!message.notice) message.notice = { theme: 'info', text: '已停止本轮生成。' }
}

/** 中止浏览器当前的 Agent 回合流并更新页面状态。由 当前模板的 @click 事件 触发。 */
function stop() {
  const id = activeMessageId.value
  if (!id) return
  streamControllers.get(id)?.abort()
}

/**
 * 按共用的发送键规则处理编辑器键盘事件。
 * 接收_value、context。
 * 由 当前模板的 @keydown 事件 触发。
 * 下游调用 chatInput 的 isChatSendKey、send。
 */
function onTextareaKeydown(_value: string, context: { e: KeyboardEvent }) {
  if (isChatSendKey(context.e)) {
    context.e.preventDefault()
    void send()
  }
}

// ---------- 展示辅助 ----------
/** 把回合阶段代码映射到聊天过程面板使用的短标签。接收phase，返回文本。 */
function phaseLabel(phase?: string): string {
  return phase ? (PHASE_LABELS[phase] ?? '正在处理…') : '正在处理…'
}

/** 将内部工具名转换为过程面板的操作名称。接收tool，返回文本。 */
function toolLabel(tool: string): string {
  return TOOL_LABELS[tool] ?? tool
}


/** 统计一条消息中执行失败的工具步骤数。接收待显示的消息，返回数值。 */
function failedCount(message: ChatMessage): number {
  return (message.steps ?? []).filter((step) => step.status === 'failed').length
}

/** 统计成功执行但缺证的工具步骤数。接收待显示的消息，返回数值。 */
function noEvidenceCount(message: ChatMessage): number {
  return (message.steps ?? []).filter((step) => step.status === 'no_evidence').length
}

/** 结合工具执行码和业务结果确定步骤状态。接收业务终态、原因码、ok，返回 ToolStep['status']。由 restoredMessages、send 触发。 */
function toolStatus(outcome?: string | null, reasonCode?: string | null, ok = false): ToolStep['status'] {
  if (reasonCode === 'memory_approval_required') return 'approval'
  if (reasonCode === 'skipped_for_memory_approval') return 'skipped'
  if (outcome === 'no_evidence') return 'no_evidence'
  return ok ? 'ok' : 'failed'
}

/** 把工具步骤状态映射为用户可读的标签。接收step，返回文本。 */
function toolResultLabel(step: ToolStep): string {
  if (step.status === 'approval') return '已发起审批'
  if (step.status === 'skipped') return '未执行'
  if (step.outcome === 'no_evidence') return '无获准证据'
  if (step.outcome === 'dependency_unavailable') return '依赖不可用'
  if (step.outcome === 'timeout') return '超时'
  return step.status === 'ok' ? '成功' : step.status === 'failed' ? '失败' : '执行中'
}

/** 判断消息是否包含需要渲染的执行步骤。接收待显示的消息，返回布尔判定。 */
function hasProcess(message: ChatMessage): boolean {
  return (message.steps?.length ?? 0) > 0 || (message.preambles?.length ?? 0) > 0
}

/** 将工具参数整理为可阅读格式，保留无法解析的原文本。接收args，返回文本。 */
function prettyArgs(args: string): string {
  try {
    return JSON.stringify(JSON.parse(args), null, 2)
  } catch {
    return args
  }
}

/** 选取消息中可渲染的回答正文。接收待显示的消息，返回文本。下游调用 ragOutcome 的 contextCapacityMessage。 */
function answerText(message: ChatMessage): string {
  if (message.approvalId) return ''
  // 容量失败已有明确提示，不再把服务端的降级说明重复展示成答案。
  if (contextCapacityMessage(message.stats?.reasonCode) || contextCapacityMessage(message.stats?.degradeReason)) return ''
  if (message.content) return message.content
  if (message.streamText) return message.streamText
  if (message.status === 'done' && !message.answerReceived) return '本轮未产生可展示的回答。'
  return ''
}

/** 优先用来源的可读标题，缺失时使用位置或序号生成标题。接收来源记录、列表索引，返回文本。 */
function sourceTitle(source: KnowledgeSourceDTO, index: number): string {
  return source.headingPath
    ?? (source.pageNo != null ? `第 ${source.pageNo} 页` : `来源片段 ${index + 1}`)
}

/** 从回合统计中读取页面展示的令牌总量。接收stats，返回数值。 */
function tokenTotal(stats?: AgentDoneEvent): number {
  return (stats?.promptTokens ?? 0) + (stats?.completionTokens ?? 0)
}

// ---------- 自动滚动 ----------
/** 将当前消息容器定位到最新消息。由 send、scrollSoon 触发。 */
function scrollToBottom() {
  const el = scrollEl.value
  if (el) el.scrollTop = el.scrollHeight
}

/** 等待界面渲染后将消息容器滚到最新一条。由 send、onDelta 触发。下游调用 scrollToBottom。 */
function scrollSoon() {
  void nextTick(() => scrollToBottom())
}

watch(() => activeConv.value.id, async () => {
  await nextTick()
  scrollToBottom()
})

watch(
  () => activeConv.value.messages.map((message) => [
    message.status,
    message.content.length,
    message.streamText?.length ?? 0,
    (message.steps ?? []).map((step) => step.status).join(','),
    message.notice?.text ?? '',
    message.error?.message ?? '',
  ].join(':')).join('|'),
  async () => {
    await nextTick()
    scrollToBottom()
  },
)
</script>

<template>
  <section class="chat-page">
    <!-- 左侧：Agent 选择 + 服务端会话目录 -->
    <aside class="agent-sidebar">
      <div class="agent-picker">
        <span class="picker-label">当前 Agent</span>
        <t-select
          :value="activeConv.agentId"
          :options="agentOptions"
          :loading="profileLoading"
          :disabled="sending || sessionLoading"
          placeholder="选择 Agent"
          @change="value => switchAgent(value)"
        />
        <p class="agent-scope muted">{{ selectedScope }}</p>
        <t-button block variant="outline" size="small" :disabled="sending" @click="managerVisible = true">
          管理 Agent 配置
        </t-button>
        <t-button block variant="outline" size="small" :disabled="sending" @click="memoryManagerVisible = true">
          管理长期记忆
        </t-button>
        <t-alert v-if="profileWarning" theme="warning" class="picker-warning">
          {{ profileWarning }}
        </t-alert>
        <p class="picker-hint muted">切换或保存 Agent 配置会开始新会话；已有对话仍留在左侧原会话中。</p>
      </div>

      <t-button class="new-conv-btn" theme="primary" variant="outline" block :disabled="sending || sessionLoading" @click="newConversation">
        ＋ 新建会话
      </t-button>
      <div class="conv-history-head">
        <h2>会话记录</h2>
        <t-button size="small" variant="outline" aria-label="刷新会话记录" :disabled="sessionLoading || sending" @click="loadSessions()">
          <template #icon><RefreshIcon /></template>
          刷新
        </t-button>
      </div>
      <ul class="conv-list" aria-label="Agent 会话记录">
        <li
          v-for="conv in conversations"
          :key="conv.id"
          class="conv-item"
          :class="{ active: conv.id === activeId }"
        >
          <button type="button" class="conv-title conv-open" :title="conv.title"
            :aria-current="conv.id === activeId ? 'true' : undefined" :disabled="sending || sessionLoading"
            @click="selectConversation(conv.id)">{{ conv.title }}</button>
          <t-popconfirm v-if="conv.sessionId" content="删除此会话及其全部对话记录？" @confirm="removeConversation(conv.id)">
            <t-button variant="text" size="small" class="conv-delete"
              :aria-label="`删除会话：${conv.title}`" :disabled="sending || sessionLoading">删除</t-button>
          </t-popconfirm>
        </li>
      </ul>
      <t-button v-if="moreSessions" class="conv-more" variant="text" :disabled="sessionLoading || sending" @click="loadSessions(true)">更多会话</t-button>
      <t-alert v-if="sessionWarning" theme="warning">{{ sessionWarning }}</t-alert>
      <p class="conv-foot muted">已完成回合保存在服务端，保留至会话到期；取消内容不入历史。</p>
    </aside>

    <!-- 中间：对话区 -->
    <div class="chat-main">
      <header class="page-head">
        <div>
          <h1>Agent 助手</h1>
          <p>由 Agent 规划并调用授权工具；答案与来源以服务端返回为准</p>
        </div>
        <div class="page-head-actions">
          <t-tag v-if="USE_MOCK" theme="warning" variant="light-outline">开发模拟数据</t-tag>
        </div>
      </header>

      <div ref="scrollEl" class="messages">
        <t-button v-if="activeConv.hasMoreHistory" variant="text" :disabled="sending || sessionLoading" @click="loadHistory(activeConv, true)">加载更早回合</t-button>
        <p v-if="sessionLoading" class="muted">正在读取会话…</p>
        <div v-if="activeConv.messages.length === 0" class="chat-empty">
          <div class="welcome-icon" aria-hidden="true"><RobotIcon /></div>
          <p>和 Agent 开始对话</p>
          <span v-if="activeConv.agentId === 'default' && activeConv.answerPolicy !== 'grounded'">可以直接聊天，也可以让 Agent 查询企业资料。检索结果和引用会随回答展示。</span>
          <span v-else>按当前 Agent 的回答策略提问，使用工具时可在回答下方查看过程与来源。</span>
          <div class="sample-list">
            <button
              v-for="sample in SAMPLE_QUESTIONS"
              :key="sample"
              type="button"
              class="sample-chip"
              @click="draft = sample"
            >
              {{ sample }}
            </button>
          </div>
        </div>

        <template v-for="msg in activeConv.messages" :key="msg.id">
          <div v-if="msg.role === 'user'" class="msg-row user">
            <div class="bubble user-bubble">{{ msg.content }}</div>
          </div>

          <div v-else class="msg-row assistant">
            <div class="bubble ai-bubble">
              <div v-if="msg.status === 'pending'" class="run-state" role="status" aria-live="polite">
                <span class="loading-dot" />
                <span>{{ phaseLabel(msg.phase) }}</span>
                <span v-if="msg.limits" class="run-budget">
                  上限 {{ msg.limits.maxIterations }} 轮 / {{ msg.limits.maxToolCalls }} 次工具
                </span>
              </div>

              <small v-if="msg.contextNote" class="muted">{{ msg.contextNote }}</small>
              <MemorySaveApprovalCard
                v-if="msg.approvalId && activeConv.sessionId && !USE_MOCK"
                :key="msg.approvalId"
                :session-id="activeConv.sessionId"
                :approval-id="msg.approvalId"
                :initial-approval="msg.memoryApproval"
                :busy="sending"
              />
              <t-alert v-if="msg.error" theme="error" class="msg-alert" role="alert">
                <div class="error-body">
                  <span>{{ msg.error.message }}</span>
                  <small v-if="msg.error.code" class="meta-line">错误代码：{{ msg.error.code }}</small>
                </div>
              </t-alert>
              <t-alert v-else-if="msg.notice" :theme="msg.notice.theme" class="msg-alert">
                <div class="error-body">
                  <span>{{ msg.notice.text }}</span>
                  <small v-if="msg.notice.detail" class="meta-line">原因：{{ msg.notice.detail }}</small>
                </div>
              </t-alert>

              <details v-if="hasProcess(msg)" class="process-block" :open="msg.status === 'pending'">
                <summary>
                  <span>执行过程</span>
                  <span class="process-meta">
                    {{ msg.steps?.length ?? 0 }} 次工具调用<template v-if="noEvidenceCount(msg)"> · {{ noEvidenceCount(msg) }} 次无证据</template><template v-if="failedCount(msg)"> · {{ failedCount(msg) }} 次失败</template>
                  </span>
                </summary>
                <div v-if="msg.preambles?.length" class="preambles">
                  <p v-for="(item, index) in msg.preambles" :key="index" class="preamble">
                    <span class="preamble-round">第 {{ item.round }} 轮</span>{{ item.text }}
                  </p>
                </div>
                <ol v-if="msg.steps?.length" class="tool-steps">
                  <li v-for="step in msg.steps" :key="step.id" class="tool-step" :class="step.status">
                    <div class="tool-head">
                      <span class="tool-name">{{ toolLabel(step.tool) }} <code>{{ step.tool }}</code></span>
                      <t-tag
                        size="small"
                        :theme="step.status === 'ok' ? 'success' : step.status === 'no_evidence' ? 'warning' : step.status === 'failed' ? 'danger' : 'default'"
                        variant="light-outline"
                      >
                        {{ toolResultLabel(step) }}
                      </t-tag>
                    </div>
                    <pre v-if="step.args" class="tool-args">{{ prettyArgs(step.args) }}</pre>
                    <p v-if="step.summary" class="tool-summary">{{ step.summary }}</p>
                    <p v-if="step.error" class="tool-error">{{ step.error }}</p>
                    <p v-if="step.reasonCode" class="tool-reason">原因代码：{{ step.reasonCode }}</p>
                  </li>
                </ol>
              </details>

              <p v-if="answerText(msg)" class="answer-text">
                <template v-for="(part, partIndex) in agentAnswerParts(answerText(msg), msg.sources)" :key="partIndex">
                  <button
                    v-if="part.source"
                    type="button"
                    class="answer-citation"
                    :aria-label="`查看来源 ${part.source.citationId}`"
                    @click="locateSource(msg, part.source)"
                  >[{{ part.source.citationId }}]</button>
                  <template v-else>{{ part.text }}</template>
                </template><span v-if="msg.status === 'pending' && msg.streamText" class="typing-caret" aria-hidden="true" />
              </p>

              <!-- 来源只来自服务端准入集合，不从工具摘要或原始候选推导。 -->
              <div v-if="msg.sources?.length" class="sources-block">
                <div class="sources-head">
                  <span>可追溯来源</span>
                  <span class="source-count">{{ msg.sources.length }}</span>
                </div>
                <p class="sources-note muted">
                  来源为本轮实际进入模型的获准片段，不代表最终回答的每个断言都已逐句核验。
                </p>
                <t-collapse
                  v-model:value="expandedSources"
                  borderless
                  expand-icon-placement="right"
                  class="source-collapse"
                >
                  <t-collapse-panel
                    v-for="(source, index) in msg.sources"
                    :key="sourceKey(source)"
                    :value="sourceKey(source)"
                  >
                    <template #header>
                      <div :id="sourceDomId(msg, source)" class="source-header" tabindex="-1">
                        <span class="source-index">{{ String(index + 1).padStart(2, '0') }}</span>
                        <div class="source-titles">
                          <strong><template v-if="source.citationId">[{{ source.citationId }}] </template>{{ sourceTitle(source, index) }}</strong>
                          <small>
                            文档 #{{ source.documentId }}
                            <span v-if="source.chunkIndex != null"> · 片段 {{ source.chunkIndex }}</span>
                            <span v-if="source.sourceType"> · {{ source.sourceType }}</span>
                          </small>
                        </div>
                        <span
                          v-if="source.distance != null"
                          class="source-score"
                          title="向量余弦距离，数值越小表示越相关"
                        >
                          距离 {{ source.distance.toFixed(4) }}
                        </span>
                      </div>
                    </template>
                    <p class="source-content">{{ source.content }}</p>
                  </t-collapse-panel>
                </t-collapse>
              </div>

              <div v-if="msg.status !== 'pending' && msg.stats" class="exec-summary">
                <span v-if="msg.stats.iterations != null">轮次 <strong>{{ msg.stats.iterations }}</strong></span>
                <span>工具调用 <strong>{{ msg.stats.toolCalls ?? 0 }}</strong></span>
                <span v-if="tokenTotal(msg.stats)">累计用量 Token <strong>{{ tokenTotal(msg.stats) }}</strong></span>
                <span v-if="msg.stats.stopReason">终止原因 <strong>{{ agentStopReasonLabel(msg.stats) }}</strong></span>
                <span v-if="msg.stats.synthesized" class="meta-line">兜底合成</span>
              </div>
            </div>
          </div>
        </template>
      </div>

      <footer class="composer">
        <t-textarea
          v-model:value="draft"
          class="composer-textarea"
          :maxlength="MAX_DRAFT"
          :autosize="{ minRows: 2, maxRows: 6 }"
          placeholder="输入你的问题"
          @keydown="onTextareaKeydown"
        />
        <div class="composer-footer">
          <span class="shortcut-hint muted">Enter 发送 · Shift + Enter 换行</span>
          <div class="composer-actions">
            <t-button v-if="sending" variant="outline" @click="stop">停止</t-button>
            <t-button
              theme="primary"
              :loading="sending"
              :disabled="sending || !draft.trim()"
              @click="send"
            >
              {{ sending ? 'Agent 正在执行…' : '发送' }}
              <span aria-hidden="true">→</span>
            </t-button>
          </div>
        </div>
      </footer>
    </div>

    <AgentMemoryManager v-model:visible="memoryManagerVisible" />
    <AgentProfileManager
      v-model:visible="managerVisible"
      :profiles="profiles"
      :knowledge-bases="knowledgeBases"
      @changed="onProfilesChanged"
      @select="onProfileSelected"
    />
  </section>
</template>

<style scoped>
/* 气泡、来源卡片、输入区、会话栏都在 styles/chat.css 里统一维护。
   这里保留 Agent 独有的部分：配置选择器与工具执行过程。 */

/* ---------- 左侧配置选择器 ---------- */
.agent-picker {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding-bottom: var(--space-2);
  border-bottom: 1px solid var(--border);
}

.picker-label {
  color: var(--text-dim);
  font-size: var(--fs-sm);
}

.agent-picker :deep(.t-select) {
  width: 100%;
}

.agent-scope,
.picker-hint {
  margin: 0;
  font-size: var(--fs-micro);
  line-height: var(--lh-base);
}

.picker-warning {
  font-size: var(--fs-sm);
}

/* 列表尾部的辅助操作左对齐，读起来像会话列表的延伸，而不是一条居中漂浮的链接 */
.conv-more {
  align-self: flex-start;
  margin-left: -8px;
  color: var(--text-dim);
}

.run-budget {
  font-size: var(--fs-micro);
}

/* ---------- 工具执行过程 ---------- */
.process-block {
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-inset);
}

.process-block > summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 12px;
  color: var(--text-dim);
  font-size: var(--fs-sm);
  cursor: pointer;
  user-select: none;
}

.process-block > summary:hover {
  color: var(--text);
}

.process-meta {
  font-size: var(--fs-micro);
}

.preambles {
  padding: 0 12px 4px;
}

.preamble {
  margin: 6px 0;
  color: var(--text-dim);
  font-size: var(--fs-sm);
  line-height: var(--lh-base);
  white-space: pre-wrap;
}

.preamble-round {
  margin-right: 6px;
  border: 1px solid var(--border);
  border-radius: var(--radius-full);
  padding: 1px 6px;
  font-family: var(--font-mono);
  font-size: var(--fs-micro);
}

.tool-steps {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 0;
  padding: 4px 12px 10px;
  list-style: none;
}

.tool-step {
  border-left: 2px solid var(--border);
  padding: 3px 0 3px 10px;
}

.tool-step.running {
  border-left-color: var(--accent);
}

.tool-step.ok {
  border-left-color: var(--ok);
}

.tool-step.no_evidence {
  border-left-color: var(--warn);
}

.tool-step.failed {
  border-left-color: var(--err);
}

.tool-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.tool-name {
  color: var(--text);
  font-size: var(--fs-sm);
}

.tool-name code {
  margin-left: 6px;
  color: var(--text-dim);
  font-size: var(--fs-micro);
}

.tool-args {
  margin: 6px 0 0;
  border-radius: var(--radius-sm);
  padding: 8px;
  background: var(--bg-inset-deep);
  color: var(--text-dim);
  font-size: var(--fs-micro);
  line-height: var(--lh-base);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.tool-summary,
.tool-error {
  margin: 6px 0 0;
  font-size: var(--fs-sm);
  line-height: var(--lh-base);
}

.tool-summary {
  color: var(--text-dim);
}

.tool-error {
  color: var(--err);
}

.tool-reason {
  margin: 4px 0 0;
  color: var(--text-dim);
  font-size: var(--fs-micro);
}

/* ---------- 引用按钮与来源说明 ---------- */
.answer-citation {
  border: 0;
  padding: 0 3px;
  background: transparent;
  color: var(--accent);
  font: inherit;
  cursor: pointer;
  text-decoration: underline;
}

.sources-note {
  margin: 0;
  font-size: var(--fs-micro);
  line-height: var(--lh-base);
}

.composer-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

/* ---------- 窄屏：配置选择器换行占满一行 ---------- */
@media (max-width: 900px) {
  .agent-picker {
    flex: 1 1 260px;
    min-width: 220px;
    padding-bottom: 0;
    border-bottom: none;
  }
}
</style>
