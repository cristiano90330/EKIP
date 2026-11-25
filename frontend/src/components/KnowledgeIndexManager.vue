<!-- 查看知识库的活动索引和维护状态，并控制索引重建任务。 -->

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { listKnowledgeBases } from '../api/library'
import { cancelIndexRebuild, getKnowledgeIndex, startIndexRebuild } from '../api/models'
import { currentUserId } from '../api/userIdentity'
import type { KnowledgeBaseDTO } from '../types'
import type { KnowledgeIndexStatus, ManagedModel } from '../modelTypes'
import { USE_MOCK } from '../config'

const props = defineProps<{ models: ManagedModel[] }>()
const knowledgeBases = ref<KnowledgeBaseDTO[]>([])
const knowledgeBaseId = ref<number | null>(null)
const modelId = ref('')
const index = ref<KnowledgeIndexStatus | null>(null)
const confirmed = ref(false)
const loading = ref(false)
const acting = ref(false)
const error = ref('')
const notice = ref('')
const pollingPaused = ref(false)
const selectedKb = computed(() => knowledgeBases.value.find(kb => kb.id === knowledgeBaseId.value))
const isOwner = computed(() => !!selectedKb.value && String(selectedKb.value.creatorId) === currentUserId())
const embeddingModels = computed(() => props.models.filter(model => model.type === 'embedding'))
const taskActive = computed(() => !!index.value?.task && ['WAITING', 'PROCESSING'].includes(index.value.task.status.toUpperCase()))
const progress = computed(() => {
  const task = index.value?.task
  if (!task?.totalChunks) return null
  return Math.min(100, Math.round((task.processedChunks ?? 0) * 100 / task.totalChunks))
})
const statusLabel = computed(() => {
  const status = index.value?.status ?? ''
  return ({
    ready: '索引可用',
    unconfigured: '尚未配置',
    requires_rebuild: '需要显式重建',
  } as Record<string, string>)[status] ?? status
})
let sequence = 0
let disposed = false
let pollTimer: ReturnType<typeof setTimeout> | null = null
let pollFailures = 0

/** 从失败对象提取用户可读消息，缺少消息时使用当前操作提示。接收失败原因。由 loadKnowledgeBases、loadStatus 触发。 */
const messageOf = (cause: unknown) => cause instanceof Error ? cause.message : '读取索引状态失败。'

/** 清除上次计划的索引状态轮询。由 schedulePoll、loadStatus 触发。 */
function stopPoll() { if (pollTimer !== null) clearTimeout(pollTimer); pollTimer = null }

/** 为需要跟踪的索引任务安排下一次读取。由 loadStatus、rebuild 触发。下游调用 stopPoll、loadStatus。 */
function schedulePoll() {
  stopPoll()
  if (disposed || !index.value?.maintenance || pollingPaused.value) return
  pollTimer = setTimeout(() => { void loadStatus(false) }, 2500)
}

/** 读取当前账号可见的知识库，并同步到页面的范围选择。由 当前模板的 @click 事件 触发。下游调用 library 的 listKnowledgeBases、messageOf。 */
async function loadKnowledgeBases() {
  loading.value = true; error.value = ''
  try {
    const values = await listKnowledgeBases()
    if (disposed) return
    knowledgeBases.value = values
    if (!values.some(kb => kb.id === knowledgeBaseId.value)) knowledgeBaseId.value = values[0]?.id ?? null
  } catch (cause) { if (!disposed) error.value = messageOf(cause) }
  finally { loading.value = false }
  if (!disposed && knowledgeBaseId.value !== null) await loadStatus(true)
}

/**
 * 读取当前库的索引状态，并根据维护进度和错误次数管理轮询。
 * 接收manual。
 * 由 schedulePoll、loadKnowledgeBases 触发。
 * 下游调用 stopPoll、models 的 getKnowledgeIndex。
 */
