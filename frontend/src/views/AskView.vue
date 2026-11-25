<!-- 组织 RAG 问答范围、会话历史、流式结果与页面内取消操作。 -->

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute } from 'vue-router'
import { SearchIcon } from 'tdesign-icons-vue-next'
import { USE_MOCK } from '../config'
import { askKnowledgeStream, createRagSession, deleteRagSession, getRagSessionHistory, listRagSessions, type RagSession } from '../api/knowledge'
import { listKnowledgeBases } from '../api/library'
import type { KnowledgeAskResponseDTO, KnowledgeBaseDTO, KnowledgeSourceDTO, PipelineEngine, RagStrategy } from '../types'
import { presentRagResult, safeRagReasonCode } from '../ragOutcome'
import { evidenceModeLabel, pipelineEngineLabel, retrievalVersionLabel } from '../ragExecution'
import { isChatSendKey } from '../chatInput'

type NoticeTheme = 'success' | 'info' | 'warning' | 'error'

interface AssistantError {
  message: string
  outcome: string
  reasonCode: string
  failedStage: string
  requestId: string
}

interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  status: 'pending' | 'done'
  typing?: boolean
  progressText?: string
  contextNote?: string
  // 以下为 assistant 专有
  sources?: KnowledgeSourceDTO[]
  showSources?: boolean
  notice?: { theme: NoticeTheme; text: string; reasonCode?: string; requestId?: string }
  exec?: { engine?: string | null; version?: string | null; evidenceMode?: string | null }
  error?: AssistantError
}

interface Conversation {
  id: string
  serverId?: string
  available: boolean
  historyLoading?: boolean
  historyError?: string
  nextBeforeId?: number | null
  title: string
  engine: PipelineEngine
  strategy: RagStrategy
  knowledgeBaseId: number | null
  messages: ChatMessage[]
}

const DEFAULT_TITLE = '新会话'
const MAX_DRAFT = 2000
const STRATEGY_LABELS: Record<RagStrategy, string> = {
  default: '部署默认',
  light: '轻量问答（跳过重排）',
  rerank_support: '重排 + 正文审查',
  rerank_score: '重排 + 分数阈值',
}
const SAMPLE_QUESTIONS = [
  '文档上传后，系统如何把它变成可检索的知识片段？',
  '如何配置 pgvector 向量检索？',
  'Rerank 模型在整条问答流程里起什么作用？',
]

