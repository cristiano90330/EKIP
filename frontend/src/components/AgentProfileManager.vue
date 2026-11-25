<!-- 编辑自建 Agent 的工具、模型、知识库范围和记忆配置。 -->

<script setup lang="ts">
import { Option as TOption, Select as TSelect } from 'tdesign-vue-next'
import { computed, reactive, ref, watch } from 'vue'
import { MessagePlugin } from 'tdesign-vue-next'
import {
  createAgentProfile,
  deleteAgentProfile,
  listAgentTools,
  updateAgentProfile,
  type AgentKbSelectionMode,
  type AgentAnswerPolicy,
  type AgentProfileDTO,
  type AgentProfileRequestDTO,
  type AgentToolDTO,
} from '../api/agent'
import type { KnowledgeBaseDTO } from '../types'
import { listModels } from '../api/models'
import type { ManagedModel } from '../modelTypes'

const props = defineProps<{
  visible: boolean
  profiles: AgentProfileDTO[]
  knowledgeBases: KnowledgeBaseDTO[]
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  changed: []
  select: [id: number]
}>()

const toolCatalog = ref<AgentToolDTO[]>([])
const chatModels = ref<ManagedModel[]>([])
const modelsError = ref('')
const modelsLoading = ref(false)
const chatModelOptions = computed(() => [
  { value: '', label: '使用默认 Agent 模型（个人绑定优先）' },
  ...chatModels.value.map(model => ({ value: model.id, label: `${model.name} · ${model.model}` })),
  ...(form.chatModelId && !chatModels.value.some(model => model.id === form.chatModelId)
    ? [{ value: form.chatModelId, label: '当前覆盖模型不可用，请重新选择', disabled: true }] : []),
])
const toolsLoading = ref(false)
const toolsLoaded = ref(false)
const toolsError = ref('')
let catalogLoadId = 0
const TOOL_GROUPS = { knowledge: '知识库工具', memory: '个人记忆', other: '其他工具' }
const TOOL_DETAILS: Record<string, { group: keyof typeof TOOL_GROUPS; label: string; hint: string }> = {
  knowledge_search: { group: 'knowledge', label: '检索知识库', hint: '查找相关原文片段。' },
  knowledge_ask: { group: 'knowledge', label: '知识库问答', hint: '从知识库生成带引用的回答。' },
  list_documents: { group: 'knowledge', label: '查看文档列表', hint: '查找可读文档及其基本信息。' },
  read_document: { group: 'knowledge', label: '阅读文档', hint: '读取指定文档中的原文片段。' },
  memory_search: { group: 'memory', label: '查找记忆', hint: '查询你已保存的事实或偏好。' },
  memory_save: { group: 'memory', label: '保存记忆', hint: '提交保存申请，确认后写入。' },
}
const toolGroups = computed(() => {
  const tools = toolCatalog.value.map((tool) => ({ ...tool, display: TOOL_DETAILS[tool.name] }))
  return Object.entries(TOOL_GROUPS).map(([group, label]) => ({
    label,
    tools: tools.filter((tool) => (tool.display?.group ?? 'other') === group),
  })).filter((group) => group.tools.length)
})

interface FormState {
  name: string
  chatModelId: string
  systemPrompt: string
  answerPolicy: AgentAnswerPolicy
  memoryReadEnabled: boolean
  memoryWriteEnabled: boolean
  memorySuggestionsEnabled: boolean
  kbSelectionMode: AgentKbSelectionMode
  knowledgeBaseIds: number[]
  allowedTools: string[]
}

const editingId = ref<number | null>(null)
const saving = ref(false)
const errorText = ref('')
const form = reactive<FormState>(emptyForm())
const enabledToolCount = computed(() => toolCatalog.value.filter((tool) => form.allowedTools.includes(tool.name)).length)
const unavailableTools = computed(() => toolsLoaded.value
  ? form.allowedTools.filter((name) => !toolCatalog.value.some((tool) => tool.name === name))
  : [])

const dialogVisible = computed({
  /** 将父组件的显示状态暴露给弹窗。 */
  get: () => props.visible,

  /** 向父组件传出弹窗显示状态变更。接收value。 */
  set: (value: boolean) => emit('update:visible', value),
})