async function loadStatus(manual: boolean) {
  const id = knowledgeBaseId.value
  stopPoll()
  if (id === null || disposed) return

  // 给每次读取编号，切库或重新读取后仅接收最新一次响应。
  const request = ++sequence

  if (manual) { loading.value = true; error.value = ''; pollingPaused.value = false; pollFailures = 0 }

  try {
    const value = await getKnowledgeIndex(id)
    if (disposed || request !== sequence || id !== knowledgeBaseId.value) return
    index.value = value; pollFailures = 0; error.value = ''
    if (!value.maintenance) confirmed.value = false
  } catch (cause) {
    if (disposed || request !== sequence || id !== knowledgeBaseId.value) return
    error.value = messageOf(cause)
    pollFailures++
    if (manual || pollFailures >= 3) pollingPaused.value = true
  } finally {
    if (!disposed && request === sequence) { loading.value = false; schedulePoll() }
  }
}

/** 清空上一个库的索引状态并读取新库的活动索引。由 当前模板的 @change 事件 触发。下游调用 stopPoll、loadStatus。 */
function changeKnowledgeBase() {
  sequence++; stopPoll(); index.value = null; confirmed.value = false
  notice.value = ''; error.value = ''; modelId.value = ''
  void loadStatus(true)
}

/** 提交重建任务并开始跟踪目标索引。由 当前模板的 @click 事件 触发。下游调用 stopPoll、models 的 startIndexRebuild。 */
async function rebuild() {
  const id = knowledgeBaseId.value
  const currentIndex = index.value
  if (id === null || !currentIndex || !confirmed.value || !isOwner.value || !modelId.value || acting.value) return
  acting.value = true; error.value = ''; notice.value = ''; stopPoll()
  try {
    // 同时传入页面看到的活动版本，服务端可识别过期的重建请求。
    const task = await startIndexRebuild(id, modelId.value, currentIndex.activeGenerationId)
    if (disposed || id !== knowledgeBaseId.value) return
    index.value = { ...currentIndex, maintenance: true, task }
    confirmed.value = false; notice.value = '重建任务已创建，知识库进入只读维护。'
    await loadStatus(true)
  } catch (cause) { if (!disposed) error.value = messageOf(cause) }
  finally { acting.value = false; schedulePoll() }
}

/** 提交取消当前重建任务并刷新服务端状态。由 当前模板的 @click 事件 触发。下游调用 stopPoll、models 的 cancelIndexRebuild。 */
async function cancel() {
  const id = knowledgeBaseId.value
  const task = index.value?.task
  if (id === null || !task || !isOwner.value || acting.value) return
  acting.value = true; error.value = ''; notice.value = ''; stopPoll()
  try {
    await cancelIndexRebuild(id, task.id)
    if (disposed || id !== knowledgeBaseId.value) return
    notice.value = '重建已取消，原活动索引保持不变。'
    await loadStatus(true)
  } catch (cause) { if (!disposed) error.value = messageOf(cause) }
  finally { acting.value = false; schedulePoll() }
}

onMounted(loadKnowledgeBases)
onUnmounted(() => { disposed = true; sequence++; stopPoll() })
</script>

