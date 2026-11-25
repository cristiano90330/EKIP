<!-- 组织设置页面和按需面板，处理模型编辑、凭证、能力检测与用途绑定。 -->

<script setup lang="ts">
import { computed, defineAsyncComponent, h, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { USE_MOCK } from '../config'
import { getAccountAdminAccess } from '../api/auth'
import { clearModelCredentials, createModel, deleteModel, getDeploymentModelDefaults, getModelBindings, getModelProviders, listModels, putModelBindings, putModelCalibration, putModelCredentials, resolveModel, testModelDraft, testSavedModel, updateModel } from '../api/models'
import { MODEL_PROTOCOLS, MODEL_PURPOSES, MODEL_TYPES, modelDraft, newModelDraft } from '../modelTypes'
import type { DeploymentModelDefaults, ManagedModel, ModelDraft, ModelProviderCatalog, ModelPurpose, ModelResolution, ModelTestResult } from '../modelTypes'
const panelLoadFeedback = {
  /** 为按需加载中的设置面板渲染状态提示。 */
  loadingComponent: () => h('p', { role: 'status', class: 'muted' }, '正在打开此设置…'),

  /** 为加载失败的面板渲染告警。 */
  errorComponent: () => h('p', { role: 'alert', class: 'error' }, '无法打开此设置，请刷新页面后重试。'),
}
const KnowledgeIndexManager = defineAsyncComponent({ ...panelLoadFeedback, loader: () => import('../components/KnowledgeIndexManager.vue') })
const AccountAdminPanel = defineAsyncComponent({ ...panelLoadFeedback, loader: () => import('../components/AccountAdminPanel.vue') })
const McpKeyPanel = defineAsyncComponent({ ...panelLoadFeedback, loader: () => import('../components/McpKeyPanel.vue') })
const AuditLogPanel = defineAsyncComponent({ ...panelLoadFeedback, loader: () => import('../components/AuditLogPanel.vue') })
const RetrievalEvaluationPanel = defineAsyncComponent({ ...panelLoadFeedback, loader: () => import('../components/RetrievalEvaluationPanel.vue') })

const models = ref<ManagedModel[]>([])
const catalog = ref<ModelProviderCatalog>({ providers: [], protocols: [] })
const deploymentDefaults = ref<DeploymentModelDefaults>({ defaults: {} })
const bindings = ref<Partial<Record<ModelPurpose, string>>>({})
const selected = ref<ManagedModel | null>(null)
const draft = ref<ModelDraft>(newModelDraft())
const compatText = ref('{}')
const credential = ref('')
const testKey = ref('')
const calibrationThreshold = ref<number | string>('')
const calibrationReference = ref('')
const resolution = ref<ModelResolution | null>(null)
const testResult = ref<ModelTestResult | null>(null)
const checks = ref<string[]>(['text'])
const busy = ref(false)
const loaded = ref(false)
const error = ref('')
const notice = ref('')
const bindingSaveError = ref('')
const bindingSaveNotice = ref('')
const deleteDialog = ref<HTMLDialogElement | null>(null)
const deleteError = ref('')
const modelDeleted = ref(false)
const modelListHeading = ref<HTMLElement | null>(null)

type SettingsTab = 'models' | 'defaults' | 'bindings' | 'index' | 'accounts' | 'mcp' | 'audit' | 'evaluation'
interface SettingsSection { value: SettingsTab; label: string }
const MODEL_SECTIONS: SettingsSection[] = [
  { value: 'models', label: '我的模型' },
  { value: 'defaults', label: '默认部署' },
  { value: 'bindings', label: '用途绑定' },
  { value: 'index', label: '知识库索引' },
]
const INTEGRATION_SECTIONS: SettingsSection[] = [
  { value: 'mcp', label: 'MCP Key' },
  { value: 'evaluation', label: '检索评测' },
]
const ADMIN_SECTIONS: SettingsSection[] = [
  { value: 'accounts', label: '账号管理' },
  { value: 'audit', label: '操作审计' },
]
const DEFAULT_MODEL_GROUPS: { purpose: ModelPurpose; title: string; usage: string }[] = [
  { purpose: 'agent', title: '问答与 Agent', usage: '问答、Agent、改写、审查与摘要' },
  { purpose: 'embedding', title: '知识库向量', usage: '新建索引及显式重建的默认向量模型' },
  { purpose: 'rerank', title: '检索重排', usage: '需要重排的 RAG 策略' },
]
const route = useRoute()
const tab = ref<SettingsTab>(route.query.section === 'bindings' ? 'bindings' : 'models')
const accountAdmin = ref(false)
const modelTab = computed(() => MODEL_SECTIONS.some(item => item.value === tab.value))
const settingsGroups = computed(() => {
  const groups = [{ label: '模型与索引', items: MODEL_SECTIONS }]
  if (!USE_MOCK) groups.push({ label: '集成与评测', items: INTEGRATION_SECTIONS })
  if (accountAdmin.value) groups.push({ label: '账号与审计', items: ADMIN_SECTIONS })
  return groups
})

/** 模型分区内部的两级视图：先看列表，再进入某个模型的分步配置。 */
type ModelStage = 'list' | 'edit'
const stage = ref<ModelStage>('list')
const leavePrompt = ref(false)
/** 保存首步后把视图带到「02 凭证」，让动线自己往下走，而不是让用户去找下一步。 */
const credentialBlock = ref<HTMLElement | null>(null)

const provider = computed(() => catalog.value.providers.find(item => item.id === draft.value.provider))
const protocols = computed(() => MODEL_PROTOCOLS.filter(item => item.type === draft.value.type && (!provider.value?.protocols || provider.value.protocols.includes(item.value))))
const candidates = computed(() => (catalog.value.models ?? []).filter(item => item.type === draft.value.type && item.provider === draft.value.provider))
const compatKeys = computed(() => catalog.value.protocols.find(item => item.id === draft.value.protocol)?.compatKeys ?? [])
const availableChecks = computed(() => draft.value.type === 'chat' ? ['text', 'tools', 'stream', 'json'] : [draft.value.type === 'embedding' ? 'embedding' : 'rerank'])
const capabilityKeys = ['tools', 'streaming', 'json', 'thinking'] as const
const capabilityLabels = { tools: '工具调用', streaming: '流式输出', json: 'JSON 输出', thinking: '推理模式' }
/** 连接测试的能力键是协议能力（text/tools/stream/json），与上面的模型能力字段不是同一组，单独映射。 */
const CHECK_LABELS: Record<string, string> = {
  text: '文本', tools: '工具调用', stream: '流式输出', json: 'JSON 输出',
  embedding: '向量化', rerank: '重排',
}

/** 将模型检查项代码转换为显示名称。接收check。 */
const checkLabel = (check: string) => CHECK_LABELS[check] ?? check
const hasUnsavedChanges = computed(() => selected.value !== null && (JSON.stringify(draft.value) !== JSON.stringify(modelDraft(selected.value)) || compatText.value !== JSON.stringify(selected.value.compat, null, 2)))
/** 新建草稿没有 selected 可比，单独判断用户是否已经填过内容，避免返回时静默丢弃。 */
const isNewDirty = computed(() => selected.value === null
  && (JSON.stringify(draft.value) !== JSON.stringify(newModelDraft()) || compatText.value !== '{}'))
const dirty = computed(() => hasUnsavedChanges.value || isNewDirty.value)

/** 从模型类型目录读取标签。接收type。 */
const typeLabel = (type: ManagedModel['type']) => MODEL_TYPES.find(item => item.value === type)?.label ?? type
/** 统计使用指定模型的当前用途数。接收模型 ID。 */
const usageCount = (modelId: string) => Object.values(bindings.value).filter(id => id === modelId).length
const hasDeploymentDefaults = computed(() => Object.values(deploymentDefaults.value.defaults).some(Boolean))
const hasPersonalBinding = computed(() => Object.values(bindings.value).some(Boolean))
const indexModels = computed(() => {
  const sharedEmbedding = deploymentDefaults.value.defaults.embedding
  return sharedEmbedding ? [...models.value, sharedEmbedding] : models.value
})

/** 从失败对象提取用户可读消息，缺少消息时使用当前操作提示。接收失败原因。由 action、removeModel 触发。 */
const messageOf = (cause: unknown) => cause instanceof Error ? cause.message : '操作失败，请稍后重试。'
watch([draft, compatText], () => { resolution.value = null; testResult.value = null }, { deep: true })
watch(() => draft.value.baseUrl, () => { testKey.value = '' })

/**
 * 统一模型写操作期间的等待状态和异常反馈。
 * 接收operation。
 * 由 refresh、save 触发。
 * 下游调用 messageOf。
 */
async function action(operation: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try { await operation() } catch (cause) { error.value = messageOf(cause) } finally { busy.value = false }
}

/**
 * 更新模型选择并初始化对应编辑内容。
 * 接收model。
 * 由 refresh、openModel 触发。
 * 下游调用 modelTypes 的 modelDraft、modelTypes 的 newModelDraft。
 */
function selectModel(model: ManagedModel | null) {
  selected.value = model
  draft.value = model ? modelDraft(model) : newModelDraft()
  compatText.value = JSON.stringify(draft.value.compat, null, 2)
  credential.value = ''; testKey.value = ''
  calibrationThreshold.value = model?.calibration?.threshold ?? ''
  calibrationReference.value = model?.calibration?.reference ?? ''
  resetChecks(); resolution.value = null; testResult.value = null; error.value = ''; notice.value = model?.configurationWarning ?? ''
}

/** 加载模型、用途配置和提供方目录。由 当前模板的 @click 事件 触发。下游调用 action、models 的 listModels。 */
async function refresh() {
  await action(async () => {
    const results = await Promise.allSettled([listModels(), getModelProviders(), getModelBindings(), getDeploymentModelDefaults()])
    const [modelResult, catalogResult, bindingResult, defaultsResult] = results
    if (modelResult.status === 'fulfilled') models.value = modelResult.value
    if (catalogResult.status === 'fulfilled') catalog.value = catalogResult.value
    if (bindingResult.status === 'fulfilled') {
      bindings.value = Object.fromEntries(MODEL_PURPOSES.map(purpose => [purpose.value, bindingResult.value.bindings[purpose.value] ?? '']))
    }
    if (defaultsResult.status === 'fulfilled') deploymentDefaults.value = defaultsResult.value
    loaded.value = results.every(item => item.status === 'fulfilled')
    const failure = results.find(item => item.status === 'rejected')
    if (failure?.status === 'rejected') throw failure.reason
    // 编辑态刷新只更新列表，不覆盖正在填写的表单；列表态才同步当前选中的模型。
    if (stage.value === 'list') selectModel(selected.value ? models.value.find(item => item.id === selected.value?.id) ?? null : null)
  })
}

// ---------- 动线：列表 → 单个模型的分步配置 ----------
/**
 * 从现有记录或新建默认值生成编辑草稿。
 * 接收model。
 * 由 当前模板的 @click 事件 触发。
 * 下游调用 selectModel。
 */
function openModel(model: ManagedModel | null) {
  selectModel(model)
  stage.value = 'edit'
  leavePrompt.value = false
  // 进入二级视图从头开始读；.content 是 App 外壳里的滚动容器。
  void nextTick(() => document.querySelector('.content')?.scrollTo({ top: 0 }))
}

/** 根据草稿是否有未保存变更决定关闭或提示确认。由 当前模板的 @click 事件 触发。 */
function requestClose() {
  if (dirty.value) { leavePrompt.value = true; return }
  stage.value = 'list'
}

/** 结束编辑并清理当前草稿相关状态。由 当前模板的 @click 事件 触发。 */
function leaveEditor() {
  leavePrompt.value = false
  stage.value = 'list'
}

/** 切换至模型配置选项卡。由 当前模板的 @click 事件 触发。 */
function goModelsTab() {
  tab.value = 'models'
  stage.value = 'list'
}

/** 清除上次模型检查的选择或结果。由 selectModel、changeType 触发。 */
function resetChecks() { checks.value = [draft.value.type === 'chat' ? 'text' : draft.value.type === 'embedding' ? 'embedding' : 'rerank'] }

/** 切换模型类型并校正与类型绑定的协议及能力选项。由 changeProvider、当前模板的 @change 事件 触发。下游调用 resetChecks。 */
function changeType() {
  const type = draft.value.type
  const supported = MODEL_PROTOCOLS.find(item => item.type === type && (!provider.value?.protocols || provider.value.protocols.includes(item.value)))
  if (!supported) draft.value.provider = 'generic'
  draft.value.protocol = (supported ?? MODEL_PROTOCOLS.find(item => item.type === type))!.value
  draft.value.dimension = null
  draft.value.contextWindow = type === 'chat' ? 8192 : null
  draft.value.maxOutputTokens = type === 'chat' ? 1024 : null
  draft.value.runtime.timeoutMs = type === 'embedding' ? 120000 : 20000
  draft.value.capabilities = { tools: false, streaming: false, json: false, thinking: false }
  compatText.value = '{}'; resetChecks(); resolution.value = null; testResult.value = null
}

/** 应用提供方变更并重新整理模型传输选项。由 当前模板的 @change 事件 触发。下游调用 changeType、resetChecks。 */
function changeProvider() {
  const value = provider.value
  if (value?.defaultBaseUrl) draft.value.baseUrl = value.defaultBaseUrl
  if (!protocols.value.some(item => item.value === draft.value.protocol)) {
    const match = protocols.value[0]
    if (match) draft.value.protocol = match.value
    else { draft.value.type = value?.id === 'cohere' ? 'rerank' : 'chat'; changeType() }
  }
  if (draft.value.provider === 'ollama') { draft.value.source = 'local'; draft.value.runtime.resourceGroup = 'local-gpu' }
  else { draft.value.source = 'remote'; draft.value.runtime.resourceGroup = '' }
  compatText.value = '{}'; testKey.value = ''; resetChecks(); resolution.value = null; testResult.value = null
}

/** 按协议修正模型草稿的能力和兼容字段。由 当前模板的 @change 事件 触发。 */
function changeProtocol() { compatText.value = '{}'; resolution.value = null; testResult.value = null; testKey.value = '' }

/** 切换模型来源并重置相应表单字段。由 当前模板的 @change 事件 触发。 */
function changeSource() { draft.value.runtime.resourceGroup = draft.value.source === 'local' ? 'local-gpu' : '' }

/** 将目录中的候选模型配置应用到草稿。接收页面事件。由 当前模板的 @change 事件 触发。 */
function chooseCandidate(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  const candidate = candidates.value.find(item => item.model === value)
  if (!candidate) return
  draft.value.model = candidate.model; draft.value.name = candidate.name ?? candidate.model
  draft.value.protocol = candidate.protocol
  compatText.value = JSON.stringify(candidate.compatDefaults ?? {}, null, 2)
  resolution.value = null; testResult.value = null
}

/**
 * 构造可提交给模型管理接口的规范化表单。
 * 返回 ModelDraft。
 * 由 copyModel、save 触发。
 * 下游调用 modelTypes 的 modelDraft。
 */
function preparedDraft(): ModelDraft {
  const value = modelDraft(draft.value)
  const compat: unknown = JSON.parse(compatText.value)
  if (!compat || Array.isArray(compat) || typeof compat !== 'object') throw new Error('兼容参数必须是 JSON 对象。')
  value.compat = compat as Record<string, unknown>
  value.dimension = value.type === 'embedding' ? Number(value.dimension) : null
  value.contextWindow = value.contextWindow ? Number(value.contextWindow) : null
  value.maxOutputTokens = value.maxOutputTokens ? Number(value.maxOutputTokens) : null
  if (!value.name.trim() || !value.model.trim()) throw new Error('请填写显示名称和远端模型名。')
  if (value.type === 'embedding' && ![1024, 2560].includes(value.dimension ?? 0)) throw new Error('请选择 1024 或 2560 维。')
  return value
}

/**
 * 以已保存的模型记录刷新表单和列表。
 * 接收model。
 * 由 save、updateCredential 触发。
 * 下游调用 selectModel。
 */
function acceptModel(model: ManagedModel) {
  models.value = models.value.some(item => item.id === model.id) ? models.value.map(item => item.id === model.id ? model : item) : [...models.value, model]
  selectModel(model)
}

/** 复制当前草稿以创建独立模型登记。由 当前模板的 @click 事件 触发。下游调用 preparedDraft、selectModel。 */
function copyModel() {
  const copy = preparedDraft()
  selectModel(null); draft.value = copy; draft.value.name += '（副本）'
  compatText.value = JSON.stringify(copy.compat, null, 2); resetChecks()
}

/** 创建或更新当前模型并同步已保存状态。由 当前模板的 @submit.prevent 事件 触发。下游调用 action、preparedDraft。 */
function save() {
  return action(async () => {
    const value = preparedDraft()
    acceptModel(selected.value ? await updateModel(selected.value.id, selected.value.revision, value) : await createModel(value))
    notice.value = '模型配置已保存。凭证通过独立入口设置。'
    // 首步保存后把视线带到凭证：还没配凭证时明确「下一步在这里」。
    // 不用 scrollIntoView —— 它对齐的是滚动容器的内容框顶边，会顶到顶栏下面；这里显式留出余量。
    if (!selected.value?.credentialConfigured) {
      await nextTick()
      const content = document.querySelector('.content')
      const target = credentialBlock.value
      if (content && target) {
        // 滚动容器的 padding-top 不会随内容滚走，落点要把它和余量一起让出来。
        const padTop = Number.parseFloat(getComputedStyle(content).paddingTop) || 0
        const delta = target.getBoundingClientRect().top - content.getBoundingClientRect().top
        content.scrollTo({ top: content.scrollTop + delta - padTop - 16, behavior: 'smooth' })
      }
    }
  })
}

/** 打开当前登记记录的删除确认。由 当前模板的 @click 事件 触发。 */
function openDeleteDialog() {
  deleteError.value = ''
  modelDeleted.value = false
  deleteDialog.value?.showModal()
}

/** 按当前版本删除模型登记并恢复列表选择。由 当前模板的 @click 事件 触发。下游调用 action、models 的 deleteModel。 */
async function removeModel() {
  const model = selected.value
  if (!model) return
  await action(async () => {
    deleteError.value = ''
    try {
      await deleteModel(model.id, model.revision)
    } catch (cause) {
      deleteError.value = messageOf(cause)
      return
    }
    models.value = models.value.filter(item => item.id !== model.id)
    selectModel(null)
    stage.value = 'list'
    modelDeleted.value = true
  })
  await nextTick()
  deleteDialog.value?.querySelector('button')?.focus()
}

/** 解析当前草稿并展示后端解析出的模型执行信息。由 当前模板的 @click 事件 触发。下游调用 action、models 的 resolveModel。 */
function preview() { return action(async () => { resolution.value = await resolveModel(preparedDraft()) }) }

/**
 * 选择草稿或保存后的模型发起能力检查。
 * 接收saved。
 * 由 当前模板的 @click 事件 触发。
 * 下游调用 action、models 的 testSavedModel。
 */
function test(saved: boolean) {
  return action(async () => {
    testResult.value = null
    if (!checks.value.length) throw new Error('请至少选择一项连接测试。')
    try {
      testResult.value = saved && selected.value ? await testSavedModel(selected.value.id, [...checks.value]) : await testModelDraft(preparedDraft(), testKey.value || undefined, [...checks.value])
    } finally { testKey.value = '' }
  })
}

/**
 * 保存或清除当前已登记模型的凭证。
 * 接收clear。
 * 由 当前模板的 @click 事件 触发。
 * 下游调用 action、acceptModel。
 */
function updateCredential(clear: boolean) {
  return action(async () => {
    const model = selected.value
    if (!model || hasUnsavedChanges.value) throw new Error('请先保存或放弃模型配置修改，再操作凭证。')
    try {
      if (!clear && !credential.value.trim()) throw new Error('请输入新的 API Key。')
      acceptModel(clear ? await clearModelCredentials(model.id, model.revision) : await putModelCredentials(model.id, credential.value, model.revision))
      notice.value = clear ? '凭证已清除。' : '凭证已更新。'
    } finally { credential.value = '' }
  })
}

/** 提交当前模型的分数阈值和校准来源。由 当前模板的 @click 事件 触发。下游调用 action、acceptModel。 */
function saveCalibration() {
  return action(async () => {
    if (!selected.value || hasUnsavedChanges.value) throw new Error('请先保存模型配置，再记录校准。')
    if (calibrationThreshold.value === '' || !calibrationReference.value.trim()) throw new Error('请填写已验证的阈值和校准记录。')
    const threshold = Number(calibrationThreshold.value)
    if (!Number.isFinite(threshold) || threshold < 0 || threshold > 1) throw new Error('阈值应在 0 到 1 之间。')
    acceptModel(await putModelCalibration(selected.value, threshold, calibrationReference.value.trim()))
    notice.value = '校准已绑定到当前模型语义指纹。'
  })
}

/** 将当前用途绑定交给模型管理 API 保存。由 当前模板的 @click 事件 触发。下游调用 models 的 putModelBindings、messageOf。 */
async function saveBindings() {
  if (busy.value) return
  busy.value = true
  error.value = ''; notice.value = ''
  bindingSaveError.value = ''; bindingSaveNotice.value = ''
  try {
    const values = Object.fromEntries(Object.entries(bindings.value).filter(([, id]) => !!id))
    bindings.value = { ...(await putModelBindings({ bindings: values })).bindings }
    bindingSaveNotice.value = '用途绑定已保存。'
  } catch (cause) {
    bindingSaveError.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}

/** 将当前用途选择恢复到部署默认值。由 当前模板的 @click 事件 触发。下游调用 models 的 putModelBindings、messageOf。 */
async function restoreDeploymentDefaults() {
  if (busy.value) return
  busy.value = true
  error.value = ''; notice.value = ''
  bindingSaveError.value = ''; bindingSaveNotice.value = ''
  try {
    bindings.value = { ...(await putModelBindings({ bindings: {} })).bindings }
    for (const purpose of MODEL_PURPOSES) bindings.value[purpose.value] = ''
    bindingSaveNotice.value = '个人用途绑定已清除，未指定用途将使用默认部署模型。'
  } catch (cause) {
    bindingSaveError.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}

/** 根据用途需要的类型筛选可绑定的模型记录。接收purpose。 */
function optionsFor(purpose: typeof MODEL_PURPOSES[number]) {
  return models.value.filter(model => model.type === purpose.type
    && (purpose.value !== 'agent' || model.capabilities.tools)
    && (purpose.value !== 'evidence_support' || model.capabilities.json))
}
onMounted(() => {
  void refresh()
  if (!USE_MOCK) {
    void getAccountAdminAccess().then(allowed => {
      accountAdmin.value = allowed
      if (allowed && route.query.section === 'accounts') tab.value = 'accounts'
    }).catch(() => {})
  }
})
</script>

<template>
  <section class="settings-page">
    <header class="page-head">
      <div>
        <h1>设置</h1>
        <p>配置语言、向量与重排模型，管理用途绑定与知识库索引。</p>
      </div>
      <button v-if="modelTab" :disabled="busy" @click="refresh">刷新配置</button>
    </header>

    <p v-if="USE_MOCK" class="notice">演示模式：配置仅在当前浏览器运行期间有效，不保存密钥、不执行推理或索引重建。</p>
    <template v-if="modelTab">
      <p v-if="error" role="alert" class="error">{{ error }} 如提示修订冲突，请刷新后重新编辑。</p>
      <p v-if="notice" role="status" class="notice">{{ notice }}</p>
      <p v-if="busy && !deleteDialog?.open" role="status" class="muted">正在处理，请稍候…</p>
    </template>

    <nav class="settings-nav" aria-label="设置分区">
      <div v-for="(group, index) in settingsGroups" :key="group.label" class="settings-nav-group"
        role="group" :aria-labelledby="`settings-group-${index}`">
        <span :id="`settings-group-${index}`" class="settings-nav-label">{{ group.label }}</span>
        <div class="settings-nav-items">
          <button v-for="item in group.items" :key="item.value" type="button" class="settings-nav-item"
            :aria-current="tab === item.value ? 'true' : undefined" @click="tab = item.value">
            {{ item.label }}
          </button>
        </div>
      </div>
    </nav>

    <!-- ============ 模型：一级视图（列表） ============ -->
    <section v-if="tab === 'models' && stage === 'list'" class="panel models-panel">
      <div class="section-head">
        <div>
          <h2 ref="modelListHeading" tabindex="-1">个人模型覆盖</h2>
          <p class="muted">部署已提供标准模型。这里仅管理你自己的覆盖配置。</p>
        </div>
      </div>

      <div v-if="!models.length" class="empty-state">
        <span class="empty-mark" aria-hidden="true">◇</span>
        <h3>{{ hasDeploymentDefaults ? '当前使用默认部署模型' : '还没有任何模型' }}</h3>
        <p>{{ hasDeploymentDefaults ? '问答与 Agent 可直接使用标准模型。只有需要个人模型或覆盖默认部署时才添加。' : '默认部署尚未提供；可联系管理员，也可添加个人模型。' }}</p>
        <div class="actions"><button v-if="hasDeploymentDefaults" class="primary" type="button" :disabled="busy || !loaded" @click="tab = 'defaults'">查看默认部署</button><button type="button" :disabled="busy || !loaded" @click="openModel(null)">添加个人模型</button></div>
      </div>

      <div v-else class="model-grid">
        <button
          v-for="model in models"
          :key="model.id"
          type="button"
          class="model-card"
          @click="openModel(model)"
        >
          <span class="model-card-top">
            <strong>{{ model.name }}</strong>
            <span class="pill">{{ typeLabel(model.type) }}</span>
          </span>
          <span class="model-card-model">{{ model.model }}</span>
          <span class="model-card-tags">
            <span class="pill">{{ model.source === 'local' ? '本地' : '云端' }}</span>
            <span class="pill" :class="model.credentialConfigured ? 'ok' : 'warn'">
              {{ model.credentialConfigured ? '已配置凭证' : '无凭证' }}
            </span>
            <span v-if="usageCount(model.id)" class="pill accent">用于 {{ usageCount(model.id) }} 个用途</span>
          </span>
        </button>

        <button type="button" class="model-card add-card" :disabled="busy || !loaded" @click="openModel(null)">
          <span class="add-plus" aria-hidden="true">＋</span>
          <strong>添加模型</strong>
          <small>语言、向量或重排模型</small>
        </button>
      </div>
    </section>

    <!-- ============ 模型：二级视图（单个模型的分步配置） ============ -->
    <div v-else-if="tab === 'models'" class="editor">
      <button type="button" class="back-link" @click="requestClose">
        <span aria-hidden="true">←</span> 我的模型
      </button>

      <div class="editor-head">
        <h2>{{ selected ? selected.name : '添加模型' }}</h2>
        <p v-if="selected" class="muted">修订 {{ selected.revision }} · 凭证版本 {{ selected.credentialVersion }}</p>
        <p v-else class="muted">按 01 → 02 → 03 依次完成。</p>
      </div>
      <div v-if="leavePrompt" class="leave-prompt" role="alert">
        <span>修改尚未保存，返回会丢弃。</span>
        <div class="leave-actions">
          <button type="button" @click="leavePrompt = false">继续编辑</button>
          <button type="button" class="danger" @click="leaveEditor">放弃并返回</button>
        </div>
      </div>

      <!-- 01 连接配置 -->
      <section class="panel block">
        <div class="step-head">
          <span class="step-no">01</span>
          <div>
            <h3>连接配置</h3>
            <p>选择服务与协议；保存后解锁后续步骤。</p>
          </div>
          <button type="submit" form="model-config-form" class="primary step-action" :disabled="busy || !loaded">保存配置</button>
        </div>
        <form id="model-config-form" @submit.prevent="save">
          <fieldset :disabled="busy || !loaded">
            <div class="fields">
              <label>显示名称<input v-model="draft.name" required maxlength="100" placeholder="例如：百炼问答" /></label>
              <label>类型<select v-model="draft.type" @change="changeType"><option v-for="type in MODEL_TYPES" :key="type.value" :value="type.value">{{ type.label }}</option></select></label>
              <label>厂商<select v-model="draft.provider" @change="changeProvider"><option v-for="item in catalog.providers" :key="item.id" :value="item.id">{{ item.name ?? item.id }}</option></select></label>
              <label>来源<select v-model="draft.source" @change="changeSource"><option value="remote">云端服务</option><option value="local">本地服务</option></select></label>
              <label>协议<select v-model="draft.protocol" @change="changeProtocol"><option v-for="item in protocols" :key="item.value" :value="item.value">{{ item.label }}</option></select></label>
              <label v-if="candidates.length">目录候选<select value="" @change="chooseCandidate"><option value="">选择候选以填入模型名</option><option v-for="item in candidates" :key="item.model" :value="item.model">{{ item.name ?? item.model }}（未验证）</option></select><small>目录记录不保证当前地址支持、免费额度或能力。</small></label>
              <label class="wide">远端模型名<input v-model="draft.model" required placeholder="完整模型名或 Ollama 标签" /></label>
              <label class="wide">服务地址<input v-model="draft.baseUrl" type="url" placeholder="https://example.com/v1 或 http://host.docker.internal:11434/v1" /><small>留空使用厂商默认地址；自定义服务须填写。Ollama 填写以 /v1 结尾的 OpenAI 兼容地址。AI 在 Docker Desktop 中运行时，访问本机 Ollama 可填写 http://host.docker.internal:11434/v1。</small></label>
              <label v-if="draft.type === 'embedding'">向量维度<select v-model="draft.dimension"><option :value="null">请选择实际输出维度</option><option :value="1024">1024</option><option :value="2560">2560</option></select></label>
              <template v-if="draft.type === 'chat'"><label>上下文窗口<input v-model.number="draft.contextWindow" type="number" min="1" /></label><label>最大输出 Token<input v-model.number="draft.maxOutputTokens" type="number" min="1" /></label><div class="wide check-row"><label v-for="key in capabilityKeys" :key="key"><input v-model="draft.capabilities[key]" type="checkbox" />{{ capabilityLabels[key] }}</label></div></template>
            </div>

            <details class="sub-details">
              <summary>兼容参数与运行策略</summary>
              <div class="fields">
                <label class="wide">兼容参数（JSON）<textarea v-model="compatText" rows="5" spellcheck="false" /><small>可用参数：{{ compatKeys.join('、') || '请查看运行预览或接口说明' }}。不要填写密钥。</small></label>
                <label>模型超时（ms）<input v-model.number="draft.runtime.timeoutMs" type="number" min="100" max="600000" required /></label><label>重试次数<input v-model.number="draft.runtime.retries" type="number" min="0" max="3" required /></label><label>退避（ms）<input v-model.number="draft.runtime.backoffMs" type="number" min="0" max="10000" required /></label><label>模型并发<input v-model.number="draft.runtime.maxConcurrency" type="number" min="1" max="64" required /></label><label class="wide">共享资源组<input v-model="draft.runtime.resourceGroup" maxlength="128" placeholder="本地 GPU 使用 local-gpu" /><small>本地 GPU 组默认容量 1；排队、重试和读取共同受请求总预算限制。</small></label>
              </div>
            </details>

            <div class="actions">
              <button class="primary" type="submit">保存配置</button>
              <button type="button" @click="preview">预览实际配置</button>
              <button v-if="selected" type="button" @click="action(async () => copyModel())">复制为新模型</button>
              <button v-if="selected && hasUnsavedChanges" type="button" @click="selectModel(selected)">放弃修改</button>
            </div>
          </fieldset>
        </form>
        <div v-if="resolution" class="result">
          <h3>运行预览（未调用模型）</h3>
          <p>{{ resolution.protocol }} · {{ resolution.model }}</p>
          <code>{{ resolution.endpoint }}</code>
          <pre>{{ JSON.stringify(resolution.effectiveCompat, null, 2) }}</pre>
          <p v-for="warning in resolution.warnings" :key="warning">{{ warning }}</p>
        </div>
      </section>

      <!-- 02 模型凭证 -->
      <section ref="credentialBlock" class="panel block">
        <div class="step-head">
          <span class="step-no">02</span>
          <div>
            <h3>模型凭证</h3>
            <p>凭证单独保存，不会回填已有密钥。</p>
          </div>
        </div>
        <p v-if="!selected" class="step-locked">保存连接配置后解锁。地址或厂商变更会清除旧凭证。</p>
        <p v-else-if="hasUnsavedChanges" class="step-locked">请先保存或放弃上方修改。</p>
        <fieldset v-else :disabled="busy || !loaded">
          <label>新的 API Key<input v-model="credential" type="password" autocomplete="new-password" placeholder="不会回填已有密钥" /></label>
          <div class="actions">
            <button type="button" @click="updateCredential(false)">更新凭证</button>
            <button type="button" :disabled="!selected.credentialConfigured" @click="updateCredential(true)">清除凭证</button>
          </div>
        </fieldset>
      </section>

      <!-- 03 连接测试 -->
      <section class="panel block">
        <div class="step-head">
          <span class="step-no">03</span>
          <div>
            <h3>连接测试</h3>
            <p>会请求真实服务并消耗额度；文本通过不代表工具与 JSON 可用。</p>
          </div>
        </div>
        <fieldset :disabled="busy || !loaded">
          <div class="check-row">
            <label v-for="check in availableChecks" :key="check"><input v-model="checks" type="checkbox" :value="check" />{{ checkLabel(check) }}</label>
          </div>
          <label>测试草稿用的 API Key<input v-model="testKey" type="password" autocomplete="new-password" placeholder="仅用于本次测试，不自动使用已存凭证" /></label>
          <div class="actions">
            <button type="button" @click="test(false)">测试当前草稿</button>
            <button v-if="selected" type="button" @click="test(true)">测试已保存版本及凭证</button>
          </div>
          <div v-if="testResult" class="result" role="status">
            <strong>{{ testResult.success ? '测试通过' : '测试未通过' }}</strong>
            <p>{{ testResult.model }} · {{ testResult.protocol }} · {{ testResult.elapsedMs }} ms</p>
            <p>{{ testResult.message }}</p>
            <p v-if="testResult.dimension">返回维度：{{ testResult.dimension }}</p>
            <p v-for="(result, check) in testResult.checks" :key="check">{{ checkLabel(check) }}：{{ result.success ? '通过' : '失败' }} · {{ result.message }}</p>
          </div>
        </fieldset>
      </section>

      <!-- 04 证据阈值校准 -->
      <section class="panel block">
        <div class="step-head">
          <span class="step-no">04</span>
          <div>
            <h3>证据阈值校准</h3>
            <p>只记录已验证的阈值；模型语义变化后需重新校准。</p>
          </div>
        </div>
        <p v-if="!selected" class="step-locked">保存模型后可记录校准。</p>
        <p v-else-if="hasUnsavedChanges" class="step-locked">请先保存或放弃上方修改。</p>
        <fieldset v-else :disabled="busy || !loaded">
          <p class="fingerprint">语义指纹：{{ selected.semanticFingerprint }}</p>
          <div class="fields">
            <label>阈值（0–1）<input v-model="calibrationThreshold" type="number" min="0" max="1" step="any" /></label>
            <label>校准记录<input v-model="calibrationReference" placeholder="数据集、评测记录或报告编号" /></label>
          </div>
          <div class="actions"><button type="button" @click="saveCalibration">保存校准</button></div>
        </fieldset>
      </section>

      <!-- 危险操作 -->
      <section v-if="selected" class="panel block danger-block">
        <div class="step-head">
          <span class="step-no">!</span>
          <div>
            <h3>删除模型</h3>
            <p>被用途、Agent 或知识库索引引用的模型无法删除。</p>
          </div>
        </div>
        <div class="actions"><button type="button" class="danger" :disabled="busy" @click="openDeleteDialog">删除模型</button></div>
      </section>
    </div>

    <!-- ============ 用途绑定 ============ -->
    <section v-else-if="tab === 'bindings'" class="panel">
      <div class="section-head">
        <div>
          <h2>用途绑定</h2>
          <p class="muted">只为实际用到的用途选择模型；知识库向量模型由活动索引决定。</p>
        </div>
      </div>
      <div v-if="!models.length && !hasDeploymentDefaults && !hasPersonalBinding" class="empty-state">
        <span class="empty-mark" aria-hidden="true">◇</span>
        <h3>还没有可绑定的模型</h3>
        <p>先添加模型，再回来绑定。</p>
        <button class="primary" type="button" @click="goModelsTab">前往添加模型</button>
      </div>
      <fieldset v-else :disabled="busy || !loaded">
        <div class="fields bindings-fields">
          <label v-for="purpose in MODEL_PURPOSES" :key="purpose.value">
            {{ purpose.label }}
            <select v-model="bindings[purpose.value]">
              <option value="">{{ deploymentDefaults.defaults[purpose.value] ? '默认部署' : '未配置默认部署' }}</option>
              <option v-if="bindings[purpose.value] && !optionsFor(purpose).some(model => model.id === bindings[purpose.value])" :value="bindings[purpose.value]" disabled>当前绑定不可用，请重新选择</option>
              <option v-for="model in optionsFor(purpose)" :key="model.id" :value="model.id">{{ model.name }} · {{ model.model }}</option>
            </select>
            <small>{{ purpose.description }}</small>
          </label>
        </div>
        <div class="actions"><button class="primary" type="button" :disabled="busy || !loaded" @click="saveBindings">保存个人覆盖</button><button type="button" :disabled="busy || !loaded || !hasPersonalBinding" @click="restoreDeploymentDefaults">清除个人覆盖并恢复默认部署</button></div>
        <p v-if="bindingSaveError" role="alert" class="error">{{ bindingSaveError }}</p>
        <p v-else-if="bindingSaveNotice" role="status" class="notice">{{ bindingSaveNotice }}</p>
      </fieldset>
    </section>

    <!-- ============ 部署统一默认模型 ============ -->
    <section v-else-if="tab === 'defaults'" class="panel">
      <div class="section-head"><div><h2>默认部署</h2><p class="muted">普通用户无需注册模型或新建 Agent 配置；个人设置只作为覆盖。</p></div></div>
      <div v-if="!hasDeploymentDefaults" class="empty-state"><span class="empty-mark" aria-hidden="true">◇</span><h3>默认部署模型未启用</h3><p>请部署管理员启用标准模型组并检查本地 Ollama 与重排服务地址。</p></div>
      <div v-else class="default-grid">
        <article v-for="group in DEFAULT_MODEL_GROUPS" :key="group.purpose" class="default-card">
          <div class="model-card-top"><strong>{{ group.title }}</strong><span class="pill accent">默认部署</span></div>
          <template v-if="deploymentDefaults.defaults[group.purpose]">
            <strong class="default-name">{{ deploymentDefaults.defaults[group.purpose]?.name }}</strong>
            <code>{{ deploymentDefaults.defaults[group.purpose]?.model }}</code>
            <code>{{ deploymentDefaults.defaults[group.purpose]?.baseUrl }}</code>
            <p>{{ group.usage }}</p>
            <p v-if="deploymentDefaults.defaults[group.purpose]?.credentialRequired && !deploymentDefaults.defaults[group.purpose]?.credentialConfigured" class="model-card-warn">重排服务密钥未配置，部署管理员需设置 MODEL_DEFAULT_RERANK_API_KEY。</p>
            <p v-else-if="deploymentDefaults.defaults[group.purpose]?.credentialRequired" class="muted">部署服务密钥已提供，密钥内容不会显示在页面。</p>
          </template>
          <p v-else class="model-card-warn">该用途暂无默认部署模型。</p>
        </article>
      </div>
    </section>

    <!-- ============ 知识库索引 ============ -->
    <KnowledgeIndexManager v-else-if="tab === 'index'" :models="indexModels" />
    <McpKeyPanel v-else-if="tab === 'mcp' && !USE_MOCK" />
    <RetrievalEvaluationPanel v-else-if="tab === 'evaluation' && !USE_MOCK" />
    <AccountAdminPanel v-else-if="tab === 'accounts' && accountAdmin" />
    <AuditLogPanel v-else-if="tab === 'audit' && accountAdmin" />

    <dialog ref="deleteDialog" class="panel delete-dialog" aria-labelledby="delete-model-title"
      @cancel="busy && $event.preventDefault()" @close="modelDeleted && modelListHeading?.focus()">
      <h2 id="delete-model-title">删除模型</h2>
      <p v-if="modelDeleted" role="status">模型已删除。</p>
      <p v-else>确定删除「{{ selected?.name }}」？</p>
      <p v-if="deleteError" class="error" role="alert">{{ deleteError }}</p>
      <div class="actions">
        <button type="button" autofocus :disabled="busy" @click="deleteDialog?.close()">{{ modelDeleted ? '完成' : '取消' }}</button>
        <button v-if="!modelDeleted" type="button" class="danger" :disabled="busy" @click="removeModel">
          {{ busy ? '正在删除…' : '确认删除' }}
        </button>
      </div>
    </dialog>
  </section>
</template>

<style scoped>
.settings-page { max-width: var(--content-max); margin: auto; }
.panel { padding: var(--space-5); }
/* fieldset 做纵向栅格：卡片内的说明、字段、操作按统一节奏排，不靠逐个 margin 拼 */
fieldset { display: flex; flex-direction: column; gap: var(--space-3); border: 0; padding: 0; margin: 0; min-width: 0; }

/* ---------- 分区切换 ---------- */
.settings-nav {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
  padding: var(--space-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--bg-panel);
}

.settings-nav-group { flex: 1 1 auto; padding: var(--space-1); }
.settings-nav-label { display: block; margin: 0 var(--space-2) var(--space-1); color: var(--text-dim); font-size: var(--fs-sm); }
.settings-nav-items { display: flex; flex-wrap: wrap; gap: var(--space-1); }
.settings-nav-item {
  min-height: 34px;
  padding: var(--space-1) var(--space-2);
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text);
  font: inherit;
  cursor: pointer;
}
.settings-nav-item:hover { background: var(--bg-inset); }
.settings-nav-item[aria-current='true'] { background: var(--accent-soft); color: var(--accent); font-weight: var(--fw-semi); }

/* ---------- 一级视图：模型列表 ---------- */
.section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
  margin-bottom: var(--space-5);
}

.section-head h2 { font-size: var(--fs-lg); }
.section-head p { margin-top: 4px; font-size: var(--fs-sm); line-height: 1.55; }

.model-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(232px, 1fr));
  gap: var(--space-3);
}

