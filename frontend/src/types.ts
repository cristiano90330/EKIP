/** 声明认证、文档、RAG、Agent 和运维 API 共用的前端数据契约。 */

// 与后端契约一一对应的类型。字段名严格对齐后端 DTO。

/**
 * 后端统一响应包装。成功：HTTP 2xx + code === 200。
 * 失败：HTTP 状态码语义化（400 校验 / 401 未认证 / 403 越权 / 413 超限 / 422 业务失败 / 500 未知），
 * 且 body.code 与该状态码一致，message 为可直接展示的中文文案。
 */
export interface Result<T> {
  code: number
  message: string
  data: T
}

/** 单条 trace 内的一个阶段 span（RagSpan -> TraceSpanDTO） */
export interface TraceSpanDTO {
  stage: string
  durationMs: number
  totalTokens: number | null
  costUsd: number | null
  inputTokens: number | null
  outputTokens: number | null
  success: boolean
  errorMessage: string | null
}

export type PipelineEngine = 'fixed' | 'langchain'
export type RagStrategy = 'default' | 'light' | 'rerank_support' | 'rerank_score'

/** Actual execution metadata; absent fields on older traces must not imply defaults. */
export interface RagExecutionMetadataDTO {
  requestedPipelineEngine?: string | null
  requestedRetrievalVersion?: string | null
  pipelineEngine?: string | null
  retrievalVersion?: string | null
  providerUsed?: string | null
  evidenceScorer?: string | null
  outcome?: string | null
  reasonCode?: string | null
}

/** 单条 trace 详情（GET /observability/trace/{id}） */
export interface TraceDTO extends RagExecutionMetadataDTO {
  traceId: string
  entrypoint: string
  originalQuery: string
  rewrittenQuery: string
  startTime: number // epoch ms
  totalMs: number
  spans: TraceSpanDTO[]
}

/** trace 列表摘要（GET /observability/trace） */
export interface TraceSummaryDTO extends RagExecutionMetadataDTO {
  traceId: string
  entrypoint: string
  originalQuery: string
  totalMs: number
  spanCount: number
  success: boolean
  startTime: number // epoch ms
}

/** POST /knowledge/ask 的证据片段。 */
export interface KnowledgeSourceDTO {
  /** Agent 工具结果与正文引用共用的标识；普通 RAG 可省略。 */
  citationId?: string | null
  documentId: number
  chunkId: number
  knowledgeBaseId: number | null
  chunkIndex: number
  /** 连续阅读来源在原 chunk 中的 Unicode 字符起点。 */
  contentOffset?: number | null
  content: string
  distance: number | null
  headingPath: string | null
  pageNo: number | null
  sourceType: string | null
}

/** Shared by single-turn ask and multi-turn chat; values are stable API codes. */
export type RagOutcome =
  | 'success'
  | 'no_evidence'
  | 'dependency_unavailable'
  | 'timeout'

export interface RagEvidenceDecisionDTO {
  state: 'accepted' | 'rejected' | 'unverified'
  reasonCode?: string | null
  policyVersion?: string | null
}

/** Shared response contract for POST /knowledge/ask and POST /rag/chat. */
export interface RagOutcomeResponseDTO {
  answer: string
  sources: KnowledgeSourceDTO[]
  outcome?: RagOutcome
  reasonCode?: string | null
  /** 检索完整度：partial 表示某一路召回不可用，答案只基于仍可用的证据（AI-04）。 */
  retrievalStatus?: 'complete' | 'partial' | 'unavailable' | null
  failedStage?: string | null
  requestId?: string | null
  evidenceDecision?: RagEvidenceDecisionDTO | null
  pipelineEngine?: string | null
  retrievalVersion?: string | null
  evidenceMode?: 'score' | 'support' | null
}

export type KnowledgeAskResponseDTO = RagOutcomeResponseDTO
export interface RagChatResponseDTO extends RagOutcomeResponseDTO {
  conversationId?: string
}