const formTitle = computed(() => (editingId.value === null ? '新建 Agent' : '编辑 Agent'))

watch(
  () => props.visible,
  (visible) => {
    if (visible) {
      startCreate()
      void loadToolCatalog()
      void loadChatModels()
    }
  },
)

// 只有 selected 才允许携带知识库 ID，避免同一字段表达两种互相矛盾的意图。
watch(
  () => form.kbSelectionMode,
  (mode) => {
    if (mode !== 'selected') form.knowledgeBaseIds = []
  },
)

/** 以当前工具目录构造可新建的 Agent 表单。返回 FormState。由 startCreate 触发。 */
function emptyForm(): FormState {
  return {
    name: '',
    chatModelId: '',
    systemPrompt: '',
    answerPolicy: 'grounded',
    memoryReadEnabled: true,
    memoryWriteEnabled: true,
    memorySuggestionsEnabled: false,
    kbSelectionMode: 'all',
    knowledgeBaseIds: [],
    allowedTools: toolCatalog.value.map((tool) => tool.name),
  }
}

/** 清空当前编辑 ID 并进入新建配置模式。由 remove、当前模板的 @click 事件 触发。下游调用 emptyForm。 */
function startCreate() {
  editingId.value = null
  errorText.value = ''
  Object.assign(form, emptyForm())
}

/** 把所选 Agent 的模型、检索、记忆和工具配置装入表单。接收profile。由 当前模板的 @click 事件 触发。 */
function startEdit(profile: AgentProfileDTO) {
  editingId.value = profile.id
  errorText.value = ''
  form.name = profile.name
  form.chatModelId = profile.chatModelId ?? ''
  form.systemPrompt = profile.systemPrompt ?? ''
  form.answerPolicy = profile.answerPolicy ?? 'grounded'
  form.memoryReadEnabled = profile.memoryReadEnabled ?? true
  form.memoryWriteEnabled = profile.memoryWriteEnabled ?? true
  form.memorySuggestionsEnabled = profile.memorySuggestionsEnabled ?? false
  form.kbSelectionMode = profile.kbSelectionMode
  form.knowledgeBaseIds = [...profile.knowledgeBaseIds]
  form.allowedTools = [...profile.allowedTools]
}

/** 从 Agent API 读取工具目录并校验当前工具选择。由 当前模板的 @click 事件 触发。下游调用 agent 的 listAgentTools、messageOf。 */
async function loadToolCatalog() {
  const loadId = ++catalogLoadId
  toolsLoading.value = true
  toolsLoaded.value = false
  toolsError.value = ''
  toolCatalog.value = []
  try {
    const tools = await listAgentTools()
    if (loadId !== catalogLoadId) return
    toolCatalog.value = tools
    toolsLoaded.value = true
    if (editingId.value === null) form.allowedTools = tools.map((tool) => tool.name)
  } catch (cause) {
    if (loadId !== catalogLoadId) return
    toolsError.value = messageOf(cause, '工具目录加载失败，请重试。')
  } finally {
    if (loadId === catalogLoadId) toolsLoading.value = false
  }
}

/** 从模型列表筛选能用于 Agent 的对话模型。由 当前模板的 @click 事件 触发。下游调用 models 的 listModels、messageOf。 */
async function loadChatModels() {
  if (modelsLoading.value) return
  modelsLoading.value = true
  modelsError.value = ''
  try { chatModels.value = (await listModels()).filter(model => model.type === 'chat' && model.capabilities.tools) }
  catch (cause) { modelsError.value = messageOf(cause, '聊天模型加载失败。') }
  finally { modelsLoading.value = false }
}

/** 移除已经不在工具目录内的表单选项。由 当前模板的 @click 事件 触发。 */
function removeUnavailableTools() {
  form.allowedTools = form.allowedTools.filter((name) => toolCatalog.value.some((tool) => tool.name === name))
}

/** 为选定的知识库范围生成配置摘要文案。接收mode，返回文本。 */
function modeLabel(mode: AgentKbSelectionMode): string {
  if (mode === 'all') return '全部可读知识库'
  if (mode === 'selected') return '指定知识库'
  return '不检索知识库'
}

