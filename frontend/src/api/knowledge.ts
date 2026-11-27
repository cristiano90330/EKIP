/** 维护 RAG 请求、持久会话与 SSE 帧处理，并统一浏览器期限和失败类型。 */

import axios from 'axios'
import { USE_MOCK } from '../config'
import type { KnowledgeAskResponseDTO, PipelineEngine, RagOutcomeResponseDTO, RagStrategy } from '../types'
import { http, raw } from './http'
import { mockKnowledgeAsk } from './mock'
import { parseJsonFrame, readSseFrames } from './sse'
import { contextCapacityMessage, normalizeRagStreamResult, safeRagReasonCode, type RagStreamResult } from '../ragOutcome'
import { useAuth } from '../stores/auth'

const RAG_CLIENT_TIMEOUT_MS = 300_000
const RAG_CANCEL_WAIT_MS = 2_000
const RAG_TIMEOUT_MESSAGE = '本次问答超过等待时间。请稍后重试；如果持续发生，请缩短问题或联系管理员。'
const EVIDENCE_THRESHOLD_MESSAGE = '证据验证门槛尚未配置，系统没有生成答案。请稍后重试或联系管理员。'
const STRATEGY_MISMATCH_MESSAGE = '服务未确认本次问答策略，请稍后重试。'

interface RagFailurePayload {
  outcome?: string
  reasonCode?: string
  failedStage?: string
  requestId?: string
}

interface ApiErrorBody extends RagFailurePayload {
  data?: RagFailurePayload
}

export class RagRequestError extends Error {
  /**
   * 保存 RAG 异常的用户文案、终态、原因码、阶段和请求标识。
   * 接收待显示的消息、业务终态、原因码、failedStage、requestId。
   * 下游调用 function Object() { [native code] }。
   */
  constructor(
    message: string,
    readonly outcome?: string,
    readonly reasonCode?: string,
    readonly failedStage?: string,
    readonly requestId?: string,
  ) {
    super(message)
    this.name = 'RagRequestError'
  }
}

/** 从 SSE 建连失败的 HTTP 响应中恢复可展示的 RAG 错误。接收HTTP 响应，返回 RagRequestError。由 readKnowledgeStream 触发。 */
function streamPreflightError(response: Response): RagRequestError {
  const status = response.status
  const rawRequestId = response.headers.get('X-Request-ID') ?? ''
  const requestId = /^[a-zA-Z0-9_-]{1,100}$/.test(rawRequestId) ? rawRequestId : undefined

  if (status === 401) {
    return new RagRequestError(
      '登录状态已失效，请重新登录后继续。', undefined, 'authentication_required',
      'java.stream_preflight', requestId,
    )
  }
  if (status === 403) {
    return new RagRequestError(
      '当前账号无权使用问答服务或访问相关知识库。', undefined, 'forbidden',
      'java.stream_preflight', requestId,
    )
  }
  if (status === 408 || status === 504) {
    return new RagRequestError(
      '本次问答超过等待时间。请稍后重试。', 'timeout', 'query_deadline_exceeded',
      'java.stream_preflight', requestId,
    )
  }
  if (status === 429) {
    return new RagRequestError(
      '问答请求过多，请稍后重试。', 'dependency_unavailable', 'request_throttled',
      'java.stream_preflight', requestId,
    )
  }
  if (status === 502 || status === 503) {
    return new RagRequestError(
      '问答服务当前繁忙或依赖暂不可用，请稍后重试。', 'dependency_unavailable',
      'stream_service_unavailable', 'java.stream_preflight', requestId,
    )
  }
  if (status === 400 || status === 422) {
    return new RagRequestError(
      '问答请求未被接受。请检查问题内容后重试。', undefined, 'invalid_request',
      'java.stream_preflight', requestId,
    )
  }
  return new RagRequestError(
    '问答服务暂时无法完成请求。请稍后重试。', undefined, undefined,
    'java.stream_preflight', requestId,
  )
}

