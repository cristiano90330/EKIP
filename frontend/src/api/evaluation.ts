/** 定义检索评测和批量回归的数据契约，并调用对应 Java 接口。 */

import { http } from './http'

export interface RetrievalEvaluation {
  question?: string
  version: string | null
  precisionAtK: number | null
  recallAtK: number | null
  mrr: number | null
  latencyMs: number | null
  retrievalLatencyMs: number | null
  outcome: string | null
  reasonCode: string | null
  requestId: string | null
  answer: string | null
}

export interface EvaluationCase {
  knowledgeBaseId: number
  question: string
  relevantChunkIds: number[]
}

export interface EvaluationMetrics {
  caseCount: number
  recallAtK: number | null
  precisionAtK: number | null
  mrr: number | null
  ndcgAtK?: number | null
  avgLatencyMs?: number | null
}

export interface BatchEvaluation {
  version: string
  k: number
  totalCases: number
  total: EvaluationMetrics
  perCase: RetrievalEvaluation[]
}

export interface EvaluationRegression {
  passed: boolean
  verdict: string
  deltas: { metric: string; label: string; baselineValue: number | null; currentValue: number | null;
    absoluteDelta: number | null; verdict: string; applicable: boolean }[]
}

/**
 * 提交检索问题和相关切片标签，接收批次汇总。
 * 接收cases、取消信号，返回 BatchEvaluation。
 * 由 RetrievalEvaluationPanel.vue 的 runBatch 触发。
 * 下游调用 EvaluationController。
 */
export const evaluateBatch = (cases: EvaluationCase[], signal: AbortSignal): Promise<BatchEvaluation> =>
  http.post<BatchEvaluation, BatchEvaluation>('/evaluation/batch', { k: 5, cases }, { timeout: 300_000, signal })

/**
 * 提交基线和当前批次并取得回归比较。
 * 接收baseline、current、取消信号，返回 EvaluationRegression。
 * 由 RetrievalEvaluationPanel.vue 的 runBatch 触发。
 * 下游调用 EvaluationController。
 */
export const compareEvaluation = (
  baseline: EvaluationMetrics, current: EvaluationMetrics, signal: AbortSignal,
): Promise<EvaluationRegression> =>
  http.post<EvaluationRegression, EvaluationRegression>('/evaluation/regression', {
    baseline, current, threshold: { keyMetrics: ['recallAtK'] },
  }, { signal })

/**
 * 在指定知识库按标注切片评估单条检索问题。
 * 接收知识库 ID、问题正文、relevantChunkIds、取消信号，返回 RetrievalEvaluation。
 * 由 RetrievalEvaluationPanel.vue 的 runEvaluation 触发。
 * 下游调用 EvaluationController。
 */
export const evaluateRetrieval = (
  knowledgeBaseId: number,
  question: string,
  relevantChunkIds: number[],
  signal?: AbortSignal,
): Promise<RetrievalEvaluation> =>
  http.post<RetrievalEvaluation, RetrievalEvaluation>(
    '/evaluation/retrieval', { knowledgeBaseId, question, relevantChunkIds }, { timeout: 300_000, signal },
  )