/** 生成仅用于前端消息或占位会话的本地标识。返回文本。由 createConversation、send 触发。 */
function uid(): string {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

/**
 * 为当前检索范围和引擎建立尚未提交的新会话状态。
 * 返回 Conversation。
 * 由 restoreConversation、addConversation 触发。
 * 下游调用 uid。
 */
function createConversation(): Conversation {
  return { id: uid(), available: true, title: DEFAULT_TITLE, engine: 'fixed', strategy: 'default',
    knowledgeBaseId: null, messages: [] }
}

// 空白输入区可以直接提问，只在新建或发送时把草稿加入会话列表。
const idleConversation = ref(createConversation())
const conversations = ref<Conversation[]>([])
const activeId = ref(idleConversation.value.id)
const activeConv = computed(() =>
  conversations.value.find((item) => item.id === activeId.value) ?? idleConversation.value,
)

const draft = ref('')
const composer = ref<HTMLElement | null>(null)
const route = useRoute()
const pendingMode = ref<PipelineEngine | null>(null)
const sending = computed(() => pendingMode.value !== null)
const knowledgeBases = ref<KnowledgeBaseDTO[]>([])
const loadingKnowledgeBases = ref(false)
const knowledgeBaseListFailed = ref(false)
const knowledgeBaseListLoaded = ref(false)
const requestedScopeId = ref<number | null>(null)
const requestedScopeUnavailable = ref(false)
const canSend = computed(() => activeConv.value.available && !activeConv.value.historyLoading
  && !activeConv.value.historyError && requestedScopeId.value === null && !requestedScopeUnavailable.value)
const scopeLabel = computed(() => {
  const { available, knowledgeBaseId } = activeConv.value
  if (!available) return '知识库已不可访问'
  if (knowledgeBaseId === null) return '全部可读知识库 · 单轮'
  const library = knowledgeBases.value.find(item => item.id === knowledgeBaseId)
  return `${library?.name ?? `知识库 #${knowledgeBaseId}`} · 可追问`
})
// 来源折叠面板的展开值（chunkId 唯一，多个消息可共用一份状态）
const expandedPanels = ref<(string | number)[]>([])
const sessionsLoading = ref(false)
const sessionsError = ref('')
const sessionOffset = ref<number | null>(0)
const deletingId = ref<string | null>(null)

/**
 * 读取当前账号可见的知识库，并同步到页面的范围选择。
 * 由 当前模板的 @click 事件 触发。
 * 下游调用 library 的 listKnowledgeBases、applyRequestedScope。
 */
async function loadKnowledgeBases() {
  loadingKnowledgeBases.value = true
  knowledgeBaseListFailed.value = false
  try {
    knowledgeBases.value = await listKnowledgeBases()
    knowledgeBaseListLoaded.value = true
    applyRequestedScope()
  } catch {
    knowledgeBaseListFailed.value = true
  } finally {
    loadingKnowledgeBases.value = false
  }
}

/** 从入口路由读取知识库参数，并在可读列表中应用范围。由 loadKnowledgeBases 触发。 */
function applyRequestedScope() {
  const id = requestedScopeId.value
  if (id === null || !knowledgeBaseListLoaded.value) return
  requestedScopeId.value = null
  const library = knowledgeBases.value.find(item => item.id === id)
  if (library) activeConv.value.knowledgeBaseId = library.id
  requestedScopeUnavailable.value = !library
}

/** 清除路由带入的知识库范围，避免影响后续会话。由 useAllReadableLibraries、newConversation 触发。 */
function clearRequestedScope() {
  requestedScopeId.value = null
  requestedScopeUnavailable.value = false
}

/** 把新问答的范围切换为全部可读知识库。由 当前模板的 @click 事件 触发。下游调用 clearRequestedScope。 */
function useAllReadableLibraries() {
  activeConv.value.knowledgeBaseId = null
  clearRequestedScope()
}

// ---------- 会话管理 ----------
/**
 * 将服务端会话的范围、可访问状态和标题恢复为页面状态。
 * 接收session，返回 Conversation。
 * 由 loadSessions 触发。
 * 下游调用 createConversation。
 */
function restoreConversation(session: RagSession): Conversation {
  return {
    ...createConversation(), id: session.id, serverId: session.id, title: session.title,
    available: session.available, knowledgeBaseId: session.knowledgeBaseId,
  }
}

/** 从服务端读取已有会话目录并恢复页面列表。由 当前模板的 @click 事件 触发。下游调用 knowledge 的 listRagSessions、restoreConversation。 */
async function loadSessions() {
  if (sessionsLoading.value || sessionOffset.value === null) return
  sessionsLoading.value = true
  sessionsError.value = ''
  try {
    const page = await listRagSessions(sessionOffset.value)
    for (const session of page.items) {
      if (!conversations.value.some((item) => item.serverId === session.id)) {
        conversations.value.push(restoreConversation(session))
      }
    }
    sessionOffset.value = page.nextOffset
  } catch {
    sessionsError.value = '无法读取会话列表。'
  } finally {
    sessionsLoading.value = false
  }
}

/**
 * 读取服务端已保存的对话轮次，并转换为当前会话的消息。
 * 接收页面对话状态、历史页游标。
 * 由 selectConversation、当前模板的 @click 事件 触发。
 * 下游调用 knowledge 的 getRagSessionHistory、showResult。
 */
async function loadHistory(conv: Conversation, beforeId?: number) {
  if (!conv.serverId || !conv.available || conv.historyLoading) return
  conv.historyLoading = true
  conv.historyError = undefined
  const previousHeight = scrollEl.value?.scrollHeight ?? 0
  const previousTop = scrollEl.value?.scrollTop ?? 0

  try {
    const page = await getRagSessionHistory(conv.serverId, beforeId)
    const messages = page.items.flatMap((turn): ChatMessage[] => {
      const answer: ChatMessage = { id: `answer-${turn.id}`, role: 'assistant', content: '', status: 'done' }
      showResult(answer, turn.response)
      return [{ id: `question-${turn.id}`, role: 'user', content: turn.question, status: 'done' }, answer]
    })
    conv.messages = beforeId === undefined ? messages : [...messages, ...conv.messages]
    conv.nextBeforeId = page.nextBeforeId
  } catch {
    conv.messages = []
    conv.nextBeforeId = null
    conv.historyError = '无法读取会话记录，知识库访问权限可能已变更。'
  } finally {
    conv.historyLoading = false
    await nextTick()
    if (activeId.value === conv.id && scrollEl.value && beforeId !== undefined) {
      scrollEl.value.scrollTop = previousTop + scrollEl.value.scrollHeight - previousHeight
    }
  }
}

/**
 * 将新会话加入本地目录并设为当前会话。
 * 接收页面对话状态。
 * 由 newConversation、send 触发。
 * 下游调用 createConversation。
 */
function addConversation(conv: Conversation) {
  conversations.value.unshift(conv)
  activeId.value = conv.id
  if (conv.id === idleConversation.value.id) idleConversation.value = createConversation()
}

/** 打开新问答并初始化范围选择和输入状态。由 当前模板的 @click 事件 触发。下游调用 clearRequestedScope、addConversation。 */
function newConversation() {
  if (sending.value) return
  clearRequestedScope()
  const current = activeConv.value
  if (current.id === idleConversation.value.id) addConversation(current)
  else if (current.serverId || current.messages.length) addConversation(createConversation())
  void nextTick(() => composer.value?.querySelector('textarea')?.focus())
}

/**
 * 切换到指定会话，并按需加载其已完成历史。
 * 接收记录 ID。
 * 由 removeConversation、当前模板的 @click 事件 触发。
 * 下游调用 clearRequestedScope、loadHistory。
 */
function selectConversation(id: string) {
  if (sending.value || id === activeId.value || deletingId.value === id) return
  activeId.value = id
  clearRequestedScope()
  const conv = activeConv.value
  conv.messages = conv.serverId ? [] : conv.messages
  conv.nextBeforeId = null
  void loadHistory(conv)
}

/**
 * 删除指定问答会话并更新当前选中项。
 * 接收记录 ID。
 * 由 当前模板的 @confirm 事件 触发。
 * 下游调用 knowledge 的 deleteRagSession、selectConversation。
 */
async function removeConversation(id: string) {
  const conv = conversations.value.find((item) => item.id === id)
  if (!conv || sending.value || sessionsLoading.value || deletingId.value !== null) return
  if (conv.serverId) {
    deletingId.value = id
    sessionsError.value = ''
    try {
      await deleteRagSession(conv.serverId)
      // 删除已加载的记录后，服务端分页位置随目录一同前移。
      if (sessionOffset.value !== null && sessionOffset.value > 0) sessionOffset.value--
    } catch (error) {
      sessionsError.value = error instanceof Error ? error.message : '删除会话失败。'
      return
    } finally {
      deletingId.value = null
    }
  }

  const index = conversations.value.indexOf(conv)
  conversations.value.splice(index, 1)
  if (activeId.value === id) {
    const next = conversations.value[Math.min(index, conversations.value.length - 1)] ?? idleConversation.value
    selectConversation(next.id)
  }
}

// 总览的快速提问与命令面板会带 query 跳转过来：预填问题，必要时另开空白会话
watch(
  () => route.query,
  (query) => {
    if (typeof query.new === 'string') newConversation()
    if (!USE_MOCK && !sending.value && typeof query.knowledgeBaseId === 'string' && /^\d+$/.test(query.knowledgeBaseId)) {
      if (activeConv.value.serverId || activeConv.value.messages.length) newConversation()
      requestedScopeId.value = Number(query.knowledgeBaseId)
      requestedScopeUnavailable.value = false
      applyRequestedScope()
    }
    if (typeof query.q === 'string' && query.q.trim()) {
      if (activeConv.value.serverId || activeConv.value.messages.length > 0) newConversation()
      draft.value = query.q
    }
  },
  { immediate: true },
)

// ---------- 打字机（仅回退路径） ----------
// 问答已改为 SSE 流式（`POST /knowledge/ask/stream`，见 api/knowledge.ts::askKnowledgeStream），
// 正文由 delta 帧逐字写入，不再需要前端模拟。
// 这里保留打字机只服务两种回退情形：① `pipelineEngine=langchain`（流式仅支持 fixed）；
// ② 流式未产出任何正文就以 done 结束。两条路径都只影响前端渲染，不改变后端契约。
const streams = new Map<string, AbortController>()
const typers = new Map<string, ReturnType<typeof setInterval>>()

/** 取消指定消息的逐步显示定时器。接收消息 ID。由 typeInto 触发。 */
function clearTyper(messageId: string) {
  const timer = typers.get(messageId)
  if (timer) {
    clearInterval(timer)
    typers.delete(messageId)
  }
}

/**
 * 在指定会话的消息位置逐步显示已经收到的完整正文。
 * 接收对话 ID、消息 ID、fullText。
 * 由 send 触发。
 * 下游调用 clearTyper。
 */
function typeInto(conversationId: string, messageId: string, fullText: string) {
  clearTyper(messageId)
  let cursor = 0
  const timer = setInterval(() => {
    const conv = conversations.value.find((item) => item.id === conversationId)
    const message = conv?.messages.find((item) => item.id === messageId)
    if (!message) {
      clearTyper(messageId)
      return
    }
    cursor += 3
    message.typing = true
    message.content = fullText.slice(0, cursor)
    if (cursor >= fullText.length) {
      message.content = fullText
      message.typing = false
      clearTyper(messageId)
    }
  }, 16)
  typers.set(messageId, timer)
}

/** 统一中止当前问答流和正文展示计时。由 onPageHide、stop 触发。 */
function cancelStreams() {
  for (const controller of streams.values()) controller.abort()
}

/** 页面离开时停止进行中的问答或正文显示。下游调用 cancelStreams。 */
function onPageHide() {
  // 刷新、关闭标签页或离开当前站点时，尽早断开 RAG 请求。
  cancelStreams()
}

onMounted(() => {
  window.addEventListener('pagehide', onPageHide)
  if (!USE_MOCK) {
    void loadKnowledgeBases()
    void loadSessions()
  }
})
onBeforeRouteLeave(() => {
  // 页面离开时中止客户端请求。服务端停止执行由流式接口处理。
  cancelStreams()
})
onUnmounted(() => {
  window.removeEventListener('pagehide', onPageHide)
  for (const id of typers.keys()) clearTyper(id)
  // 组件卸载兜底取消所有在途请求，不把未完成结果当成已完成回答。
  cancelStreams()
  streams.clear()
})

// ---------- 发送 ----------
/**
 * 从请求错误对象中读取用于展示的指定诊断字段。
 * 接收失败原因、key，返回文本。
 * 由 send 触发。
 * 下游调用 ragOutcome 的 safeRagReasonCode。
 */
function readErrorField(cause: unknown, key: 'outcome' | 'reasonCode' | 'failedStage' | 'requestId'): string {
  if (typeof cause !== 'object' || cause === null || !(key in cause)) return ''

  const value = (cause as Record<string, unknown>)[key]
  if (typeof value !== 'string') return ''
  if (key === 'failedStage') return /^[a-zA-Z0-9_.-]{1,100}$/.test(value) ? value : ''
  if (key === 'outcome' || key === 'reasonCode') return safeRagReasonCode(value)
  return value
}

/**
 * 按业务终态更新消息正文、获准来源及失败提示。
 * 接收ai、处理结果、content。
 * 由 loadHistory、send 触发。
 * 下游调用 ragOutcome 的 presentRagResult、ragOutcome 的 safeRagReasonCode。
 */
function showResult(ai: ChatMessage, result: KnowledgeAskResponseDTO, content = '') {
  const presentation = presentRagResult(result)
  ai.content = presentation.showAnswer ? result.answer || content || '没有可展示的回答。' : ''
  ai.sources = result.sources ?? []
  ai.showSources = presentation.showSources
  ai.exec = { engine: result.pipelineEngine, version: result.retrievalVersion, evidenceMode: result.evidenceMode }
  if (presentation.message) {
    const theme = { success: 'success', notice: 'info', warning: 'warning', error: 'error' } as const
    ai.notice = {
      theme: theme[presentation.kind], text: presentation.message,
      reasonCode: safeRagReasonCode(result.reasonCode) || undefined, requestId: result.requestId ?? undefined,
    }
  }
  ai.status = 'done'
}

/** 固定当前范围并建立会话，发起问答并整理终态或取消状态。由 onTextareaKeydown、当前模板的 @click 事件 触发。下游调用 addConversation、uid。 */
async function send() {
  const conv = activeConv.value
  const question = draft.value.trim()
  if (!question || sending.value || !canSend.value || deletingId.value === conv.id) return
  if (conv.id === idleConversation.value.id) addConversation(conv)

  const engine = conv.engine
  const strategy = conv.strategy === 'default' ? undefined : conv.strategy
  const knowledgeBaseId = conv.knowledgeBaseId

  conv.messages.push({ id: uid(), role: 'user', content: question, status: 'done' })
  if (conv.title === DEFAULT_TITLE) conv.title = question.slice(0, 18)

  const aiId = uid()
  conv.messages.push({ id: aiId, role: 'assistant', content: '', status: 'pending' })
  draft.value = ''
  pendingMode.value = engine
  const controller = new AbortController()
  streams.set(aiId, controller)
  let streamed = ''

  try {
    await nextTick()
    scrollToBottom()
    if (!USE_MOCK && !conv.serverId) {
      const session = await createRagSession(knowledgeBaseId)
      conv.serverId = session.id
      const duplicate = conversations.value.findIndex((item) => item !== conv && item.serverId === session.id)
      if (duplicate !== -1) conversations.value.splice(duplicate, 1)
    }
    controller.signal.throwIfAborted()
    // 使用发送时固定的范围和会话 ID，使回放历史能对应这次问答。
    const result = await askKnowledgeStream(
          question,
          engine,
          {
            /** 把上下文输入组织进度写入正在生成的消息。接收阶段数据。由 send 触发。 */
            onContext: (data) => {
              const target = conv.messages.find((item) => item.id === aiId)
              if (!target) return
              target.progressText = data.status === 'started' ? '正在整理上下文…' : '上下文已整理，正在继续处理…'
              target.contextNote = data.status === 'completed'
                ? `已整理上下文：单次输入估算 ${data.inputTokensBefore} → ${data.inputTokensAfter} Token，可用 ${data.inputBudget}。`
                : '本次单次输入超出容量，已触发上下文整理。'
            },

            /** 把本次问答的正文增量追加到匹配的消息。接收文本。由 send 触发。 */
            onDelta: (text: string) => {
              const target = conv.messages.find((item) => item.id === aiId)
              if (!target) return
              streamed += text
              target.content = streamed
            },
          },
          controller.signal,
          strategy,
          conv.serverId,
        )
    const ai = conv.messages.find((item) => item.id === aiId)
    if (!ai) return
    showResult(ai, result, streamed)
    if (!streamed && ai.content) {
      const answer = ai.content
      ai.content = ''
      typeInto(conv.id, aiId, answer)
    }
  } catch (cause) {
    const ai = conv.messages.find((item) => item.id === aiId)
    if (controller.signal.aborted) {
      if (ai) {
        ai.status = 'done'
        ai.typing = false
        const stoppedMessage = streamed ? '本轮已停止，当前回答不完整。' : '本轮问答已停止，未生成完整回答。'
        ai.notice = {
          theme: 'info',
          text: engine === 'fixed' ? stoppedMessage : '已结束等待，本轮结果未返回。',
        }
      }
    } else if (ai) {
      ai.status = 'done'
      const failureMessage = cause instanceof Error ? cause.message : '提问失败，请稍后重试。'
      ai.error = {
        message: streamed
          ? `${failureMessage} 已收到的部分内容不完整，不能作为完整回答。`
          : failureMessage,
        outcome: readErrorField(cause, 'outcome'),
        reasonCode: readErrorField(cause, 'reasonCode'),
        failedStage: readErrorField(cause, 'failedStage'),
        requestId: readErrorField(cause, 'requestId'),
      }
    }
  } finally {
    streams.delete(aiId)
    pendingMode.value = null
    await nextTick()
    scrollToBottom()
  }
}

/** 中止当前页面发起的问答并标记停止状态。由 当前模板的 @click 事件 触发。下游调用 cancelStreams。 */
function stop() {
  cancelStreams()
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

// ---------- 来源展示辅助 ----------
/** 优先用来源的可读标题，缺失时使用位置或序号生成标题。接收来源记录、列表索引，返回文本。 */
function sourceTitle(source: KnowledgeSourceDTO, index: number): string {
  return source.headingPath
    ?? (source.pageNo != null ? `第 ${source.pageNo} 页` : `来源片段 ${index + 1}`)
}

// ---------- 自动滚动到底部 ----------
const scrollEl = ref<HTMLElement | null>(null)

/** 将当前消息容器定位到最新消息。由 send 触发。 */
function scrollToBottom() {
  const el = scrollEl.value
  if (el) el.scrollTop = el.scrollHeight
}

watch(() => activeConv.value.id, async () => {
  await nextTick()
  scrollToBottom()
})
// 消息新增、状态变化、打字机增量都会改变下面这个拼接串
watch(
  () => activeConv.value.messages.map((m) => `${m.status}:${m.content}`).join('|'),
  async () => {
    await nextTick()
    scrollToBottom()
  },
)
</script>

<template>
  <section class="chat-page">
    <aside class="conv-sidebar">
      <t-button class="new-conv-btn" theme="primary" variant="outline" block :disabled="sending" @click="newConversation">
        ＋ 新建会话
      </t-button>
      <div class="conv-history-head"><h2>会话记录</h2></div>
      <p v-if="!conversations.length && !sessionsLoading && !sessionsError" class="conv-empty muted">暂无会话记录</p>
      <ul class="conv-list" aria-label="问答会话记录">
        <li
          v-for="conv in conversations"
          :key="conv.id"
          class="conv-item"
          :class="{ active: conv.id === activeId }"
        >
          <button type="button" class="conv-title conv-open" :title="conv.title"
            :aria-current="conv.id === activeId ? 'true' : undefined" :disabled="sending || deletingId === conv.id"
            @click="selectConversation(conv.id)">{{ conv.title }}</button>
          <div>
            <t-popconfirm content="删除此会话及其全部问答记录？" @confirm="removeConversation(conv.id)">
              <t-button variant="text" size="small" class="conv-delete"
                :aria-label="`删除会话：${conv.title}`" :disabled="sending || sessionsLoading || deletingId !== null">
                删除
              </t-button>
            </t-popconfirm>
          </div>
        </li>
      </ul>
      <p v-if="USE_MOCK" class="conv-foot muted">模拟会话刷新后清空</p>
      <p v-if="sessionsError" class="conv-error" role="alert">{{ sessionsError }}</p>
      <t-button v-if="!USE_MOCK && sessionOffset !== null" size="small" variant="text"
        :loading="sessionsLoading" :disabled="deletingId !== null" @click="loadSessions">
        {{ sessionsError && sessionOffset === 0 ? '重新读取会话' : '更多会话' }}
      </t-button>
    </aside>

    <!-- 中间：聊天区 -->
    <div class="chat-main">
      <header class="page-head">
        <div>
          <h1>智能问答</h1>
          <p>选择提问范围，查看回答与原文引用。</p>
        </div>
        <div class="page-head-actions">
          <t-tag v-if="USE_MOCK" theme="warning" variant="light-outline">开发模拟数据</t-tag>
        </div>
      </header>

      <section v-if="!USE_MOCK" class="ask-scope" aria-label="问答范围">
        <div class="ask-scope-row">
          <label v-if="!activeConv.serverId" for="ask-knowledge-base" class="setting-label">问答范围</label>
          <span v-else class="setting-label">问答范围</span>
          <select v-if="!activeConv.serverId" id="ask-knowledge-base" v-model="activeConv.knowledgeBaseId"
            :disabled="sending || loadingKnowledgeBases" @change="clearRequestedScope">
            <option :value="null">全部可读知识库 · 单轮</option>
            <option v-for="library in knowledgeBases" :key="library.id" :value="library.id">{{ library.name }} · 可追问</option>
          </select>
          <strong v-else class="ask-scope-value">{{ scopeLabel }}</strong>
          <t-button v-if="activeConv.serverId" size="small" variant="text" :disabled="sending" @click="newConversation">
            新建会话换库
          </t-button>
          <span v-else-if="loadingKnowledgeBases" class="muted" role="status">正在读取知识库…</span>
        </div>
        <div v-if="knowledgeBaseListFailed || requestedScopeUnavailable" class="ask-scope-feedback" role="status">
          <span>{{ knowledgeBaseListFailed ? '无法读取知识库列表。' : '所选知识库已不可访问，请重新选择范围。' }}</span>
          <t-button v-if="knowledgeBaseListFailed" size="small" variant="text" @click="loadKnowledgeBases">重试读取</t-button>
          <t-button v-if="requestedScopeUnavailable || (knowledgeBaseListFailed && requestedScopeId !== null)"
            size="small" variant="text" @click="useAllReadableLibraries">改用全部可读知识库</t-button>
        </div>
      </section>

      <div ref="scrollEl" class="messages">
        <div v-if="!activeConv.available" class="chat-empty" role="status">
          <p>知识库访问权限已变更</p>
          <span>可以从左侧删除此会话。</span>
        </div>
        <div v-else-if="activeConv.historyLoading && !activeConv.messages.length" class="chat-empty" role="status">
          正在读取问答历史…
        </div>
        <div v-else-if="activeConv.historyError" class="chat-empty">
          <p role="alert">{{ activeConv.historyError }}</p>
          <t-button size="small" variant="outline" @click="loadHistory(activeConv)">重试</t-button>
        </div>
        <div v-else-if="activeConv.messages.length === 0" class="chat-empty">
          <div class="welcome-icon" aria-hidden="true"><SearchIcon /></div>
          <p>从一个具体问题开始</p>
          <span>回答和引用的证据片段会显示在这里。</span>
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
        <div v-if="activeConv.nextBeforeId != null" class="older-messages">
          <t-button size="small" variant="text" :loading="activeConv.historyLoading" :disabled="sending"
            @click="loadHistory(activeConv, activeConv.nextBeforeId)">加载更早的问答</t-button>
        </div>

        <template v-for="msg in activeConv.messages" :key="msg.id">
          <!-- 用户消息 -->
          <div v-if="msg.role === 'user'" class="msg-row user">
            <div class="bubble user-bubble">{{ msg.content }}</div>
          </div>

          <!-- AI 消息 -->
          <div v-else class="msg-row assistant">
            <div class="bubble ai-bubble">
              <div v-if="msg.status === 'pending'" class="loading-state" role="status" aria-live="polite">
                <span class="loading-dot" />
                {{ msg.progressText || '正在检索相关文档并整理回答，请稍候…' }}
              </div>

              <t-alert
                v-if="msg.error"
                theme="error"
                class="msg-alert"
                role="alert"
              >
                <div class="error-body">
                  <span>{{ msg.error.message }}</span>
                  <small v-if="msg.error.outcome" class="request-id">处理状态：{{ msg.error.outcome }}</small>
                  <small v-if="msg.error.reasonCode" class="request-id">状态代码：{{ msg.error.reasonCode }}</small>
                  <small v-if="msg.error.failedStage" class="request-id">失败阶段：{{ msg.error.failedStage }}</small>
                  <small v-if="msg.error.requestId" class="request-id">请求编号：{{ msg.error.requestId }}</small>
                </div>
              </t-alert>

              <t-alert
                v-else-if="msg.notice"
                :theme="msg.notice.theme"
                class="msg-alert"
              >
                <div class="error-body">
                  <span>{{ msg.notice.text }}</span>
                  <small v-if="msg.notice.reasonCode" class="request-id">状态代码：{{ msg.notice.reasonCode }}</small>
                  <small v-if="msg.notice.requestId" class="request-id">请求编号：{{ msg.notice.requestId }}</small>
                </div>
              </t-alert>

              <small v-if="msg.contextNote" class="muted">{{ msg.contextNote }}</small>
              <div v-if="msg.status === 'done' && !msg.error && msg.exec" class="exec-summary">
                <span>实际流程 <strong>{{ pipelineEngineLabel(msg.exec.engine) }}</strong></span>
                <span :title="msg.exec.version || undefined">
                  检索策略 <strong>{{ retrievalVersionLabel(msg.exec.version) }}</strong>
                </span>
                <span v-if="msg.exec.evidenceMode">证据模式 <strong>{{ evidenceModeLabel(msg.exec.evidenceMode) }}</strong></span>
              </div>

              <p v-if="msg.content" class="answer-text">
                {{ msg.content }}<span v-if="msg.typing" class="typing-caret" aria-hidden="true" />
              </p>

              <!-- 参考来源：可展开卡片，展开后看原文片段 -->
              <div v-if="msg.showSources" class="sources-block">
                <div class="sources-head">
                  <span>参考来源</span>
                  <span class="source-count">{{ msg.sources?.length ?? 0 }}</span>
                </div>
                <t-collapse
                  v-if="msg.sources?.length"
                  v-model:value="expandedPanels"
                  borderless
                  expand-icon-placement="right"
                  class="source-collapse"
                >
                  <t-collapse-panel
                    v-for="(source, index) in msg.sources"
                    :key="source.chunkId"
                    :value="`${msg.id}-${source.chunkId}`"
                  >
                    <template #header>
                      <div class="source-header">
                        <span class="source-index">{{ String(index + 1).padStart(2, '0') }}</span>
                        <div class="source-titles">
                          <strong>{{ sourceTitle(source, index) }}</strong>
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
                <p v-else class="no-sources">这次回答没有返回可展示的来源片段。</p>
              </div>
            </div>
          </div>
        </template>
      </div>

      <footer ref="composer" class="composer">
        <details class="ask-options">
          <summary>高级选项 <span>{{ pipelineEngineLabel(activeConv.engine) }}<template v-if="!USE_MOCK"> · {{ STRATEGY_LABELS[activeConv.strategy] }}</template></span></summary>
          <div class="ask-options-body">
            <fieldset class="ask-option">
              <legend class="setting-label">问答流程</legend>
              <t-radio-group v-model:value="activeConv.engine" variant="default-filled" size="small" :disabled="sending">
                <t-radio value="fixed">固定流程</t-radio>
                <t-radio value="langchain">LangChain</t-radio>
              </t-radio-group>
            </fieldset>
            <div v-if="!USE_MOCK" class="ask-option">
              <label for="ask-strategy" class="setting-label">问答策略</label>
              <select id="ask-strategy" v-model="activeConv.strategy" :disabled="sending">
                <option v-for="(label, value) in STRATEGY_LABELS" :key="value" :value="value">{{ label }}</option>
              </select>
            </div>
          </div>
        </details>
        <t-textarea
          v-model:value="draft"
          class="composer-textarea"
          :maxlength="MAX_DRAFT"
          :autosize="{ minRows: 2, maxRows: 6 }"
          placeholder="输入你的问题"
          :disabled="!canSend"
          @keydown="onTextareaKeydown"
        />
        <div class="composer-footer">
          <span class="shortcut-hint muted">Enter 发送 · Shift + Enter 换行</span>
          <div class="composer-actions">
            <t-button v-if="pendingMode === 'fixed' && !USE_MOCK" theme="danger" variant="outline" @click="stop">
              停止
            </t-button>
            <t-button
              theme="primary"
              :loading="sending"
              :disabled="sending || !draft.trim() || !canSend || deletingId === activeId"
              @click="send"
            >
              {{ sending ? '正在检索并生成…' : '发送' }}
              <span aria-hidden="true">→</span>
            </t-button>
          </div>
        </div>
      </footer>
    </div>
  </section>
</template>

<style scoped>
.conv-empty { margin: 0; font-size: var(--fs-sm); }

.older-messages {
  text-align: center;
}

.ask-scope {
  margin: 0 var(--space-5);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-inset);
}

.ask-scope-row,
.ask-scope-feedback,
.ask-options-body {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-3);
}

.ask-scope-feedback {
  color: var(--warn);
  font-size: var(--fs-sm);
}

.ask-scope-value { overflow-wrap: anywhere; }

.ask-scope select,
.ask-options select {
  width: min(100%, 18rem);
  padding: var(--space-1) var(--space-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-panel-2);
  color: var(--text);
  font: inherit;
}

.setting-label,
.ask-options summary span {
  color: var(--text-dim);
  font-size: var(--fs-sm);
}

.ask-options summary {
  width: fit-content;
  max-width: 100%;
  cursor: pointer;
  font-size: var(--fs-sm);
}

.ask-options summary span { margin-inline-start: var(--space-2); }
.ask-options-body { align-items: end; margin-top: var(--space-2); }
.ask-option { display: grid; min-width: 0; gap: var(--space-1); margin: 0; padding: 0; border: 0; }
.ask-option legend { padding: 0; margin-bottom: var(--space-1); }

.composer-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

@media (max-width: 640px) {
  .ask-scope { margin-inline: var(--space-3); }
  .ask-scope-row { gap: var(--space-2); }
}
</style>
