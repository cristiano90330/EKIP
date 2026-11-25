/** 封装模型登记、凭证、能力检测、用途绑定及知识库索引生命周期的请求。 */

import { USE_MOCK } from '../config'
import type { DeploymentModelDefaults, IndexRebuildTask, KnowledgeIndexStatus, ManagedModel, ModelBindings, ModelDraft, ModelProviderCatalog, ModelResolution, ModelTestResult } from '../modelTypes'
import { http } from './http'
import * as mock from './mockModels'

/**
 * 读取模型登记记录及其当前版本。
 * 返回模型登记列表。
 * 由 AgentProfileManager.vue 的 loadChatModels、SettingsView.vue 的 refresh 触发。
 * 下游调用 ModelManagementController／KnowledgeIndexController。
 */
export const listModels = (): Promise<ManagedModel[]> => USE_MOCK ? mock.listModels() : http.get<ManagedModel[], ManagedModel[]>('/models')

/**
 * 提交模型草稿并返回创建后的登记记录。
 * 接收model。
 * 由 SettingsView.vue 的 save 触发。
 * 下游调用 ModelManagementController／KnowledgeIndexController。
 */
export const createModel = (model: ModelDraft): Promise<ManagedModel> => USE_MOCK ? mock.createModel(model) : http.post<ManagedModel, ManagedModel>('/models', model)

/**
 * 用期望版本修改已登记模型，接收服务端保存后的记录。
 * 接收模型 ID、期望修订号、model，返回模型配置记录。
 * 由 SettingsView.vue 的 save 触发。
 * 下游调用 ModelManagementController／KnowledgeIndexController。
 */
export const updateModel = (id: string, expectedRevision: number, model: ModelDraft): Promise<ManagedModel> => USE_MOCK
  ? mock.updateModel(id, expectedRevision, model)
  : http.put<ManagedModel, ManagedModel>(`/models/${encodeURIComponent(id)}`, { ...model, expectedRevision })

/**
 * 用期望版本删除指定登记记录。
 * 接收模型 ID、期望修订号，返回 unknown。
 * 由 SettingsView.vue 的 removeModel 触发。
 * 下游调用 ModelManagementController／KnowledgeIndexController。
 */
export const deleteModel = (id: string, expectedRevision: number): Promise<unknown> => USE_MOCK
  ? mock.deleteModel(id, expectedRevision)
  : http.delete(`/models/${encodeURIComponent(id)}`, { params: { expectedRevision } })

/**
 * 向模型记录提交新凭证并返回掩蔽后的状态。
 * 接收模型 ID、模型凭证、期望修订号。
 * 由 SettingsView.vue 的 updateCredential 触发。
 * 下游调用 ModelManagementController／KnowledgeIndexController。
 */
export const putModelCredentials = (id: string, apiKey: string, expectedRevision: number): Promise<ManagedModel> => USE_MOCK
  ? mock.putModelCredentials(id, apiKey, expectedRevision)
  : http.put<ManagedModel, ManagedModel>(`/models/${encodeURIComponent(id)}/credentials`, { apiKey, expectedRevision })

/**
 * 清除此模型的凭证配置并返回新状态。
 * 接收模型 ID、期望修订号。
 * 由 SettingsView.vue 的 updateCredential 触发。
 * 下游调用 ModelManagementController／KnowledgeIndexController。
 */
export const clearModelCredentials = (id: string, expectedRevision: number): Promise<ManagedModel> => USE_MOCK
  ? mock.clearModelCredentials(id, expectedRevision)
  : http.delete<ManagedModel, ManagedModel>(`/models/${encodeURIComponent(id)}/credentials`, { params: { expectedRevision } })

/**
 * 保存评分阈值和校准来源。
 * 接收model、分数阈值、校准来源，返回模型配置记录。
 * 由 SettingsView.vue 的 saveCalibration 触发。
 * 下游调用 ModelManagementController／KnowledgeIndexController。
 */
export const putModelCalibration = (model: ManagedModel, threshold: number, reference: string): Promise<ManagedModel> => USE_MOCK
  ? mock.putModelCalibration(model, threshold, reference)
  : http.put<ManagedModel, ManagedModel>(`/models/${encodeURIComponent(model.id)}/calibration`, {
      expectedRevision: model.revision, semanticFingerprint: model.semanticFingerprint, threshold, reference,
    })

/**
 * 读取模型提供方、协议及可选字段目录。
 * 返回 ModelProviderCatalog。
 * 由 SettingsView.vue 的 refresh 触发。
 * 下游调用 ModelManagementController／KnowledgeIndexController。
 */
export const getModelProviders = (): Promise<ModelProviderCatalog> => USE_MOCK ? mock.getModelProviders() : http.get<ModelProviderCatalog, ModelProviderCatalog>('/models/providers')