/** 从失败对象提取用户可读消息，缺少消息时使用当前操作提示。接收失败原因、兜底文案，返回文本。由 loadToolCatalog、loadChatModels 触发。 */
function messageOf(cause: unknown, fallback: string): string {
  return cause instanceof Error && cause.message ? cause.message : fallback
}

/**
 * 根据当前模式创建或更新 Agent 配置并通知页面刷新。
 * 由 当前模板的 @click 事件 触发。
 * 下游调用 agent 的 createAgentProfile、agent 的 updateAgentProfile。
 */
async function save() {
  if (saving.value) return
  if (!toolsLoaded.value || toolsLoading.value) {
    errorText.value = '请先加载当前可用工具目录。'
    return
  }
  if (unavailableTools.value.length) {
    errorText.value = '请先移除已不可用的工具，再保存配置。'
    return
  }

  const name = form.name.trim()

  if (!name) {
    errorText.value = 'Agent 名称不能为空。'
    return
  }
  if (form.kbSelectionMode === 'selected' && form.knowledgeBaseIds.length === 0) {
    errorText.value = '选择「指定知识库」时至少选择 1 个知识库。'
    return
  }

  const request: AgentProfileRequestDTO = {
    name,
    chatModelId: form.chatModelId || null,
    systemPrompt: form.systemPrompt.trim() || null,
    answerPolicy: form.answerPolicy,
    memoryReadEnabled: form.memoryReadEnabled,
    memoryWriteEnabled: form.memoryWriteEnabled,
    memorySuggestionsEnabled: form.memorySuggestionsEnabled,
    kbSelectionMode: form.kbSelectionMode,
    knowledgeBaseIds: form.kbSelectionMode === 'selected' ? [...form.knowledgeBaseIds] : [],
    allowedTools: [...form.allowedTools],
  }

  saving.value = true
  errorText.value = ''
  try {
    const saved = editingId.value === null
      ? await createAgentProfile(request)
      : await updateAgentProfile(editingId.value, request)
    MessagePlugin.success('Agent 配置已保存')
    emit('changed')
    emit('select', saved.id)
    emit('update:visible', false)
  } catch (cause) {
    errorText.value = messageOf(cause, 'Agent 配置保存失败，请稍后重试。')
  } finally {
    saving.value = false
  }
}

/**
 * 删除指定自建 Agent 并通知上游刷新目录。
 * 接收记录 ID。
 * 由 当前模板的 @confirm 事件 触发。
 * 下游调用 agent 的 deleteAgentProfile、startCreate。
 */
