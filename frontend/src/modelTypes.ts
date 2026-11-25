/** 定义模型登记表单的数据结构，并在空白草稿、已登记模型与编辑字段之间转换。 */

/** Public model-management contracts. Credentials are never part of a model response. */
export type ModelType = 'chat' | 'embedding' | 'rerank'
export type ModelSource = 'local' | 'remote'
export type ModelProvider = 'generic' | 'openai' | 'aliyun' | 'cohere' | 'ollama'
export type ModelProtocol = 'openai_chat' | 'openai_embedding' | 'cohere_rerank' | 'dashscope_rerank'
export type ModelPurpose = 'generate' | 'rewrite' | 'agent' | 'summarize' | 'judge' | 'evidence_support' | 'embedding' | 'rerank'

export interface ModelCapabilities {
  tools: boolean
  streaming: boolean
  json: boolean
  thinking: boolean
}

export interface ModelRuntime {
  timeoutMs: number
  retries: number
  backoffMs: number
  maxConcurrency: number
  resourceGroup: string
}

export interface ModelDraft {
  name: string
  type: ModelType
  source: ModelSource
  provider: ModelProvider
  protocol: ModelProtocol
  model: string
  baseUrl: string
  dimension: number | null
  contextWindow: number | null
  maxOutputTokens: number | null
  capabilities: ModelCapabilities
  compat: Record<string, unknown>
  runtime: ModelRuntime
}

export interface ModelCalibration {
  semanticFingerprint: string
  threshold: number
  reference: string
}

export interface ManagedModel extends ModelDraft {
  executionSupported?: boolean
  configurationWarning?: string
  deploymentDefault?: boolean
  credentialRequired?: boolean
  calibration: ModelCalibration | null
  id: string
  revision: number
  credentialConfigured: boolean
  credentialVersion: number
  semanticFingerprint: string
}

export interface ModelProviderInfo {
  id: string
  name?: string
  protocols?: string[]
  defaultBaseUrl?: string
}

export interface ModelProtocolInfo {
  id: string
  name?: string
  type?: ModelType
  compatKeys?: string[]
}

export interface ModelProviderCatalog {
  providers: ModelProviderInfo[]
  protocols: ModelProtocolInfo[]
  models?: { model: string; name?: string; type: ModelType; source: ModelSource; provider: ModelProvider; protocol: ModelProtocol; verified: boolean; compatDefaults?: Record<string, unknown> }[]
}

export interface ModelResolution {
  protocol: string
  endpoint: string
  model: string
  effectiveCompat: Record<string, unknown>
  warnings: string[]
}

export interface ModelTestResult {
  success: boolean
  elapsedMs: number
  model: string
  protocol: string
  dimension?: number | null
  scores?: number[] | null
  message: string
  checks?: Record<string, { success: boolean; message: string }>
}

export interface ModelBindings {
  bindings: Partial<Record<ModelPurpose, string>>
}

export interface DeploymentModelDefaults {
  defaults: Partial<Record<ModelPurpose, ManagedModel | null>>
}

export interface IndexRebuildTask {
  id: string
  status: string
  totalChunks?: number
  processedChunks?: number
  errorMessage?: string | null
  [key: string]: unknown
}

export interface KnowledgeIndexStatus {
  knowledgeBaseId: number
  modelId: string | null
  dimension: number | null
  spaceId: string | null
  activeGenerationId: string | null
  status: string
  maintenance: boolean
  task?: IndexRebuildTask | null
}

export const MODEL_TYPES: { value: ModelType; label: string; description: string }[] = [
  { value: 'chat', label: '语言模型', description: '回答、Agent、改写与摘要' },
  { value: 'embedding', label: '向量模型', description: '将文档与查询转为向量' },
  { value: 'rerank', label: '重排模型', description: '对召回片段重新评分' },
]

export const MODEL_PROTOCOLS: { value: ModelProtocol; label: string; type: ModelType }[] = [
  { value: 'openai_chat', label: 'OpenAI 兼容聊天接口', type: 'chat' },
  { value: 'openai_embedding', label: 'OpenAI 兼容向量接口', type: 'embedding' },
  { value: 'cohere_rerank', label: 'Cohere 兼容重排接口', type: 'rerank' },
  { value: 'dashscope_rerank', label: '百炼原生重排接口', type: 'rerank' },
]

export const MODEL_PURPOSES: { value: ModelPurpose; label: string; type: ModelType; description: string }[] = [
  { value: 'generate', label: '问答生成', type: 'chat', description: '根据获准证据生成回答' },
  { value: 'rewrite', label: '查询改写', type: 'chat', description: '将问题转换为检索查询' },
  { value: 'agent', label: 'Agent 决策', type: 'chat', description: '需要工具调用能力' },
  { value: 'summarize', label: '会话摘要', type: 'chat', description: '压缩对话上下文' },
  { value: 'judge', label: '评测裁判', type: 'chat', description: '对生成结果进行模型评测' },
  { value: 'evidence_support', label: '证据支持检查', type: 'chat', description: '核验证据能否完整支持回答' },
  { value: 'embedding', label: '新知识库向量默认值', type: 'embedding', description: '只作为新知识库候选；已有知识库使用固定索引空间' },
  { value: 'rerank', label: '检索重排', type: 'rerank', description: '评分与证据阈值绑定模型语义指纹' },
]

/**
 * 根据模型类型创建带协议和能力初值的登记草稿。
 * 接收type，返回 ModelDraft。
 * 由 mockModels.ts 的 getDeploymentModelDefaults、SettingsView.vue 的 selectModel 触发。
 */
export function newModelDraft(type: ModelType = 'chat'): ModelDraft {
  return {
    name: '', type, source: 'remote', provider: 'generic',
    protocol: type === 'chat' ? 'openai_chat' : type === 'embedding' ? 'openai_embedding' : 'cohere_rerank',
    model: '', baseUrl: '', dimension: null,
    contextWindow: type === 'chat' ? 8192 : null,
    maxOutputTokens: type === 'chat' ? 1024 : null,
    capabilities: { tools: false, streaming: false, json: false, thinking: false },
    compat: {},
    runtime: { timeoutMs: type === 'embedding' ? 120000 : 20000, retries: 0, backoffMs: 500, maxConcurrency: 1, resourceGroup: '' },
  }
}

/**
 * 把已登记模型的配置复制为编辑草稿，使页面可独立修改它。
 * 接收model，返回 ModelDraft。
 * 由 SettingsView.vue 的 selectModel、SettingsView.vue 的 preparedDraft 触发。
 */
export function modelDraft(model: ModelDraft): ModelDraft {
  return {
    name: model.name, type: model.type, source: model.source, provider: model.provider,
    protocol: model.protocol, model: model.model, baseUrl: model.baseUrl,
    dimension: model.dimension, contextWindow: model.contextWindow, maxOutputTokens: model.maxOutputTokens,
    capabilities: { ...model.capabilities }, compat: { ...model.compat }, runtime: { ...model.runtime },
  }
}