/**
 * 解析草稿的模型地址、执行协议和能力配置。
 * 接收model，返回 ModelResolution。
 * 由 SettingsView.vue 的 preview 触发。
 * 下游调用 ModelManagementController／KnowledgeIndexController。
 */
export const resolveModel = (model: ModelDraft): Promise<ModelResolution> => USE_MOCK ? mock.resolveModel(model) : http.post<ModelResolution, ModelResolution>('/models/resolve', { model })
/**
 * 使用草稿和本次提供的凭证运行模型能力检测。
 * 接收model、模型凭证、检测项，返回 ModelTestResult。
 * 由 SettingsView.vue 的 test 触发。
 * 下游调用 ModelManagementController／KnowledgeIndexController。
 */
export const testModelDraft = (model: ModelDraft, apiKey?: string, checks?: string[]): Promise<ModelTestResult> => USE_MOCK
  ? mock.testModelDraft(model)
  : http.post<ModelTestResult, ModelTestResult>('/models/test', { model, ...(apiKey ? { apiKey } : {}), checks }, { timeout: 40000 })

/**
 * 对已登记模型运行所选检测项。
 * 接收模型 ID、检测项，返回 ModelTestResult。
 * 由 SettingsView.vue 的 test 触发。
 * 下游调用 ModelManagementController／KnowledgeIndexController。
 */
export const testSavedModel = (id: string, checks?: string[]): Promise<ModelTestResult> => USE_MOCK
  ? mock.testSavedModel(id)
  : http.post<ModelTestResult, ModelTestResult>(`/models/${encodeURIComponent(id)}/test`, { checks }, { timeout: 40000 })

/**
 * 读取当前账号的模型用途绑定。
 * 返回 ModelBindings。
 * 由 SettingsView.vue 的 refresh 触发。
 * 下游调用 ModelManagementController／KnowledgeIndexController。
 */
export const getModelBindings = (): Promise<ModelBindings> => USE_MOCK ? mock.getModelBindings() : http.get<ModelBindings, ModelBindings>('/models/bindings')

/**
 * 提交模型用途映射并返回已生效的绑定。
 * 接收模型用途映射。
 * 由 SettingsView.vue 的 saveBindings、SettingsView.vue 的 restoreDeploymentDefaults 触发。
 * 下游调用 ModelManagementController／KnowledgeIndexController。
 */
export const putModelBindings = (bindings: ModelBindings): Promise<ModelBindings> => USE_MOCK ? mock.putModelBindings(bindings) : http.put<ModelBindings, ModelBindings>('/models/bindings', bindings)

/**
 * 读取部署管理员提供的默认模型配置。
 * 返回 DeploymentModelDefaults。
 * 由 SettingsView.vue 的 refresh 触发。
 * 下游调用 ModelManagementController／KnowledgeIndexController。
 */
export const getDeploymentModelDefaults = (): Promise<DeploymentModelDefaults> => USE_MOCK
  ? mock.getDeploymentModelDefaults()
  : http.get<DeploymentModelDefaults, DeploymentModelDefaults>('/models/defaults')

/**
 * 读取指定知识库的活动索引和维护任务。
 * 接收知识库 ID，返回 KnowledgeIndexStatus。
 * 由 KnowledgeIndexManager.vue 的 loadStatus、LibraryView.vue 的 loadDocuments 触发。
 * 下游调用 ModelManagementController／KnowledgeIndexController。
 */
export const getKnowledgeIndex = (id: number): Promise<KnowledgeIndexStatus> => USE_MOCK ? mock.getKnowledgeIndex(id) : http.get<KnowledgeIndexStatus, KnowledgeIndexStatus>(`/knowledge-bases/${id}/index`)

/**
 * 为指定知识库和目标模型提交索引重建任务。
 * 接收知识库 ID、模型 ID、预期的活动索引版本，返回索引重建任务。
 * 由 KnowledgeIndexManager.vue 的 rebuild 触发。
 * 下游调用 ModelManagementController／KnowledgeIndexController。
 */
export const startIndexRebuild = (id: number, modelId: string, expectedActiveGenerationId: string | null): Promise<IndexRebuildTask> => USE_MOCK
  ? mock.startIndexRebuild(id, modelId)
  : http.post<IndexRebuildTask, IndexRebuildTask>(`/knowledge-bases/${id}/index/rebuilds`, { modelId, expectedActiveGenerationId })

/**
 * 取消指定知识库的重建任务。
 * 接收知识库 ID、任务 ID，返回索引重建任务。
 * 由 KnowledgeIndexManager.vue 的 cancel 触发。
 * 下游调用 ModelManagementController／KnowledgeIndexController。
 */
export const cancelIndexRebuild = (id: number, taskId: string): Promise<IndexRebuildTask> => USE_MOCK
  ? mock.cancelIndexRebuild(id, taskId)
  : http.post<IndexRebuildTask, IndexRebuildTask>(`/knowledge-bases/${id}/index/rebuilds/${encodeURIComponent(taskId)}/cancel`)
