/** 为模拟模式维护模型登记、默认绑定、能力检查和索引任务数据。 */

/** In-memory demo data only. No credential value is retained and no inference or rebuild runs. */
import { MODEL_PROTOCOLS, MODEL_PURPOSES, newModelDraft } from '../modelTypes'
import type { DeploymentModelDefaults, IndexRebuildTask, KnowledgeIndexStatus, ManagedModel, ModelBindings, ModelDraft, ModelProviderCatalog, ModelResolution, ModelTestResult } from '../modelTypes'

/** 深复制 JSON 数据，避免页面变更直接写入模拟仓库。接收value，返回 T。由 listModels、createModel 触发。 */
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T

/** 为模拟模型生成兼容字段的序列化指纹。接收模型草稿，返回文本。由 createModel、updateModel 触发。 */
const fingerprint = (draft: ModelDraft): string => JSON.stringify([draft.type, draft.provider, draft.protocol, draft.model, draft.baseUrl, draft.dimension, draft.capabilities, draft.compat])
let sequence = 3
let models: ManagedModel[] = [
  { ...newModelDraft(), id: 'demo-chat', revision: 1, credentialVersion: 0, semanticFingerprint: 'demo-chat-space',
    name: '演示 · 百炼聊天', provider: 'aliyun', model: 'qwen3.8-flash',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', credentialConfigured: false, calibration: null,
    capabilities: { tools: true, streaming: true, json: true, thinking: true }, compat: { enableThinking: false } },
  { ...newModelDraft('embedding'), id: 'demo-embedding', revision: 1, credentialVersion: 0, semanticFingerprint: 'demo-vector-space',
    name: '演示 · 1024维向量', provider: 'aliyun', model: 'qwen3.7-text-embedding-flash',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', dimension: 1024, credentialConfigured: false, calibration: null },
]
const mockDeploymentChat = clone(models[0])
const mockDeploymentEmbedding = clone(models[1])
let bindings: ModelBindings = { bindings: { generate: 'demo-chat', agent: 'demo-chat', embedding: 'demo-embedding' } }
const indexes = new Map<number, KnowledgeIndexStatus>()

/** 按标识读取现有模拟模型，缺失时抛出错误。接收记录 ID，返回模型配置记录。由 check、testSavedModel 触发。 */
function get(id: string): ManagedModel { const value = models.find(item => item.id === id); if (!value) throw new Error('演示模型不存在。'); return value }

/**
 * 读取模型并校验调用方提交的期望修订号。
 * 接收记录 ID、修订号，返回模型配置记录。
 * 由 updateModel、deleteModel 触发。
 * 下游调用 get。
 */
function check(id: string, revision: number): ManagedModel { const value = get(id); if (value.revision !== revision) throw new Error('配置已变更，请刷新后重试。'); return value }

/** 检查模拟登记草稿的名称、模型、地址及协议组合。接收模型草稿。由 createModel、updateModel 触发。 */
function validate(draft: ModelDraft) {
  if (!draft.name.trim() || !draft.model.trim()) throw new Error('请填写名称和模型名。')
  if (!draft.baseUrl.trim() && draft.provider === 'generic') throw new Error('自定义服务必须填写地址。')
  if (draft.baseUrl) {
    const url = new URL(draft.baseUrl)
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) throw new Error('服务地址不能包含凭证或查询参数。')
  }
  if (MODEL_PROTOCOLS.find(item => item.value === draft.protocol)?.type !== draft.type) throw new Error('模型类型与协议不匹配。')
  if (draft.type === 'embedding' && ![1024, 2560].includes(draft.dimension ?? 0)) throw new Error('知识库目前支持1024或2560维。')
}

/** 读取模型登记记录及其当前版本。返回模型登记列表。下游调用 clone。 */
export async function listModels(): Promise<ManagedModel[]> { return clone(models) }

/** 提交模型草稿并返回创建后的登记记录。接收模型草稿。下游调用 validate、clone。 */
export async function createModel(draft: ModelDraft): Promise<ManagedModel> {
  validate(draft)
  const record = { ...clone(draft), id: `demo-${++sequence}`, revision: 1, credentialVersion: 0, credentialConfigured: false, calibration: null, semanticFingerprint: fingerprint(draft) }
  models.push(record); return clone(record)
}