.model-card {
  display: flex;
  flex-direction: column;
  gap: 7px;
  min-height: 118px;
  padding: var(--space-4);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--bg-panel-2);
  color: var(--text);
  text-align: left;
  white-space: normal;
  line-height: 1.45;
  cursor: pointer;
  transition: border-color var(--dur-base) var(--ease-out),
    box-shadow var(--dur-base) var(--ease-out),
    transform var(--dur-base) var(--ease-out);
}

.model-card:hover:not(:disabled) {
  border-color: var(--accent-line);
  box-shadow: var(--shadow-2);
  transform: translateY(-1px);
}

.model-card-top { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); }
.model-card-top strong { font-size: var(--fs-md); font-weight: var(--fw-semi); overflow-wrap: anywhere; }
.model-card-model { color: var(--text-dim); font-family: var(--font-mono); font-size: var(--fs-sm); overflow-wrap: anywhere; }
.model-card-tags { display: flex; flex-wrap: wrap; gap: 5px; margin-top: auto; }
.model-card-warn { color: var(--warn); font-size: var(--fs-sm); line-height: 1.45; }

.model-card-tags .pill.ok { background: var(--ok-soft); color: var(--ok); }
.model-card-tags .pill.warn { background: var(--warn-soft); color: var(--warn); }
.default-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: var(--space-3); }
.default-card { display: flex; flex-direction: column; gap: var(--space-2); min-width: 0; padding: var(--space-4); border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--bg-panel-2); }
.default-card code { overflow-wrap: anywhere; }
.default-name { font-weight: var(--fw-medium); }