/** 将传输失败或服务端原因转换成稳定的问答错误。接收错误对象，返回 RagRequestError。由 requestRagAnswer 触发。 */
function safeAskError(error: unknown): RagRequestError {
  if (!axios.isAxiosError<ApiErrorBody>(error)) {
    return new RagRequestError('问答请求未能完成，请稍后重试。')
  }

  const body = error.response?.data
  const rawOutcome = body?.outcome ?? body?.data?.outcome
  const outcome = typeof rawOutcome === 'string' && /^[a-z0-9_]{1,80}$/.test(rawOutcome) ? rawOutcome : undefined
  const rawReasonCode = body?.reasonCode ?? body?.data?.reasonCode
  const reasonCode = typeof rawReasonCode === 'string' && /^[a-z0-9_]{1,80}$/.test(rawReasonCode) ? rawReasonCode : undefined
  const rawFailedStage = body?.failedStage ?? body?.data?.failedStage
  const failedStage = typeof rawFailedStage === 'string' && /^[a-zA-Z0-9_.-]{1,100}$/.test(rawFailedStage) ? rawFailedStage : undefined
  const rawRequestId = body?.requestId ?? body?.data?.requestId
  const requestId = typeof rawRequestId === 'string' && /^[a-zA-Z0-9_-]{1,100}$/.test(rawRequestId) ? rawRequestId : undefined
  const status = error.response?.status

  let message: string

  if (reasonCode === 'query_deadline_exceeded' || status === 504 || error.code === 'ECONNABORTED') {
    return new RagRequestError(RAG_TIMEOUT_MESSAGE, 'timeout', reasonCode ?? 'query_deadline_exceeded', failedStage, requestId)
  } else if (reasonCode === 'threshold_not_configured') {
    message = EVIDENCE_THRESHOLD_MESSAGE
  } else if (reasonCode === 'scorer_unavailable') {
    message = '证据验证能力暂时不可用，系统没有生成答案。请稍后重试。'
  } else if (reasonCode === 'invalid_score_mapping') {
    message = '检索评分无法与来源对应，系统没有生成答案。请稍后重试或联系管理员。'
  } else if (reasonCode === 'ai_contract_mismatch') {
    message = STRATEGY_MISMATCH_MESSAGE
  } else if (reasonCode === 'retrieval_unavailable' || status === 503 || status === 502) {
    message = reasonCode === 'retrieval_unavailable'
      ? '知识检索服务暂时不可用。请稍后重试。'
      : '问答依赖暂时不可用，无法可靠完成本次请求。请稍后重试。'
  } else if (status === 403) {
    message = '当前账号无权访问所需知识库。请联系知识库管理员。'
  } else if (status === 400 || status === 422) {
    message = '问答请求未被接受。请检查问题内容后重试。'
  } else if (!error.response) {
    message = '无法连接问答服务。请检查网络后重试。'
  } else {
    message = '问答服务暂时无法完成请求。请稍后重试。'
  }

  return new RagRequestError(message, outcome, reasonCode, failedStage, requestId)
}

/**
 * 使等待承诺受取消信号控制，并在完成后释放监听器。
 * 接收request、用于中止等待的信号，返回 T。
 * 由 waitForRequestOrAbort、withRagDeadline 触发。
 * 下游调用 cleanup、onAbort。
 */
function waitWithAbortSignal<T>(request: Promise<T>, abortSignal: AbortSignal): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    /** 解除本次等待注册的取消监听器。由 waitWithAbortSignal 触发。 */
    function cleanup() {
      abortSignal.removeEventListener('abort', onAbort)
    }

    /** 解除监听器并用取消原因结束当前等待。由 waitWithAbortSignal 触发。下游调用 cleanup。 */
    function onAbort() {
      cleanup()
      reject(abortSignal.reason ?? new Error('问答请求已取消。'))
    }
    if (abortSignal.aborted) onAbort()
    else abortSignal.addEventListener('abort', onAbort, { once: true })
    request.then(
      (value) => {
        cleanup()
        resolve(value)
      },
      (error: unknown) => {
        cleanup()
        reject(error)
      },
    )
  })
}

