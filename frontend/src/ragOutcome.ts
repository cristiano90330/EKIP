/** 归一化 RAG 流式终态，提取可公开证据并生成页面展示状态。 */

import type { KnowledgeAskResponseDTO, KnowledgeSourceDTO, RagOutcome } from './types'

/** Python SSE keeps raw retrievals separately from the final public evidence. */
export interface RagStreamResult extends KnowledgeAskResponseDTO {
  evidenceSources?: KnowledgeSourceDTO[]
}

/**
 * 将流式终态映射到页面 DTO，仅从证据字段恢复成功来源。
 * 接收处理结果，返回带业务终态的问答结果。
 * 由 knowledge.ts 的 readKnowledgeStream、sse.test.ts 的 问答终帧只展示最终获准证据 触发。
 */
export function normalizeRagStreamResult(result: RagStreamResult): KnowledgeAskResponseDTO {
  return { ...result, sources: result.outcome === 'success' ? result.evidenceSources ?? [] : [] }
}

export interface RagOutcomePresentation {
  message: string
  showAnswer: boolean
  showSources: boolean
  kind: 'success' | 'notice' | 'warning' | 'error'
}

const VALID_OUTCOMES = new Set<RagOutcome>([
  'success',
  'no_evidence',
  'dependency_unavailable',
  'timeout',
])

/**
 * 过滤空值以及不适合在页面展示的原因码。
 * 接收code，返回文本。
 * 由 knowledge.ts 的 readKnowledgeStream、AskView.vue 的 readErrorField 触发。
 */
export function safeRagReasonCode(code: string | null | undefined): string {
  return code && code !== 'none' && /^[a-z0-9_]{1,80}$/.test(code) ? code : ''
}

/**
 * 为上下文容量相关的原因码选择面向用户的提示。
 * 接收code，返回文本。
 * 由 agentOutcome.ts 的 agentStopReasonLabel、presentRagResult 触发。
 */
export function contextCapacityMessage(code?: string | null): string {
  if (code === 'support_context_batch_limit_exceeded') {
    return '已尝试整理上下文，但资料超过本次最多 4 批的审查容量。请缩小问题或文档范围，或选择上下文更大的模型。'
  }
  return code && ['model_context_capacity_exceeded', 'model_prompt_budget_exceeded',
    'support_context_capacity_exceeded', 'generation_context_capacity_exceeded',
    'support_context_over_budget'].includes(code)
    ? '已尝试整理上下文，但系统规则、完整问题和必需证据仍超出当前模型的单次输入容量。请缩小问题或文档范围，或选择上下文更大的模型。'
    : ''
}

/**
 * 根据 RAG 业务终态计算页面正文、来源和提示状态。
 * 接收处理结果，返回 RagOutcomePresentation。
 * 由 AskView.vue 的 showResult、sse.test.ts 的 问答 参数表中的扩展名 触发。
 * 下游调用 contextCapacityMessage。
 */
export function presentRagResult(result: KnowledgeAskResponseDTO): RagOutcomePresentation {
  const capacity = contextCapacityMessage(result.reasonCode)
  if (capacity) return { message: capacity, showAnswer: false, showSources: false, kind: 'error' }

  const candidate = result.outcome ?? (result.reasonCode === 'none' ? null : result.reasonCode)
  const outcome = candidate && VALID_OUTCOMES.has(candidate as RagOutcome) ? candidate as RagOutcome : null
  const hasQualifiedSources = (result.sources?.length ?? 0) > 0
  const reasonCode = result.reasonCode === 'none' ? null : result.reasonCode
  // partial 以 retrievalStatus 为准：它是检索阶段的原始结论；旧响应没有该字段时回落到 reasonCode。
  const partialRetrieval = result.retrievalStatus
    ? result.retrievalStatus === 'partial'
    : reasonCode === 'partial_retrieval'

  // 先按业务终态决定是否公开正文和来源，再兼容未带显式终态的历史结果。
  if (outcome === 'success') {
    return partialRetrieval
      ? {
          message: '部分检索能力暂时不可用；答案基于当前仍可用且通过验证的来源。',
          showAnswer: true,
          showSources: true,
          kind: 'warning',
        }
      : { message: '', showAnswer: true, showSources: true, kind: 'success' }
  }
  if (outcome === 'no_evidence' || (candidate == null && !hasQualifiedSources)) {
    return {
      message: reasonCode === 'threshold_not_configured' || reasonCode === 'scorer_unavailable'
        ? '当前无法验证检索内容是否足以回答，因此没有生成答案。请稍后重试，或联系管理员检查证据验证能力。'
        : reasonCode === 'invalid_score_mapping'
          ? '检索评分无法与来源对应，系统没有把这些内容作为可信依据。请稍后重试或联系管理员。'
          : reasonCode === 'invalid_score' || reasonCode === 'below_threshold'
            ? '检索到的内容没有达到回答所需的证据标准。可以换一种问法，或补充更直接的资料。'
            : '没有找到足以回答问题的资料。可以换一种问法，或先在知识库中补充相关文档。',
      showAnswer: false,
      showSources: false,
      kind: reasonCode === 'threshold_not_configured' || reasonCode === 'scorer_unavailable'
        || reasonCode === 'invalid_score_mapping' || reasonCode === 'invalid_score' ? 'warning' : 'notice',
    }
  }
  if (outcome === 'dependency_unavailable') {
    return hasQualifiedSources
      ? {
          message: '已找到可核验的资料，但生成服务暂时不可用。你可以先查看下方来源，稍后再试。',
          showAnswer: false,
          showSources: true,
          kind: 'warning',
        }
      : {
          message: partialRetrieval
            ? '部分检索能力暂时不可用，未能取得可核验的资料。请稍后重试，或缩短问题范围。'
            : reasonCode === 'threshold_not_configured'
              ? '证据验证门槛尚未配置，系统没有把检索候选作为可信来源。请稍后重试或联系管理员。'
              : reasonCode === 'scorer_unavailable'
                ? '证据验证能力暂时不可用，系统没有把检索候选作为可信来源。请稍后重试。'
                : reasonCode === 'invalid_score_mapping'
                  ? '检索评分无法与来源对应，系统没有把这些内容作为可信来源。请稍后重试。'
                  : reasonCode === 'invalid_score'
                    ? '检索评分无效，系统没有把这些内容作为可信来源。请稍后重试。'
                    : '知识检索服务暂时不可用，不能判断资料是否存在。请稍后重试。',
          showAnswer: false,
          showSources: false,
          kind: 'error',
        }
  }
  if (outcome === 'timeout') {
    return hasQualifiedSources
      ? {
          message: '已找到可核验的资料，但生成回答超过等待时间。你可以先查看下方来源，稍后再试。',
          showAnswer: false,
          showSources: true,
          kind: 'warning',
        }
      : {
          message: '本次问答超过等待时间，未能取得可用资料。请稍后重试，或缩短问题范围。',
          showAnswer: false,
          showSources: false,
          kind: 'warning',
        }
  }

  // Compatibility with the old ask DTO while the back end rolls out the shared contract.
  if (candidate == null && hasQualifiedSources) {
    return { message: '', showAnswer: true, showSources: true, kind: 'success' }
  }
  return {
    message: '问答状态暂时无法判定。请稍后重试；如果持续发生，请联系管理员。',
    showAnswer: false,
    showSources: false,
    kind: 'warning',
  }
}