/** 知识库列表（GET /knowledge-base/list → Result<KnowledgeBase[]>） */
export interface KnowledgeBaseDTO {
  id: number
  name: string
  description: string | null
  creatorId: number
  createTime?: string
  updateTime?: string
}

/** 文档列表（GET /document/list → Result<IPage<Document>>） */
export interface DocumentDTO {
  id: number
  knowledgeBaseId: number
  fileName: string
  fileType: string | null
  fileSize: number
  status: number
  createTime?: string
}

export interface DocumentPageDTO {
  records: DocumentDTO[]
  current: number
  size: number
  total: number
  pages: number
}

export interface DocumentChunkDTO {
  id: number
  documentId: number
  knowledgeBaseId: number
  chunkIndex: number
  content: string
  charCount: number
  sourceType: string | null
  pageNo: number | null
  headingPath: string | null
  tableId: string | null
  rowIndex: number | null
  parentChunkId: number | null
}

export interface DocumentChunkPageDTO {
  records: DocumentChunkDTO[]
  current: number
  size: number
  total: number
  pages: number
}

/** 文档解析任务（GET /document/{id}/task → Result<DocumentTaskDTO>） */
export interface DocumentTaskDTO {
  documentId: number
  taskId?: number
  status: string
  attempt?: number
  retryCount?: number
  autoRetryCount?: number
  manualRetryCount?: number
  errorMessage: string | null
  errorCode?: string | null
  lastErrorMessage?: string | null
  lastErrorCode?: string | null
  startTime?: string | null
  finishTime?: string | null
}

/** POST /document/upload receipt; upload completion means the backend accepted the document. */
export interface DocumentUploadReceiptDTO {
  requestKey: string
  documentId: number
  taskId: number
  documentStatus: 'UPLOADED' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
  taskStatus: 'WAITING' | 'PROCESSING' | 'SUCCESS' | 'FAILED' | 'CANCELLED'
  reused: boolean
}

export interface UploadReceiptLookupDTO {
  found: boolean
  receipt?: DocumentUploadReceiptDTO | null
}

/** 代码状态/配置快照（GET /observability/status） */
export interface ObservabilityStatusDTO {
  appVersion: string
  branch: string
  commitSha: string
  retrievalVersion: string
  compressionMode: string
  vectorStore: string
  traceRetention: number
}

export type CapabilityStatus = 'ready' | 'degraded' | 'not_ready' | 'unknown'

export interface CapabilityReadinessDTO {
  status: CapabilityStatus
  reasonCode?: string | null
  policyVersion?: string
  scorer?: string
}

/** GET /observability/capabilities; distinct from process liveness at /health. */
export interface CapabilityReadinessResponseDTO {
  status: 'ready' | 'degraded' | 'not_ready'
  checkedAt: string
  capabilities: {
    backend?: CapabilityReadinessDTO
    database?: CapabilityReadinessDTO
    redis?: CapabilityReadinessDTO
    minio?: CapabilityReadinessDTO
    ingest?: CapabilityReadinessDTO
    search?: CapabilityReadinessDTO
    answer?: CapabilityReadinessDTO
    chat?: CapabilityReadinessDTO
    evidenceGate?: CapabilityReadinessDTO
    rerank?: CapabilityReadinessDTO
  }
}

/** 登录/刷新返回（POST /user/login、/user/refresh → Result<LoginVO>） */
export interface LoginVO {
  token: string // 访问令牌（短，默认 15 分钟）
  refreshToken: string // 刷新令牌（长，默认 7 天，服务端可吊销）
  expiresIn: number // 访问令牌有效期（秒）
}

/** /actuator/health 响应（非 Result 包装） */
export interface HealthDTO {
  status: string // UP / DOWN / UNKNOWN
}

/** /actuator/metrics 响应（非 Result 包装） */
export interface MetricsListDTO {
  names: string[]
}

/** /actuator/metrics/{name} 响应（非 Result 包装） */
export interface MetricDetailDTO {
  name: string
  description?: string
  baseUnit?: string
  measurements: { statistic: string; value: number }[]
  availableTags?: { tag: string; values: string[] }[]
}