async function remove(id: number) {
  if (saving.value) return
  saving.value = true
  errorText.value = ''
  try {
    await deleteAgentProfile(id)
    MessagePlugin.success('Agent 配置已删除')
    if (editingId.value === id) startCreate()
    emit('changed')
  } catch (cause) {
    errorText.value = messageOf(cause, 'Agent 配置删除失败，请稍后重试。')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <t-dialog
    v-model:visible="dialogVisible"
    header="管理 Agent 配置"
    :footer="false"
    :close-on-overlay-click="false"
    width="min(920px, calc(100vw - 2rem))"
  >
    <div class="profile-layout">
      <aside class="profile-list">
        <t-button block theme="primary" variant="outline" :disabled="saving" @click="startCreate">
          ＋ 新建 Agent
        </t-button>
        <p v-if="!profiles.length" class="list-empty muted">
          还没有保存的 Agent 配置。当前对话仍可使用默认 Agent。
        </p>
        <div
          v-for="profile in profiles"
          :key="profile.id"
          class="profile-item"
          :class="{ active: editingId === profile.id }"
          role="button"
          tabindex="0"
          @click="startEdit(profile)"
          @keydown.enter="startEdit(profile)"
        >
          <div class="profile-item-main">
            <div class="profile-item-head">
              <strong>{{ profile.name }}</strong>
              <t-tag size="small" variant="light-outline">{{ modeLabel(profile.kbSelectionMode) }}</t-tag>
            </div>
            <small class="muted">{{ profile.allowedTools.length }} 个工具</small>
          </div>
          <t-popconfirm content="删除后无法恢复，确定删除？" @confirm="remove(profile.id)">
            <t-button
              variant="text"
              theme="danger"
              size="small"
              :disabled="saving"
              @click.stop
            >
              删除
            </t-button>
          </t-popconfirm>
        </div>
      </aside>

      <section class="profile-form">
        <h3>{{ formTitle }}</h3>

        <label class="field">
          <span>名称</span>
          <t-input
            v-model:value="form.name"
            :maxlength="100"
            :disabled="saving"
            placeholder="例如：合同审阅助手"
          />
        </label>

        <label class="field">
          <span>聊天模型</span>
          <t-select v-model:value="form.chatModelId" :options="chatModelOptions" :disabled="saving || modelsLoading" />
          <small class="field-hint">个人聊天模型可选；留空会使用个人 Agent 用途绑定，未绑定时使用部署默认。修改后需开始新会话。</small>
          <small v-if="modelsError" role="alert">{{ modelsError }}</small>
          <t-button v-if="modelsError" size="small" variant="text" :disabled="saving || modelsLoading" @click="loadChatModels">刷新模型列表</t-button>
        </label>

        <label class="field">
          <span>系统提示词（可选）</span>
          <t-textarea
            v-model:value="form.systemPrompt"
            :maxlength="8000"
            :autosize="{ minRows: 3, maxRows: 7 }"
            :disabled="saving"
            placeholder="留空时使用平台默认的知识助手提示词"
          />
          <small class="field-hint">最多 8000 字符；配置只影响你自己的 Agent。</small>
        </label>

        <div class="field">
          <span>回答策略</span>
          <t-radio-group v-model:value="form.answerPolicy" :disabled="saving">
            <t-radio value="grounded">基于本轮证据回答</t-radio>
            <t-radio value="general">允许通用回答</t-radio>
          </t-radio-group>
          <small class="field-hint">证据策略要求先检索本轮获准片段；无可用知识工具或没有证据时会明确说明。</small>
        </div>

        <div class="field">
          <span>长期记忆</span>
          <t-checkbox v-model:checked="form.memoryReadEnabled" :disabled="saving">允许读取（自动注入与 memory_search）</t-checkbox>
          <t-checkbox v-model:checked="form.memoryWriteEnabled" :disabled="saving">允许经确认后保存</t-checkbox>
          <t-checkbox v-model:checked="form.memorySuggestionsEnabled" :disabled="saving || !form.memoryWriteEnabled">
            自动建议记住回复偏好
          </t-checkbox>
          <small class="field-hint">例如「以后请用中文回答」。建议会出现在对话里，确认后才保存。显式记忆工具仍须选入白名单。修改开关后请开始新会话。</small>
        </div>

        <div class="field">
          <span>知识库范围</span>
          <t-radio-group v-model:value="form.kbSelectionMode" :disabled="saving">
            <t-radio value="all">全部可读知识库</t-radio>
            <t-radio value="selected">指定知识库</t-radio>
            <t-radio value="none">不检索知识库</t-radio>
          </t-radio-group>
          <small class="field-hint">
            配置只是上限；执行时仍会与当前账号的可读范围重新求交，失权时直接拒绝。
          </small>
        </div>

        <label class="field">
          <span>指定知识库</span>
          <t-select
            v-model:value="form.knowledgeBaseIds"
            multiple
            filterable
            clearable
            :disabled="saving || form.kbSelectionMode !== 'selected'"
            placeholder="选择该 Agent 可检索的知识库"
          >
            <t-option
              v-for="kb in knowledgeBases"
              :key="kb.id"
              :value="kb.id"
              :label="kb.name"
            />
          </t-select>
          <small v-if="!knowledgeBases.length" class="field-hint">
            当前没有可选的知识库；可先在「知识库与文档」创建，或在配置中选择默认 Agent。
          </small>
        </label>

        <div class="field">
          <details class="tool-settings">
            <summary>
              允许的工具
              <small v-if="toolsLoaded" class="tool-count">已启用 {{ enabledToolCount }} / {{ toolCatalog.length }}</small>
            </summary>
            <div v-if="toolGroups.length" class="tool-groups">
              <fieldset v-for="group in toolGroups" :key="group.label" :disabled="saving">
                <legend>{{ group.label }}</legend>
                <div class="tool-list">
                  <label v-for="tool in group.tools" :key="tool.name" class="tool-option" :title="tool.name">
                    <input v-model="form.allowedTools" type="checkbox" :value="tool.name" />
                    <span>
                      {{ tool.display?.label ?? tool.name }}
                      <small v-if="tool.requiresApproval" class="tool-approval">需确认</small>
                      <small v-if="tool.display" class="tool-hint">{{ tool.display.hint }}</small>
                    </span>
                  </label>
                </div>
              </fieldset>
              <small class="field-hint">全部取消勾选时，该 Agent 不调用工具。</small>
            </div>
          </details>
          <small v-if="toolsLoading" class="field-hint" role="status">正在加载工具目录…</small>
          <div v-else-if="toolsError" class="catalog-error">
            <small>{{ toolsError }}</small>
            <t-button size="small" variant="text" :disabled="saving" @click="loadToolCatalog">重试</t-button>
          </div>
          <small v-else-if="toolsLoaded && !toolCatalog.length" class="field-hint">当前服务没有可用工具。</small>
          <div v-if="unavailableTools.length" class="catalog-error">
            <small>配置中的工具已不可用：{{ unavailableTools.join('、') }}</small>
            <t-button size="small" variant="text" :disabled="saving" @click="removeUnavailableTools">移除不可用项</t-button>
          </div>
        </div>

        <t-alert v-if="errorText" theme="error" class="form-error">{{ errorText }}</t-alert>

        <div class="form-actions">
          <t-button theme="primary" :loading="saving" :disabled="toolsLoading || !toolsLoaded" @click="save">保存配置</t-button>
          <t-button variant="outline" :disabled="saving" @click="startCreate">清空表单</t-button>
        </div>
      </section>
    </div>
  </t-dialog>
</template>

<style scoped>
.profile-layout {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: var(--space-4);
  min-height: 400px;
}

.profile-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  max-height: 540px;
  padding-right: var(--space-4);
  overflow: auto;
  border-right: 1px solid var(--border);
}

.list-empty {
  margin: var(--space-2) 0;
  border: 1px dashed var(--border);
  border-radius: var(--radius-md);
  padding: var(--space-3);
  color: var(--text-dim);
  font-size: var(--fs-sm);
  line-height: var(--lh-base);
}

.profile-item {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  border-left: var(--rule-thick) solid transparent;
  padding: 8px 10px;
  cursor: pointer;
  transition: background var(--dur-base) var(--ease-out),
    border-color var(--dur-base) var(--ease-out);
}

.profile-item:hover {
  background: var(--bg-panel-2);
}

.profile-item.active {
  border-left-color: var(--accent);
  background: var(--bg-panel-2);
}

.profile-item-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.profile-item-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
}