.add-card {
  align-items: center;
  justify-content: center;
  gap: 4px;
  border-style: dashed;
  border-color: var(--border-strong);
  background: transparent;
  color: var(--text-dim);
  text-align: center;
}

.add-card strong { font-size: var(--fs-base); font-weight: var(--fw-medium); }
.add-card small { color: var(--text-faint); font-size: var(--fs-sm); }
.add-plus { font-size: 18px; line-height: 1; }

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-7) var(--space-4);
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-lg);
  text-align: center;
}

.empty-mark { color: var(--accent); font-size: 22px; line-height: 1; }
.empty-state h3 { font-size: var(--fs-lg); }
.empty-state p { max-width: 420px; color: var(--text-dim); font-size: var(--fs-sm); line-height: 1.6; }
.empty-state button { margin-top: var(--space-2); }

/* ---------- 二级视图：单个模型的分步配置 ---------- */
.editor { display: flex; flex-direction: column; gap: var(--space-4); }

.back-link {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  align-self: flex-start;
  height: 34px;
  padding: 0 16px 0 13px;
  border: 1px solid var(--border);
  border-radius: var(--radius-full);
  background: var(--bg-panel);
  box-shadow: var(--shadow-1);
  color: var(--text);
  font-size: var(--fs-base);
  font-weight: var(--fw-medium);
  line-height: 1;
  transition: border-color var(--dur-fast) var(--ease-out),
    box-shadow var(--dur-fast) var(--ease-out),
    color var(--dur-fast) var(--ease-out);
}