/** 用期望版本修改已登记模型，接收服务端保存后的记录。接收模型 ID、修订号、模型草稿，返回模型配置记录。下游调用 check、validate。 */
export async function updateModel(id: string, revision: number, draft: ModelDraft): Promise<ManagedModel> {
  const previous = check(id, revision); validate(draft)
  const changed = fingerprint(previous) !== fingerprint(draft)
  const destinationChanged = previous.baseUrl !== draft.baseUrl || previous.provider !== draft.provider
  const record = { ...clone(draft), id, revision: revision + 1,
    credentialVersion: previous.credentialVersion + (destinationChanged ? 1 : 0),
    credentialConfigured: !destinationChanged && previous.credentialConfigured,
    semanticFingerprint: changed ? fingerprint(draft) : previous.semanticFingerprint,
    calibration: changed ? null : previous.calibration }
  models = models.map(item => item.id === id ? record : item); return clone(record)
}

/** 用期望版本删除指定登记记录。接收模型 ID、修订号。下游调用 check。 */
export async function deleteModel(id: string, revision: number): Promise<void> {
  check(id, revision)
  if (Object.values(bindings.bindings).includes(id)) throw new Error('此模型仍被用途引用，请先解除绑定。')
  models = models.filter(item => item.id !== id)
}

/** 校验模拟凭证输入，并记录凭证已配置状态。接收模型 ID、模型凭证、修订号，返回模型配置记录。下游调用 check、clone。 */
export async function putModelCredentials(id: string, apiKey: string, revision: number): Promise<ManagedModel> {
  const value = check(id, revision); if (!apiKey.trim()) throw new Error('请输入凭证。')
  value.credentialConfigured = true; value.credentialVersion++; value.revision++; return clone(value)
}

/** 校验修订号并清除模拟凭证状态。接收模型 ID、修订号，返回模型配置记录。下游调用 check、clone。 */
export async function clearModelCredentials(id: string, revision: number): Promise<ManagedModel> {
  const value = check(id, revision); value.credentialConfigured = false; value.credentialVersion++; value.revision++; return clone(value)
}

/** 保存评分阈值和校准来源。接收model、分数阈值、校准来源，返回模型配置记录。下游调用 check、clone。 */
export async function putModelCalibration(model: ManagedModel, threshold: number, reference: string): Promise<ManagedModel> {
  const value = check(model.id, model.revision)
  if (!reference.trim() || !Number.isFinite(threshold) || threshold < 0 || threshold > 1) throw new Error('需要有效阈值和校准记录。')
  value.calibration = { threshold, reference, semanticFingerprint: value.semanticFingerprint }; value.revision++; return clone(value)
}