.profile-item-head strong {
  min-width: 0;
  overflow: hidden;
  font-size: var(--fs-base);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.profile-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  min-width: 0;
}

.profile-form h3 {
  font-size: var(--fs-lg);
}

.form-error {
  text-align: left;
}

.catalog-error {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--text-dim);
}

.tool-settings {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-panel-2);
}

.tool-settings summary {
  padding: var(--space-3);
  cursor: pointer;
  font-weight: var(--fw-semi);
}

.tool-count {
  margin-inline-start: var(--space-2);
  color: var(--text-dim);
  font-weight: var(--fw-normal);
}

.tool-groups { display: grid; gap: var(--space-3); padding: 0 var(--space-3) var(--space-3); }
.tool-groups fieldset { min-width: 0; margin: 0; padding: 0; border: 0; }
.tool-groups legend { margin-block-end: var(--space-2); color: var(--text-dim); font-size: var(--fs-sm); }
.tool-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 210px), 1fr));
  gap: var(--space-2);
}

.tool-option {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  padding: var(--space-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-panel);
  cursor: pointer;
  overflow-wrap: anywhere;
}

.tool-option input { flex: none; margin: 0.2em 0 0; accent-color: var(--accent); }
.tool-hint { display: block; color: var(--text-dim); }
.tool-approval { margin-inline-start: var(--space-1); color: var(--warn); white-space: nowrap; }

@media (max-width: 760px) {
  .profile-layout {
    grid-template-columns: 1fr;
  }

  .profile-list {
    max-height: 220px;
    padding-right: 0;
    padding-bottom: var(--space-3);
    border-right: none;
    border-bottom: 1px solid var(--border);
  }
}
</style>