/**
 * 为带取消信号的模拟请求接入可中止等待。
 * 接收request、取消信号，返回 T。
 * 由 askKnowledge 触发。
 * 下游调用 waitWithAbortSignal。
 */
function waitForRequestOrAbort<T>(request: Promise<T>, signal?: AbortSignal): Promise<T> {
  return signal ? waitWithAbortSignal(request, signal) : request
}

/**
 * 将用户取消和浏览器问答期限汇合为一次请求的取消信号。
 * 接收request、取消信号，返回 T。
 * 由 requestRagAnswer、askKnowledgeStream 触发。
 * 下游调用 waitWithAbortSignal。
 */
async function withRagDeadline<T>(request: (signal: AbortSignal) => Promise<T>, signal?: AbortSignal): Promise<T> {
  if (signal?.aborted) throw signal.reason

  const controller = new AbortController()

  /** 将上游取消原因转发给本次问答的控制器。由 withRagDeadline 触发。 */
  const cancel = () => controller.abort(signal?.reason)
  signal?.addEventListener('abort', cancel, { once: true })
  // 用同一截止时间覆盖建连和响应体读取，用户主动停止也进入同一个取消入口。
  const timer = setTimeout(() => controller.abort(new RagRequestError(
    RAG_TIMEOUT_MESSAGE, 'timeout', 'query_deadline_exceeded', 'browser.transport',
  )), RAG_CLIENT_TIMEOUT_MS)

  try {
    // Include request interceptors and response body reads in the same limit.
    return await waitWithAbortSignal(request(controller.signal), controller.signal)
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', cancel)
  }
}

/**
 * 发送单轮 RAG 请求并得到完整问答结果。
 * 接收问题正文、编排引擎、取消信号、RAG 策略，返回带业务终态的问答结果。
 * 由 askKnowledgeStream 触发。
 * 下游调用 KnowledgeChatController 的单轮入口、waitForRequestOrAbort。
 */
export async function askKnowledge(
  question: string,
  pipelineEngine: PipelineEngine = 'fixed',
  signal?: AbortSignal,
  strategy?: RagStrategy,
): Promise<KnowledgeAskResponseDTO> {
  if (USE_MOCK) {
    if (signal?.aborted) throw new Error('问答请求已取消。')
    return waitForRequestOrAbort(mockKnowledgeAsk(question, pipelineEngine), signal)
  }

  return requestRagAnswer('/knowledge/ask', { question, pipelineEngine, strategy }, signal)
}

/**
 * 在指定知识库和会话标识下提交同步问答。
 * 接收知识库 ID、对话 ID、问题正文、编排引擎、取消信号、RAG 策略，返回带业务终态的问答结果。
 * 下游调用 RagController 的会话问答入口、requestRagAnswer。
 */
export function chatWithKnowledgeBase(
  knowledgeBaseId: number,
  conversationId: string,
  question: string,
  pipelineEngine: PipelineEngine,
  signal?: AbortSignal,
  strategy?: RagStrategy,
): Promise<RagOutcomeResponseDTO> {
  return requestRagAnswer('/rag/chat', { knowledgeBaseId, conversationId, question, pipelineEngine, strategy }, signal)
}

export interface RagSession {
  id: string
  title: string
  knowledgeBaseId: number | null
  createdAt: number
  available: boolean
}

interface SessionPage { items: RagSession[]; nextOffset: number | null }
interface HistoryPage {
  items: { id: number; question: string; response: RagOutcomeResponseDTO; createdAt: number }[]
  nextBeforeId: number | null
}

const sessionsPath = '/rag/sessions'

/**
 * 在单库范围或全部可读库范围下创建 RAG 会话。
 * 接收知识库 ID，返回问答会话记录。
 * 由 AskView.vue 的 send 触发。
 * 下游调用 RagSessionController 的会话创建入口。
 */
export const createRagSession = (knowledgeBaseId: number | null, signal?: AbortSignal): Promise<RagSession> =>
  http.post<RagSession, RagSession>(sessionsPath, { knowledgeBaseId }, { signal })