/** 读取模型提供方、协议及可选字段目录。返回 ModelProviderCatalog。由 model-protocol.test.ts 的 前端与共享契约只提供四种生产协议 触发。 */
export async function getModelProviders(): Promise<ModelProviderCatalog> {
  return { providers: [
    { id: 'generic', name: '自定义API', protocols: MODEL_PROTOCOLS.map(item => item.value), defaultBaseUrl: '' },
    { id: 'openai', name: 'OpenAI', protocols: ['openai_chat', 'openai_embedding'], defaultBaseUrl: 'https://api.openai.com/v1' },
    { id: 'aliyun', name: '阿里云百炼', protocols: ['openai_chat', 'openai_embedding', 'dashscope_rerank'], defaultBaseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1' },
    { id: 'cohere', name: 'Cohere', protocols: ['cohere_rerank'], defaultBaseUrl: 'https://api.cohere.com/v2' },
    { id: 'ollama', name: 'Ollama（OpenAI 兼容）', protocols: ['openai_chat', 'openai_embedding'], defaultBaseUrl: 'http://localhost:11434/v1' },
  ], protocols: MODEL_PROTOCOLS.map(item => ({ id: item.value, type: item.type, compatKeys: [] })) }
}

/**
 * 解析草稿的模型地址、执行协议和能力配置。
 * 接收模型草稿，返回 ModelResolution。
 * 由 model-protocol.test.ts 的 演示预览拒绝停用协议，不模拟自动迁移 触发。
 * 下游调用 validate、clone。
 */
export async function resolveModel(draft: ModelDraft): Promise<ModelResolution> {
  validate(draft)
  const paths: Record<string, string> = { openai_chat: '/chat/completions', openai_embedding: '/embeddings', cohere_rerank: '/rerank', dashscope_rerank: '/api/v1/services/rerank/text-rerank/text-rerank' }
  const defaults: Record<string, string> = { openai: 'https://api.openai.com/v1', aliyun: 'https://dashscope.aliyuncs.com/compatible-mode/v1', cohere: 'https://api.cohere.com/v2', ollama: 'http://localhost:11434/v1' }
  const base = (draft.baseUrl || defaults[draft.provider] || '').replace(/\/+$/, '')
  const endpoint = draft.protocol === 'dashscope_rerank' ? new URL(paths.dashscope_rerank, base).toString() : base.endsWith(paths[draft.protocol]) ? base : base + paths[draft.protocol]
  return { protocol: draft.protocol, endpoint, model: draft.model, effectiveCompat: clone(draft.compat), warnings: ['演示预览，未调用真实模型。'] }
}

/**
 * 检查草稿并返回预设能力检测结果。
 * 接收模型草稿。
 * 由 testSavedModel 触发。
 * 下游调用 validate。
 */
export async function testModelDraft(draft: ModelDraft): Promise<ModelTestResult> {
  validate(draft); return { success: false, elapsedMs: 0, model: draft.model, protocol: draft.protocol, message: '演示模式不会调用模型，不能确认连通性或质量。' }
}

/** 通过已登记的模拟模型执行草稿检测。接收模型 ID，返回 ModelTestResult。下游调用 testModelDraft、get。 */
export async function testSavedModel(id: string): Promise<ModelTestResult> { return testModelDraft(get(id)) }

/** 读取当前账号的模型用途绑定。返回 ModelBindings。下游调用 clone。 */
export async function getModelBindings(): Promise<ModelBindings> { return clone(bindings) }

/** 读取部署管理员提供的默认模型配置。返回 DeploymentModelDefaults。下游调用 clone、modelTypes 的 newModelDraft。 */
export async function getDeploymentModelDefaults(): Promise<DeploymentModelDefaults> {
  const chat = { ...clone(mockDeploymentChat), id: 'deployment-default-chat', name: '演示部署默认 Chat / Agent', deploymentDefault: true, credentialRequired: false }
  const embedding = { ...clone(mockDeploymentEmbedding), id: 'deployment-default-embedding', name: '演示部署默认向量模型', deploymentDefault: true, credentialRequired: false }
  const rerankDraft = { ...newModelDraft('rerank'), name: '演示部署默认重排模型', provider: 'generic' as const,
    protocol: 'cohere_rerank' as const, model: 'demo-rerank', baseUrl: 'http://127.0.0.1:8091/v1' }
  const rerank: ManagedModel = { ...rerankDraft, id: 'deployment-default-rerank', revision: 1, credentialVersion: 0,
    credentialConfigured: false, credentialRequired: true, deploymentDefault: true, semanticFingerprint: 'demo-rerank-space', calibration: null }
  return { defaults: {
    generate: chat, rewrite: chat, agent: chat, summarize: chat, judge: chat, evidence_support: chat,
    embedding, rerank,
  } }
}

/** 提交模型用途映射并返回已生效的绑定。接收value。下游调用 get、clone。 */
export async function putModelBindings(value: ModelBindings): Promise<ModelBindings> {
  for (const [purpose, id] of Object.entries(value.bindings)) {
    const model = get(id)
    if (MODEL_PURPOSES.find(item => item.value === purpose)?.type !== model.type) throw new Error('用途和类型不匹配。')
    if (purpose === 'agent' && !model.capabilities.tools) throw new Error('Agent需要工具调用能力。')
  }
  bindings = clone(value); return clone(bindings)
}

/** 读取指定知识库的活动索引和维护任务。接收知识库 ID，返回 KnowledgeIndexStatus。下游调用 clone。 */
export async function getKnowledgeIndex(id: number): Promise<KnowledgeIndexStatus> {
  return clone(indexes.get(id) ?? { knowledgeBaseId: id, modelId: null, dimension: null, activeGenerationId: null, spaceId: null, status: 'requires_rebuild', maintenance: false })
}

/** 核对目标模拟模型，按该模拟数据的能力返回重建结果。接收知识库 ID、模型 ID。下游调用 get。 */
export async function startIndexRebuild(id: number, modelId: string): Promise<IndexRebuildTask> {
  if (get(modelId).type !== 'embedding') throw new Error('请选择向量模型。')
  throw new Error(`演示模式不会执行知识库 ${id} 的向量重建。`)
}

/** 返回模拟索引任务无法取消的错误。接收_id、_taskId。 */
export async function cancelIndexRebuild(_id: number, _taskId: string): Promise<IndexRebuildTask> { throw new Error('演示模式没有实际重建任务。') }