.back-link > span { font-size: 15px; line-height: 1; }

.back-link:hover {
  border-color: var(--accent-line);
  box-shadow: var(--shadow-2);
  color: var(--accent);
}

.editor-head { padding: 0 var(--space-1) var(--space-1); }
.editor-head h2 { font-size: var(--fs-xl); }
.editor-head p { margin-top: 4px; font-size: var(--fs-sm); line-height: 1.55; }

.leave-prompt {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  border: 1px solid var(--warn-line);
  border-radius: var(--radius-md);
  padding: 10px 14px;
  background: var(--warn-soft);
  font-size: var(--fs-sm);
}

.leave-actions { display: flex; flex: none; gap: var(--space-2); }

.block { display: flex; flex-direction: column; }

.step-head {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
}

/* 长表单里保存动作常驻在步骤标题右侧，不必滚到卡片底部才找得到 */
.step-action { flex: none; margin-left: auto; }

.step-no {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  flex: none;
  border-radius: 9px;
  background: var(--accent-soft);
  color: var(--accent);
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  font-weight: var(--fw-bold);
  line-height: 1;
}

.step-head h3 { font-size: var(--fs-md); }
.step-head p { margin-top: 3px; color: var(--text-dim); font-size: var(--fs-sm); line-height: 1.55; }