/**
 * 按偏移量读取当前账号的问答会话目录。
 * 接收查询偏移量，返回 SessionPage。
 * 由 AskView.vue 的 loadSessions 触发。
 * 下游调用 RagSessionController 的会话列表入口。
 */
export const listRagSessions = (offset = 0): Promise<SessionPage> =>
  http.get<SessionPage, SessionPage>(sessionsPath, { params: { offset } })

/**
 * 按消息游标加载指定问答会话的完成记录。
 * 接收会话 ID、历史页游标，返回 HistoryPage。
 * 由 AskView.vue 的 loadHistory 触发。
 * 下游调用 RagSessionController 的历史入口。
 */
export const getRagSessionHistory = (id: string, beforeId?: number): Promise<HistoryPage> =>
  http.get<HistoryPage, HistoryPage>(`${sessionsPath}/${encodeURIComponent(id)}/history`, { params: { beforeId } })

/**
 * 删除指定 RAG 会话及其消息。
 * 接收会话 ID，返回文本。
 * 由 AskView.vue 的 removeConversation 触发。
 * 下游调用 RagSessionController 的会话删除入口。
 */
export const deleteRagSession = (id: string): Promise<string> =>
  http.delete<string, string>(`${sessionsPath}/${encodeURIComponent(id)}`)

/**
 * 请求同步问答正文，统一传输中止和业务失败的页面表示。
 * 接收path、body、取消信号，返回带业务终态的问答结果。
 * 由 askKnowledge、chatWithKnowledgeBase 触发。
 * 下游调用 withRagDeadline、safeAskError。
 */
async function requestRagAnswer(
  path: string,
  body: { question: string; pipelineEngine: PipelineEngine; knowledgeBaseId?: number; conversationId?: string; strategy?: RagStrategy },
  signal?: AbortSignal,
): Promise<RagOutcomeResponseDTO> {
  try {
    const response = await withRagDeadline(requestSignal => raw.post<RagOutcomeResponseDTO>(
      path,
      body,
      { timeout: RAG_CLIENT_TIMEOUT_MS, signal: requestSignal },
    ), signal)
    return response.data
  } catch (error) {
    if (error instanceof RagRequestError) throw error
    throw safeAskError(error)
  }
}

export interface AskStreamHandlers {
  onDelta?: (text: string) => void
  onContext?: (data: { status: string; inputTokensBefore: number; inputTokensAfter?: number; inputBudget: number }) => void
}

/**
 * 按会话类型选择 SSE 入口，模拟模式沿用完整结果。
 * 接收问题正文、编排引擎、事件处理回调、取消信号、RAG 策略、会话 ID，返回带业务终态的问答结果。
 * 由 AskView.vue 的 send 触发。
 * 下游调用 readKnowledgeStream、askKnowledge。
 */
export async function askKnowledgeStream(
  question: string,
  pipelineEngine: PipelineEngine = 'fixed',
  handlers: AskStreamHandlers = {},
  signal?: AbortSignal,
  strategy?: RagStrategy,
  sessionId?: string,
): Promise<KnowledgeAskResponseDTO> {
  if (USE_MOCK) return askKnowledge(question, pipelineEngine, signal, strategy)

  if (!sessionId || !signal) {
    return withRagDeadline(
      requestSignal => readKnowledgeStream(question, pipelineEngine, handlers, requestSignal, strategy, sessionId),
      signal,
    )
  }

  signal.throwIfAborted()
  const transport = new AbortController()
  let turnId: string | null = null
  let stopping: Promise<void> | undefined
  let handshakeTimer: ReturnType<typeof setTimeout> | undefined

  // 保留响应头握手，拿到本轮标识后再通知服务端停机，避免同会话抢跑。
  const stop = () => {
    if (stopping) return
    if (!turnId) {
      handshakeTimer = setTimeout(() => transport.abort(signal.reason), RAG_CANCEL_WAIT_MS)
      return
    }
    clearTimeout(handshakeTimer)
    const url = `/api/knowledge/sessions/${encodeURIComponent(sessionId)}/turns/${encodeURIComponent(turnId)}/cancel`
    stopping = fetch(url, {
      method: 'POST',
      headers: { Authorization: `Bearer ${useAuth().token}` },
      keepalive: true,
      signal: AbortSignal.timeout(RAG_CANCEL_WAIT_MS),
    }).then((response) => {
      if (!response.ok) throw new Error('取消未得到服务端确认')
    }).catch(async () => {
      transport.abort(signal.reason)
      // 控制请求失败时，给 SSE 心跳留出发现断连的时间。
      await new Promise<void>((resolve) => setTimeout(resolve, 1_000))
    }).finally(() => transport.abort(signal.reason))
  }

  signal.addEventListener('abort', stop, { once: true })
  try {
    const result = await withRagDeadline(requestSignal =>
      readKnowledgeStream(question, pipelineEngine, handlers, requestSignal, strategy, sessionId, (id) => {
        turnId = id
        if (signal.aborted && turnId) stop()
      }), transport.signal)
    signal.throwIfAborted()
    return result
  } finally {
    signal.removeEventListener('abort', stop)
    clearTimeout(handshakeTimer)
    await stopping
  }
}