<template>
  <section class="panel index-manager">
    <div class="heading"><div><h2>知识库向量索引</h2><p>更换向量模型或维度时，从现有片段正文构建新索引，全量校验后切换。</p></div><button :disabled="loading || acting" @click="knowledgeBases.length ? loadStatus(true) : loadKnowledgeBases()">刷新状态</button></div>
    <p v-if="USE_MOCK" class="notice">演示模式不会执行真实向量化或索引重建。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <p v-if="pollingPaused" class="hint">自动刷新已暂停，请手动刷新状态。停止刷新不会取消服务端任务。</p>
    <label>知识库<select v-model="knowledgeBaseId" :disabled="loading || acting" @change="changeKnowledgeBase"><option :value="null">请选择知识库</option><option v-for="kb in knowledgeBases" :key="kb.id" :value="kb.id">{{ kb.name }}</option></select></label>
    <p v-if="loading" role="status">正在读取索引状态…</p>
    <p v-if="!knowledgeBases.length && !loading" class="muted">尚无可读知识库。</p>
    <template v-if="index">
      <dl><div><dt>状态</dt><dd>{{ statusLabel }}</dd></div><div><dt>维度</dt><dd>{{ index.dimension ?? '尚未建立' }}</dd></div><div><dt>活动 generation</dt><dd>{{ index.activeGenerationId ?? '无可信活动索引' }}</dd></div><div><dt>空间</dt><dd>{{ index.spaceId ?? '未分配' }}</dd></div><div><dt>当前向量模型</dt><dd>{{ models.find(model => model.id === index?.modelId)?.name ?? index.modelId ?? '未配置' }}</dd></div></dl>
      <p v-if="index.maintenance" class="maintenance" role="status">只读维护中：暂时无法上传、删除或重新处理文档。已有有效活动索引继续提供查询。</p>
      <p v-else-if="!index.activeGenerationId" class="hint">历史向量不能自动认领为新空间。首次重建前，语义查询可能不可用。</p>
      <section v-if="index.task" class="task" aria-label="重建进度"><h3>最近重建任务</h3><p>{{ index.task.status }} · {{ index.task.processedChunks ?? 0 }} / {{ index.task.totalChunks ?? 0 }} 个片段<span v-if="progress !== null"> · {{ progress }}%</span></p><progress v-if="progress !== null" :value="progress" max="100" aria-label="重建完成百分比" /><p v-if="index.task.errorMessage" class="error">{{ index.task.errorMessage }}</p><button v-if="isOwner && taskActive" :disabled="acting" @click="cancel">取消重建</button></section>
      <fieldset :disabled="!isOwner || loading || acting || index.maintenance || !!error"><legend>重建索引</legend><label>目标向量模型<select v-model="modelId"><option value="">请选择向量模型</option><option v-for="model in embeddingModels" :key="model.id" :value="model.id">{{ model.name }}{{ model.deploymentDefault ? ' · 部署默认' : ' · 个人' }} · {{ model.dimension }} 维</option></select></label><p v-if="!isOwner" class="muted">只有知识库创建者可以更换索引；共享成员可查看状态。</p><p v-else-if="!embeddingModels.length" class="hint">当前部署未提供向量默认模型。请联系管理员，或添加个人向量模型。</p><p>开始前须无等待或执行中的文档任务。失败或取消会保留原活动索引。重建会调用向量模型，可能消耗资源。</p><label class="confirmation"><input v-model="confirmed" type="checkbox" />我已选择维护窗口，同意重建期间将此知识库设为只读。</label><button class="primary" :disabled="!confirmed || !modelId" @click="rebuild">开始重建并进入只读维护</button></fieldset>
    </template>
  </section>
</template>

<style scoped>
.index-manager { padding: var(--space-5); margin-top: var(--space-5); }
.heading { display: flex; justify-content: space-between; gap: var(--space-4); align-items: start; }
label { display: flex; flex-direction: column; gap: 6px; font-size: var(--fs-sm); max-width: 680px; }
/* 与 SettingsView 编辑区、全站 TDesign 控件保持同一尺寸与圆角：
   此前这里是 42px 高、直角、页面底色的控件，和白卡内其它控件不是一套。 */
input:not([type='checkbox']):not([type='radio']),
select,
button { border: 1px solid var(--border); border-radius: var(--radius-md); background: var(--bg-panel-2); color: var(--text); font: inherit; font-size: var(--fs-md); line-height: 20px; padding: 5px 10px; max-width: 100%; }
button { cursor: pointer; }
button:disabled { opacity: .55; cursor: default; }
button.primary { color: var(--accent); border-color: var(--accent); }
input:focus, select:focus, button:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
p { color: var(--text-dim); }
.error { color: var(--err); }
.hint, .maintenance { color: var(--warn); }
.notice, .maintenance, .task { padding: var(--space-4); border: 1px solid var(--border); border-radius: var(--radius-md); background: var(--bg-panel-2); }
/* 索引状态是 5 个短字段：两列各 600px 会剩下半屏空白，按卡片宽度自适应成 5 列 */
dl { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: var(--space-4); margin: var(--space-5) 0; }
dt { color: var(--text-dim); font-size: var(--fs-sm); }
dd { margin: 4px 0 0; overflow-wrap: anywhere; }
fieldset { margin-top: var(--space-5); padding: var(--space-4); border: 1px solid var(--border); border-radius: var(--radius-md); min-width: 0; }
legend { padding: 0 6px; color: var(--text-dim); font-size: var(--fs-sm); }
.confirmation { display: flex; flex-direction: row; align-items: start; margin-bottom: var(--space-4); }
progress { width: 100%; accent-color: var(--accent); margin-bottom: var(--space-4); }
@media (max-width: 800px) { .heading { flex-direction: column; } dl { grid-template-columns: 1fr; } }
</style>