.step-locked {
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-md);
  padding: 11px 14px;
  background: var(--bg-panel-2);
  color: var(--text-dim);
  font-size: var(--fs-sm);
}

.danger-block .step-no { background: var(--err-soft); color: var(--err); }
.delete-dialog {
  width: min(420px, calc(100% - 2rem));
  max-height: calc(100dvh - 2rem);
  overflow: auto;
  border-color: var(--border);
  background: var(--bg-elevated);
  color: var(--text);
  box-shadow: var(--shadow-dialog);
}
.delete-dialog::backdrop { background: rgb(18 20 28 / 52%); }
.delete-dialog > * + * { margin-top: var(--space-3); }
.delete-dialog .actions { justify-content: flex-end; }

/* ---------- 表单 ---------- */
form, label { display: flex; flex-direction: column; gap: var(--space-3); }
label { gap: 6px; font-size: var(--fs-sm); }
/* 控件尺寸对齐全站 TDesign（32px 高 / 14px 字）。此前是 38–42px、12px 字，
   同一产品里比其它页的输入框与按钮“重一档”。复选框与单选框不参与，否则会被拉成方块。 */
input:not([type='checkbox']):not([type='radio']),
select,
textarea,
button { border: 1px solid var(--border); border-radius: var(--radius-md); background: var(--bg-panel-2); color: var(--text); font: inherit; font-size: var(--fs-md); line-height: 20px; padding: 5px 10px; max-width: 100%; }
input:not([type='checkbox']):not([type='radio']),
select,
textarea { min-width: 0; }
textarea { line-height: var(--lh-base); }
button { cursor: pointer; }
button:disabled { cursor: default; opacity: .55; }
button.primary { border-color: transparent; background: var(--accent); color: var(--on-accent-ink); font-weight: var(--fw-medium); }
button:hover:not(:disabled) { border-color: var(--border-strong); }
button.primary:hover:not(:disabled) { background: var(--accent-dim); border-color: transparent; }
button.danger, .error { color: var(--err); }
input:focus, select:focus, textarea:focus, button:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }

.fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--space-4); margin: 0; }
.wide { grid-column: 1 / -1; }
.bindings-fields { grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); }

.check-row, .actions { display: flex; flex-wrap: wrap; gap: var(--space-3); align-items: center; }
.check-row label { flex-direction: row; align-items: center; gap: var(--space-2); }

.sub-details { border-top: 1px solid var(--border); margin-top: var(--space-2); }
.sub-details > summary { padding: 12px 0 4px; color: var(--text-dim); font-size: var(--fs-sm); }
.sub-details .fields { margin-top: var(--space-3); }

p, small { color: var(--text-dim); }
.notice, .error, .result { border: 1px solid var(--border); border-radius: var(--radius-md); padding: var(--space-4); background: var(--bg-panel); }
.notice { color: var(--text); }
.result { margin-top: var(--space-4); }
.hint { color: var(--warn); }
pre, code, .fingerprint { overflow-wrap: anywhere; white-space: pre-wrap; font-size: var(--fs-sm); }

@media (max-width: 820px) {
  .fields { grid-template-columns: 1fr; }
  .leave-prompt { flex-direction: column; align-items: flex-start; }
}
</style>