/**
 * 读取 RAG 上下文、正文和终态帧，将成功终态的获准证据映射为页面来源。
 * 接收问题正文、编排引擎、事件处理回调、取消信号、RAG 策略、会话 ID，返回最终问答结果。
 * 由 askKnowledgeStream 触发。
 * 下游调用 KnowledgeChatStreamController 的单轮或服务端会话入口、auth 的 useAuth。
 */
async function readKnowledgeStream(
  question: string,
  pipelineEngine: PipelineEngine,
  handlers: AskStreamHandlers,
  signal: AbortSignal,
  strategy?: RagStrategy,
  sessionId?: string,
  onTurn?: (id: string | null) => void,
): Promise<KnowledgeAskResponseDTO> {
  const url = sessionId ? `/api/knowledge/sessions/${encodeURIComponent(sessionId)}/ask/stream` : '/api/knowledge/ask/stream'
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      Authorization: `Bearer ${useAuth().token}`,
    },
    body: JSON.stringify({ question, pipelineEngine, strategy }),
    signal,
  })

  if (!response.ok) {
    throw streamPreflightError(response)
  }
  onTurn?.(response.headers.get('X-Rag-Turn-Id'))

  let done: KnowledgeAskResponseDTO | null = null
  let streamError: RagRequestError | null = null
  await readSseFrames(response, (frame) => {
    const payload = parseJsonFrame<Record<string, unknown>>(frame)
    if (payload === null) return
    if (frame.event === 'context') {
      handlers.onContext?.(payload as unknown as Parameters<NonNullable<AskStreamHandlers['onContext']>>[0])
      return
    }
    if (frame.event === 'delta') {
      const text = typeof payload.text === 'string' ? payload.text : ''
      if (text) handlers.onDelta?.(text)
      return
    }
    if (frame.event === 'done') {
      done = normalizeRagStreamResult(payload as unknown as RagStreamResult)
      return false
    }
    if (frame.event === 'error') {
      const outcome = typeof payload.outcome === 'string' ? payload.outcome : undefined
      const reasonCode = safeRagReasonCode(
        typeof payload.reasonCode === 'string' ? payload.reasonCode : undefined,
      )
      const reasonMessage = reasonCode === 'threshold_not_configured' ? EVIDENCE_THRESHOLD_MESSAGE
        : reasonCode === 'ai_contract_mismatch' ? STRATEGY_MISMATCH_MESSAGE : contextCapacityMessage(reasonCode)
      streamError = new RagRequestError(
        reasonMessage || (outcome === 'timeout'
          ? RAG_TIMEOUT_MESSAGE
          : '问答依赖暂时不可用，无法可靠完成本次请求。请稍后重试。'),
        outcome,
        reasonCode || undefined,
        typeof payload.failedStage === 'string' ? payload.failedStage : undefined,
        typeof payload.requestId === 'string' ? payload.requestId : undefined,
      )
      return false
    }
  })

  if (streamError) throw streamError
  if (!done) throw new RagRequestError('问答流意外结束，未能拿到完整结果。请稍后重试。')
  return done
}
