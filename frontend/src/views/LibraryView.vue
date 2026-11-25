<!-- 组织知识库切换和文件上传，恢复上传回执并展示文档任务和切片。 -->

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { Drawer as TDrawer, Table as TTable, Upload as TUpload } from 'tdesign-vue-next'
import { useRouter } from 'vue-router'
import axios from 'axios'
import type { PrimaryTableCol } from 'tdesign-vue-next'
import { USE_MOCK } from '../config'
import { DOCUMENT_UPLOAD_ACCEPT, isSupportedDocumentName } from '../documentFormats'
import { currentUserId } from '../api/userIdentity'
import { getKnowledgeIndex } from '../api/models'
import KnowledgeBaseManager from '../components/KnowledgeBaseManager.vue'
import type { KnowledgeIndexStatus } from '../modelTypes'
import {
  cancelDocumentTask,
  createKnowledgeBase,
  deleteDocument,
  getDocumentTask,
  listDocuments,
  listDocumentChunks,
  listKnowledgeBases,
  lookupUploadReceipt,
  retryDocumentTask,
  uploadDocument,
  watchDocumentTask,
  type KnowledgeBaseRemoval,
} from '../api/library'
import type { DocumentChunkDTO, DocumentDTO, DocumentTaskDTO, DocumentUploadReceiptDTO, KnowledgeBaseDTO } from '../types'

const router = useRouter()
const knowledgeBases = ref<KnowledgeBaseDTO[]>([])
const selectedKnowledgeBaseId = ref<number | null>(null)
const documents = ref<DocumentDTO[]>([])
const tasks = ref<Record<number, DocumentTaskDTO>>({})

const knowledgeBaseName = ref('')
const knowledgeBaseDescription = ref('')
const createLibraryDialog = ref<HTMLDialogElement | null>(null)
const selectedFile = ref<File | null>(null)

const loadingKnowledgeBases = ref(true)
const loadingDocuments = ref(false)
const creatingKnowledgeBase = ref(false)
const uploading = ref(false)
const uploadPercent = ref<number | null>(null)
const uploadLoadedBytes = ref(0)
const uploadTotalBytes = ref<number | null>(null)
const uploadPhase = ref<'idle' | 'hashing' | 'checking' | 'transferring' | 'confirming' | 'uncertain'>('idle')
const uploadIntentKnown = ref(false)
const retryingDocuments = ref<Record<number, boolean>>({})
const cancellingDocuments = ref<Record<number, boolean>>({})
const deletingDocuments = ref<Record<number, boolean>>({})
const taskWatchMessages = ref<Record<number, string>>({})
const taskActionErrors = ref<Record<number, string>>({})
const documentActionErrors = ref<Record<number, string>>({})
const pageError = ref('')
const knowledgeIndex = ref<KnowledgeIndexStatus | null>(null)
const indexError = ref('')
const createError = ref('')
const uploadError = ref('')
const notice = ref('')

let uploadController: AbortController | null = null
const taskStreamControllers = new Map<number, AbortController>()
const taskStreamAttempted = new Set<number>()
const taskRecoveryDelays = [1000, 2000, 4000, 8000, 8000] as const

interface UploadIntent {
  userId: string
  knowledgeBaseId: number
  requestKey: string
  fingerprint: string
  fileName: string
  fileSize: number
  lastModified: number
  createdAt: string
}

const selectedKnowledgeBase = computed(() =>
  knowledgeBases.value.find((item) => item.id === selectedKnowledgeBaseId.value) ?? null,
)
const signedInUserId = computed(() => currentUserId())
const canManageSelectedKnowledgeBase = computed(() =>
  !!selectedKnowledgeBase.value && !!signedInUserId.value
  && String(selectedKnowledgeBase.value.creatorId) === signedInUserId.value,
)
const readyDocumentCount = computed(() =>
  documents.value.filter((document) => tasks.value[document.id]?.status === 'SUCCESS').length,
)

const processingCount = computed(() => documents.value.filter((document) => {
  const task = tasks.value[document.id]
  return task ? isActiveTask(task.status) : document.status === 0 || document.status === 1
}).length)

const documentColumns: PrimaryTableCol[] = [
  { colKey: 'file', title: '文档', minWidth: 320 },
  { colKey: 'status', title: '处理状态', width: 130 },
  { colKey: 'operation', title: '操作', width: 230, align: 'right' },
]

/** 从失败对象提取用户可读消息，缺少消息时使用当前操作提示。接收错误对象、兜底文案，返回文本。由 loadKnowledgeBases、loadDocuments 触发。 */
function messageOf(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback
}

/** 取消当前列表关联的任务订阅并清理已订阅标记。由 loadKnowledgeBases、loadDocuments 触发。 */
function clearTaskStreams() {
  for (const controller of taskStreamControllers.values()) controller.abort()
  taskStreamControllers.clear()
  taskStreamAttempted.clear()
  taskWatchMessages.value = {}
  taskActionErrors.value = {}
  documentActionErrors.value = {}
}

/** 读取当前账号可见的知识库，并同步到页面的范围选择。接收preferredId。下游调用 library 的 listKnowledgeBases、loadDocuments。 */
async function loadKnowledgeBases(preferredId?: number) {
  loadingKnowledgeBases.value = true
  pageError.value = ''
  try {
    const items = await listKnowledgeBases()
    knowledgeBases.value = items
    const wantedId = preferredId ?? selectedKnowledgeBaseId.value
    const selected = items.find((item) => item.id === wantedId) ?? items[0] ?? null
    selectedKnowledgeBaseId.value = selected?.id ?? null
    if (selected) await loadDocuments(selected.id)
    else {
      clearTaskStreams()
      documents.value = []
      tasks.value = {}
    }
  } catch (error) {
    pageError.value = messageOf(error, '读取知识库失败，请稍后重试。')
  } finally {
    loadingKnowledgeBases.value = false
  }
}

/**
 * 加载指定知识库的文档页，忽略切换知识库后返回的旧结果。
 * 接收知识库 ID。
 * 由 loadKnowledgeBases、onKnowledgeBaseChange 触发。
 * 下游调用 clearTaskStreams、models 的 getKnowledgeIndex。
 */
async function loadDocuments(knowledgeBaseId: number | null) {
  clearTaskStreams()
  knowledgeIndex.value = null
  indexError.value = ''
  pageError.value = ''
  tasks.value = {}
  if (knowledgeBaseId == null) {
    documents.value = []
    return
  }

  loadingDocuments.value = true
  try {
    void getKnowledgeIndex(knowledgeBaseId).then(index => {
      if (selectedKnowledgeBaseId.value === knowledgeBaseId) knowledgeIndex.value = index
    }).catch(error => {
      if (selectedKnowledgeBaseId.value === knowledgeBaseId) indexError.value = messageOf(error, '索引状态暂不可用。')
    })
    // 仅把仍属于当前知识库的响应交给列表，旧请求可能在切库后才返回。
    const page = await listDocuments(knowledgeBaseId)
    if (selectedKnowledgeBaseId.value !== knowledgeBaseId) return
    documents.value = page.records ?? []
    await loadTaskStatuses(knowledgeBaseId, documents.value)
  } catch (error) {
    if (selectedKnowledgeBaseId.value === knowledgeBaseId) {
      documents.value = []
      pageError.value = messageOf(error, '读取文档列表失败，请稍后重试。')
    }
  } finally {
    loadingDocuments.value = false
  }
}

/**
 * 为当前文档补齐任务快照，对仍在处理的任务建立订阅。
 * 接收知识库 ID、items。
 * 由 loadDocuments 触发。
 * 下游调用 library 的 getDocumentTask、messageOf。
 */
async function loadTaskStatuses(knowledgeBaseId: number, items: DocumentDTO[]) {
  const entries = await Promise.all(items.map(async (document) => {
    // DocumentStatus.COMPLETED is code 2; avoid one task request for every completed file.
    if (document.status === 2) {
      return [document.id, { documentId: document.id, status: 'SUCCESS', errorMessage: null }] as const
    }
    try {
      return [document.id, await getDocumentTask(document.id)] as const
    } catch (error) {
      return [document.id, {
        documentId: document.id,
        status: 'UNKNOWN',
        errorMessage: messageOf(error, '暂时无法读取处理状态'),
      }] as const
    }
  }))

  if (selectedKnowledgeBaseId.value !== knowledgeBaseId) return
  tasks.value = Object.fromEntries(entries) as Record<number, DocumentTaskDTO>
  for (const task of Object.values(tasks.value)) {
    if (task.status === 'WAITING' || task.status === 'PROCESSING') {
      void startTaskStream(knowledgeBaseId, task.documentId)
    }
  }
}

/** 按手动重试轮次、尝试号及状态进度合并任务快照，终态停止订阅。接收知识库 ID、task。由 readTaskSnapshot、startTaskStream 触发。 */
function applyTask(knowledgeBaseId: number, task: DocumentTaskDTO) {
  if (selectedKnowledgeBaseId.value !== knowledgeBaseId) return

  const current = tasks.value[task.documentId]

  if (current) {
    if (task.taskId != null && current.taskId != null && task.taskId < current.taskId) return
    const rank: Record<string, number> = { UNKNOWN: -1, WAITING: 0, PROCESSING: 1, SUCCESS: 2, FAILED: 2, CANCELLED: 2 }
    const currentManualRetryCount = current.manualRetryCount ?? 0
    const nextManualRetryCount = task.manualRetryCount ?? 0
    const currentAttempt = current.attempt ?? current.retryCount ?? 0
    const nextAttempt = task.attempt ?? task.retryCount ?? 0
    const newManualRetry = nextManualRetryCount > currentManualRetryCount
    const rejectedRetryDispatch = task.status === 'WAITING'
      && task.errorCode === 'TASK_DISPATCH_REJECTED'
      && current.status === 'PROCESSING'
      && nextManualRetryCount === currentManualRetryCount
      && nextAttempt === currentAttempt

    // Manual retry starts a new generation even when the cumulative attempt count is unchanged;
    // ignore older generations, then order events by cumulative claims within each generation.
    // A scheduler rejection is the one valid same-attempt PROCESSING→WAITING transition.
    if (nextManualRetryCount < currentManualRetryCount
      || (!newManualRetry && (nextAttempt < currentAttempt
        || (nextAttempt === currentAttempt && (rank[task.status] ?? -1) < (rank[current.status] ?? -1)
          && !rejectedRetryDispatch)))) return
  }
  tasks.value = { ...tasks.value, [task.documentId]: task }
  if (task.status !== 'WAITING' && task.status !== 'PROCESSING') {
    taskStreamControllers.get(task.documentId)?.abort()
    taskStreamControllers.delete(task.documentId)
    taskWatchMessages.value = { ...taskWatchMessages.value, [task.documentId]: '' }
  }
}

/**
 * 读取任务最新快照并应用到当前知识库，读取失败返回空值。
 * 接收知识库 ID、文档 ID。
 * 由 startTaskStream、recoverTaskAfterStreamDrop 触发。
 * 下游调用 library 的 getDocumentTask、applyTask。
 */
async function readTaskSnapshot(knowledgeBaseId: number, documentId: number): Promise<DocumentTaskDTO | null> {
  if (selectedKnowledgeBaseId.value !== knowledgeBaseId) return null
  try {
    const task = await getDocumentTask(documentId)
    applyTask(knowledgeBaseId, task)
    return task
  } catch {
    return null
  }
}

/**
 * 为当前文档注册单个任务状态订阅，流断开后交由快照核对。
 * 接收知识库 ID、文档 ID。
 * 由 loadTaskStatuses、retryTask 触发。
 * 下游调用 library 的 watchDocumentTask、applyTask。
 */
async function startTaskStream(knowledgeBaseId: number, documentId: number) {
  if (USE_MOCK || taskStreamAttempted.has(documentId) || selectedKnowledgeBaseId.value !== knowledgeBaseId) return
  // 同一文档只启动一次订阅，断流后的读取由补偿路径接管。
  taskStreamAttempted.add(documentId)
  const controller = new AbortController()
  taskStreamControllers.set(documentId, controller)
  try {
    await watchDocumentTask(
      documentId,
      controller.signal,
      (task) => applyTask(knowledgeBaseId, task),
      async () => { await readTaskSnapshot(knowledgeBaseId, documentId) },
    ).catch(() => undefined)
    if (!controller.signal.aborted) await recoverTaskAfterStreamDrop(knowledgeBaseId, documentId, controller.signal)
  } finally {
    if (taskStreamControllers.get(documentId) === controller) taskStreamControllers.delete(documentId)
  }
}

/**
 * 即时核对一次任务状态，并在限定时间内尝试读取后续快照。
 * 接收知识库 ID、文档 ID、取消信号。
 * 由 startTaskStream 触发。
 * 下游调用 readTaskSnapshot。
 */
async function recoverTaskAfterStreamDrop(knowledgeBaseId: number, documentId: number, signal: AbortSignal) {
  taskWatchMessages.value = { ...taskWatchMessages.value, [documentId]: '实时更新已断开，正在核对处理状态…' }
  let task = await readTaskSnapshot(knowledgeBaseId, documentId)

  if (task && task.status !== 'WAITING' && task.status !== 'PROCESSING') {
    taskWatchMessages.value = { ...taskWatchMessages.value, [documentId]: '' }
    return
  }
  for (const delayMs of taskRecoveryDelays) {
    await new Promise((resolve) => setTimeout(resolve, delayMs))
    if (signal.aborted || selectedKnowledgeBaseId.value !== knowledgeBaseId) return
    task = await readTaskSnapshot(knowledgeBaseId, documentId)
    if (task && task.status !== 'WAITING' && task.status !== 'PROCESSING') {
      taskWatchMessages.value = { ...taskWatchMessages.value, [documentId]: '' }
      return
    }
  }
  if (!signal.aborted && selectedKnowledgeBaseId.value === knowledgeBaseId) {
    taskWatchMessages.value = { ...taskWatchMessages.value, [documentId]: '已完成有限次状态核对，仍在处理中；可手动刷新查看最新状态。' }
  }
}

/** 清除旧库提示并加载新库文档及待核对的上传意图。由 当前模板的 @change 事件 触发。下游调用 loadDocuments、inspectSelectedUploadIntent。 */
function onKnowledgeBaseChange() {
  notice.value = ''
  uploadIntentKnown.value = false
  if (selectedKnowledgeBaseId.value != null) {
    void loadDocuments(selectedKnowledgeBaseId.value)
    void inspectSelectedUploadIntent()
  }
}

/** 根据登录账号和库所有者生成知识库选项文字。接收item，返回文本。 */
function knowledgeBaseLabel(item: KnowledgeBaseDTO): string {
  return signedInUserId.value && String(item.creatorId) !== signedInUserId.value
    ? item.name + ' · 与我共享'
    : item.name
}

/** 用管理弹窗返回的名称和描述更新当前知识库列表。接收记录 ID、名称、说明。由 当前模板的 @updated 事件 触发。 */
function onLibraryUpdated(id: number, name: string, description: string) {
  knowledgeBases.value = knowledgeBases.value.map(item => item.id === id ? { ...item, name, description } : item)
  notice.value = '知识库已更新。'
}

/**
 * 移除已删除的知识库，必要时切换到剩余库并提示待清理文件。
 * 接收记录 ID、名称、处理结果。
 * 由 当前模板的 @removed 事件 触发。
 * 下游调用 loadDocuments、inspectSelectedUploadIntent。
 */
async function onLibraryRemoved(id: number, name: string, result: KnowledgeBaseRemoval) {
  knowledgeBases.value = knowledgeBases.value.filter(item => item.id !== id)
  if (selectedKnowledgeBaseId.value === id) {
    selectedKnowledgeBaseId.value = knowledgeBases.value[0]?.id ?? null
    uploadIntentKnown.value = false
    await loadDocuments(selectedKnowledgeBaseId.value)
    await inspectSelectedUploadIntent()
  }
  notice.value = result.filesPendingCleanup
    ? `知识库“${name}”已删除，${result.filesPendingCleanup} 个原文件待运维清理。`
    : `知识库“${name}”已删除。`
}

/** 清除旧建库错误并打开创建弹窗。由 当前模板的 @click 事件 触发。 */
function openCreateLibrary() {
  createError.value = ''
  createLibraryDialog.value?.showModal()
}

/**
 * 提交知识库名称和说明，根据结果刷新列表与选择项。
 * 由 当前模板的 @submit.prevent 事件 触发。
 * 下游调用 library 的 createKnowledgeBase、library 的 listKnowledgeBases。
 */
async function submitKnowledgeBase() {
  if (creatingKnowledgeBase.value) return

  const name = knowledgeBaseName.value.trim()

  if (!name) {
    createError.value = '请填写知识库名称。'
    return
  }

  creatingKnowledgeBase.value = true
  createError.value = ''
  notice.value = ''
  const previousIds = new Set(knowledgeBases.value.map((item) => item.id))
  let saved = false

  try {
    await createKnowledgeBase(name, knowledgeBaseDescription.value.trim())
    saved = true
    const items = await listKnowledgeBases()
    knowledgeBases.value = items
    const created = items.find((item) => !previousIds.has(item.id) && item.name === name)
    if (!created) {
      notice.value = '知识库已创建，请刷新页面查看。'
      return
    }

    selectedKnowledgeBaseId.value = created.id
    uploadIntentKnown.value = false
    void loadDocuments(created.id)
    notice.value = `知识库“${created.name}”已创建，可以上传文档了。`
  } catch (error) {
    if (saved) notice.value = '知识库已创建，列表暂时无法读取，请刷新页面查看。'
    else createError.value = messageOf(error, '创建知识库失败，请稍后重试。')
  } finally {
    creatingKnowledgeBase.value = false
    if (saved) {
      knowledgeBaseName.value = ''
      knowledgeBaseDescription.value = ''
      createLibraryDialog.value?.close()
    }
  }
}

// t-upload（theme=custom、auto-upload=false）选中文件后的回调。
// 组件只负责弹出文件选择器并给出原生 File，后续扩展名校验、指纹、断点续传全部沿用原逻辑。
/**
 * 取出选择器返回的文件，校验格式并同步待核对上传状态。
 * 接收选择器文件列表。
 * 由 当前模板的 @select-change 事件 触发。
 * 下游调用 documentFormats 的 isSupportedDocumentName、inspectSelectedUploadIntent。
 */
function onFileSelect(files: File[]) {
  uploadError.value = ''
  notice.value = ''
  uploadIntentKnown.value = false
  const file = files[0] ?? null

  if (!file) {
    selectedFile.value = null
    return
  }

  if (!isSupportedDocumentName(file.name)) {
    selectedFile.value = null
    uploadError.value = '不支持该格式，请选择 PDF、Markdown、Office、EPUB、XMind、TXT、CSV 或 JSON。'
    return
  }
  selectedFile.value = file
  void inspectSelectedUploadIntent()
}

/**
 * 用账号、知识库和内容指纹为本地上传意图构造隔离的键。
 * 接收账号 ID、知识库 ID、fingerprint，返回文本。
 * 由 readUploadIntent、persistUploadIntent 触发。
 */
function uploadIntentStorageKey(userId: string, knowledgeBaseId: number, fingerprint: string): string {
  return `ekip.upload.intent.v1:${encodeURIComponent(userId)}:${knowledgeBaseId}:${fingerprint}`
}

/** 为所选文件计算浏览器端内容指纹，供复用同一上传意图。接收浏览器文件，返回文本。由 inspectSelectedUploadIntent、submitUpload 触发。 */
async function fileFingerprint(file: File): Promise<string> {
  if (!globalThis.crypto?.subtle) throw new Error('secure hashing unavailable')

  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('')
}

/**
 * 按当前账号和文件指纹恢复可用的本地上传意图。
 * 接收账号 ID、知识库 ID、fingerprint，返回 UploadIntent | null。
 * 由 inspectSelectedUploadIntent、submitUpload 触发。
 * 下游调用 uploadIntentStorageKey。
 */
function readUploadIntent(userId: string, knowledgeBaseId: number, fingerprint: string): UploadIntent | null {
  const key = uploadIntentStorageKey(userId, knowledgeBaseId, fingerprint)
  const saved = localStorage.getItem(key)
  if (!saved) return null

  try {
    const intent = JSON.parse(saved) as UploadIntent
    return intent.userId === userId && intent.knowledgeBaseId === knowledgeBaseId
      && intent.fingerprint === fingerprint && intent.requestKey ? intent : null
  } catch {
    return null
  }
}

/**
 * 保存请求键和文件信息以便响应丢失后查询服务端回执。
 * 接收intent。
 * 由 submitUpload 触发。
 * 下游调用 uploadIntentStorageKey。
 */
function persistUploadIntent(intent: UploadIntent) {
  const key = uploadIntentStorageKey(intent.userId, intent.knowledgeBaseId, intent.fingerprint)
  localStorage.setItem(key, JSON.stringify(intent))
}

/**
 * 清除已经对账完成的本地上传意图。
 * 接收intent。
 * 由 acceptUploadReceipt 触发。
 * 下游调用 uploadIntentStorageKey。
 */
function removeUploadIntent(intent: UploadIntent) {
  try {
    localStorage.removeItem(uploadIntentStorageKey(intent.userId, intent.knowledgeBaseId, intent.fingerprint))
  } catch {
    // A confirmed server receipt remains authoritative; a leftover key only causes a safe lookup.
  }
}

/** 为新上传意图创建独立的请求键。返回文本。由 submitUpload 触发。 */
function newRequestKey(): string {
  if (crypto.randomUUID) return crypto.randomUUID()

  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/**
 * 识别当前所选文件是否已有可查询的上传回执。
 * 由 onKnowledgeBaseChange、onLibraryRemoved 触发。
 * 下游调用 fileFingerprint、readUploadIntent。
 */
async function inspectSelectedUploadIntent() {
  const file = selectedFile.value
  const knowledgeBaseId = selectedKnowledgeBaseId.value
  const userId = signedInUserId.value

  if (!file || knowledgeBaseId == null || !userId) {
    uploadIntentKnown.value = false
    return
  }

  try {
    const fingerprint = await fileFingerprint(file)
    if (selectedFile.value !== file || selectedKnowledgeBaseId.value !== knowledgeBaseId) return
    uploadIntentKnown.value = !!readUploadIntent(userId, knowledgeBaseId, fingerprint)
  } catch {
    uploadIntentKnown.value = false
  }
}

/** 将回执中的受理、处理或复用状态转换为页面文字。接收receipt，返回文本。由 acceptUploadReceipt 触发。 */
function receiptStatusLabel(receipt: DocumentUploadReceiptDTO): string {
  switch (receipt.taskStatus) {
    case 'WAITING': return '等待解析'
    case 'PROCESSING': return '正在解析'
    case 'SUCCESS': return '解析完成'
    case 'FAILED': return '解析失败'
    case 'CANCELLED': return '已取消解析'
    default: return '已接收'
  }
}

/**
 * 用已确认的回执刷新列表及任务状态，并结束本地上传意图。
 * 接收知识库 ID、intent、receipt。
 * 由 submitUpload 触发。
 * 下游调用 removeUploadIntent、loadDocuments。
 */
async function acceptUploadReceipt(knowledgeBaseId: number, intent: UploadIntent, receipt: DocumentUploadReceiptDTO) {
  removeUploadIntent(intent)
  uploadIntentKnown.value = false
  uploadPhase.value = 'idle'
  uploadPercent.value = 100
  selectedFile.value = null
  // t-upload 以受控空列表（:files="[]"）挂载，接收成功后组件内部不会残留已选文件
  await loadDocuments(knowledgeBaseId)
  notice.value = `${receipt.reused ? '已核对到已有上传' : '文档已接收'}：文档 #${receipt.documentId}，任务 #${receipt.taskId}（${receiptStatusLabel(receipt)}）。`
}

/** 辨认可能已到达后端的传输失败，供上传页先查回执。接收错误对象，返回布尔判定。由 submitUpload 触发。 */
function isUncertainUploadFailure(error: unknown): boolean {
  if (!axios.isAxiosError(error)) return error instanceof DOMException && error.name === 'AbortError'
  // A gateway timeout may arrive as HTTP 408 after the upload reached the backend.
  return !error.response || error.response.status === 408 || error.response.status >= 500 || axios.isCancel(error)
}

/**
 * 将上传失败映射为能指导重新选择、核对或重试的交互提示。
 * 接收错误对象，返回文本。
 * 由 submitUpload 触发。
 * 下游调用 messageOf。
 */
function uploadErrorMessage(error: unknown): string {
  const status = axios.isAxiosError(error) ? error.response?.status : undefined
  if (status === 400 || status === 422) return '文件未通过上传校验，请确认文件格式和内容。'
  if (status === 403) return '当前账号无权向此知识库上传文档。'
  if (status === 409) return messageOf(error, '知识库正在维护或请求键存在冲突，请刷新状态后重试。')
  if (status === 413) return '文件超过允许的大小上限。'
  if (status === 415) return '当前文件格式暂不支持。'
  return '上传状态尚未确认。系统会先按原请求键核对；确认未接收后可以安全地同键重发。'
}

/**
 * 检查上传条件，优先核对历史回执，再按原请求键发起上传。
 * 由 当前模板的 @submit.prevent 事件 触发。
 * 下游调用 fileFingerprint、readUploadIntent。
 */
async function submitUpload() {
  const knowledgeBaseId = selectedKnowledgeBaseId.value
  const file = selectedFile.value

  if (knowledgeBaseId == null) {
    uploadError.value = '请先创建或选择一个知识库。'
    return
  }
  if (!file) {
    uploadError.value = '请先选择要上传的文档。'
    return
  }
  if (!canManageSelectedKnowledgeBase.value) {
    uploadError.value = '只有知识库所有者可以上传文档。'
    return
  }
  if (knowledgeIndex.value?.maintenance) {
    uploadError.value = '知识库正在重建索引，暂时只读。请等待完成或在模型管理中取消重建。'
    return
  }

  uploading.value = true
  uploadError.value = ''
  notice.value = ''
  uploadPercent.value = null
  uploadLoadedBytes.value = 0
  uploadTotalBytes.value = null
  uploadPhase.value = 'hashing'
  try {
    const userId = signedInUserId.value
    if (!userId) throw new Error('identity unavailable')
    const fingerprint = await fileFingerprint(file)
    let intent = readUploadIntent(userId, knowledgeBaseId, fingerprint)
    const recovered = !!intent
    if (!intent) {
      intent = {
        userId,
        knowledgeBaseId,
        requestKey: newRequestKey(),
        fingerprint,
        fileName: file.name,
        fileSize: file.size,
        lastModified: file.lastModified,
        createdAt: new Date().toISOString(),
      }
      try {
        persistUploadIntent(intent)
      } catch {
        throw new Error('intent persistence failed')
      }
    }

    // A persisted key signals an uncertain previous request. Check its receipt before sending bytes again.
    if (recovered) {
      uploadPhase.value = 'checking'
      const lookup = await lookupUploadReceipt(knowledgeBaseId, intent.requestKey)
      if (lookup.found && lookup.receipt) {
        await acceptUploadReceipt(knowledgeBaseId, intent, lookup.receipt)
        return
      }
    }

    uploadController = new AbortController()
    uploadPhase.value = 'transferring'
    // 上传失败可能发生在受理之后，原请求键由本地上传意图保留，供之后查原回执。
    const receipt = await uploadDocument(knowledgeBaseId, file, intent.requestKey, {
      signal: uploadController.signal,

      /** 把当前传输的字节数和百分比同步到上传表单。接收{ loaded, total, percent }。由 submitUpload 触发。 */
      onProgress: ({ loaded, total, percent }) => {
        uploadLoadedBytes.value = loaded
        uploadTotalBytes.value = total
        if (percent != null) {
          uploadPercent.value = percent
          if (percent >= 100) uploadPhase.value = 'confirming'
        }
      },
    })
    await acceptUploadReceipt(knowledgeBaseId, intent, receipt)
  } catch (error) {
    if (uploadPhase.value === 'hashing' && error instanceof Error && error.message === 'identity unavailable') {
      uploadError.value = '无法读取当前账号身份，请重新登录后再上传。'
    } else if (uploadPhase.value === 'hashing' && error instanceof Error && error.message === 'intent persistence failed') {
      uploadError.value = '浏览器无法保存本次上传请求键，未发送文件。请启用本地存储后重试。'
    } else if (uploadPhase.value === 'hashing') {
      uploadError.value = '无法计算文件指纹，本次文件尚未发送。请在安全连接下重试。'
    } else if (isUncertainUploadFailure(error)) {
      const userId = signedInUserId.value
      if (!userId) {
        uploadError.value = uploadErrorMessage(error)
      } else {
        try {
          const fingerprint = await fileFingerprint(file)
          const intent = readUploadIntent(userId, knowledgeBaseId, fingerprint)
          if (intent) {
            uploadPhase.value = 'checking'
            const lookup = await lookupUploadReceipt(knowledgeBaseId, intent.requestKey)
            if (lookup.found && lookup.receipt) {
              await acceptUploadReceipt(knowledgeBaseId, intent, lookup.receipt)
            } else {
              uploadIntentKnown.value = true
              uploadPhase.value = 'uncertain'
              uploadError.value = '服务尚未确认是否接收。请求键已保存；点击“核对并继续”会先查收据，再用同一个请求键重发。'
            }
          } else {
            uploadError.value = uploadErrorMessage(error)
          }
        } catch {
          uploadPhase.value = 'uncertain'
          uploadIntentKnown.value = true
          uploadError.value = '暂时无法核对服务收据。请求键已保存，请稍后点击“核对并继续”。'
        }
      }
    } else {
      uploadError.value = uploadErrorMessage(error)
    }
  } finally {
    uploadController = null
    uploading.value = false
  }
}

/** 中止当前浏览器上传请求并保留用于对账的本地信息。由 当前模板的 @click 事件 触发。 */
function cancelUpload() {
  uploadController?.abort()
}

/** 判断任务是否处于等待或处理状态。接收状态，返回布尔判定。由 canCancelTask 触发。 */
function isActiveTask(status?: string): boolean {
  return status === 'WAITING' || status === 'PROCESSING'
}

/**
 * 按页面管理权、任务标识和活动状态决定是否显示取消操作。
 * 接收文档 ID，返回布尔判定。
 * 由 cancelTask 触发。
 * 下游调用 isActiveTask。
 */
function canCancelTask(documentId: number): boolean {
  const task = tasks.value[documentId]
  return !USE_MOCK && canManageSelectedKnowledgeBase.value && task?.taskId != null && isActiveTask(task.status)
}

/**
 * 取消指定尝试所属的任务，响应不明确时补读当前快照。
 * 接收文档 ID、任务 ID。
 * 由 当前模板的 @confirm 事件 触发。
 * 下游调用 canCancelTask、library 的 cancelDocumentTask。
 */
async function cancelTask(documentId: number, taskId: number) {
  const knowledgeBaseId = selectedKnowledgeBaseId.value
  if (knowledgeBaseId == null || !canCancelTask(documentId) || tasks.value[documentId]?.taskId !== taskId
      || cancellingDocuments.value[documentId] || deletingDocuments.value[documentId]
      || retryingDocuments.value[documentId] || knowledgeIndex.value?.maintenance) return
  cancellingDocuments.value = { ...cancellingDocuments.value, [documentId]: true }
  taskActionErrors.value = { ...taskActionErrors.value, [documentId]: '' }
  try {
    const task = await cancelDocumentTask(documentId, taskId)
    if (selectedKnowledgeBaseId.value !== knowledgeBaseId) return
    applyTask(knowledgeBaseId, task)
    if (task.status !== 'CANCELLED') {
      taskActionErrors.value = { ...taskActionErrors.value, [documentId]: '任务已经结束，已显示最新状态。' }
    }
  } catch {
    const snapshot = await readTaskSnapshot(knowledgeBaseId, documentId)
    if (selectedKnowledgeBaseId.value === knowledgeBaseId && snapshot?.status !== 'CANCELLED') {
      taskActionErrors.value = { ...taskActionErrors.value, [documentId]: '取消结果未确认，请刷新任务状态后重试。' }
    }
  } finally {
    const next = { ...cancellingDocuments.value }
    delete next[documentId]
    cancellingDocuments.value = next
  }
}

/**
 * 提交解析重试并按照返回任务切换状态订阅。
 * 接收文档 ID。
 * 由 当前模板的 @click 事件 触发。
 * 下游调用 library 的 retryDocumentTask、applyTask。
 */
async function retryTask(documentId: number) {
  const knowledgeBaseId = selectedKnowledgeBaseId.value
  if (!canManageSelectedKnowledgeBase.value || knowledgeBaseId == null || retryingDocuments.value[documentId]
      || cancellingDocuments.value[documentId] || deletingDocuments.value[documentId] || knowledgeIndex.value?.maintenance) return
  retryingDocuments.value = { ...retryingDocuments.value, [documentId]: true }
  taskActionErrors.value = { ...taskActionErrors.value, [documentId]: '' }
  try {
    const task = await retryDocumentTask(documentId)
    if (selectedKnowledgeBaseId.value === knowledgeBaseId) {
      taskStreamControllers.get(documentId)?.abort()
      taskStreamControllers.delete(documentId)
      taskStreamAttempted.delete(documentId)
      applyTask(knowledgeBaseId, task)
      if (task.status === 'WAITING' || task.status === 'PROCESSING') {
        void startTaskStream(knowledgeBaseId, documentId)
      }
    }
  } catch {
    taskActionErrors.value = { ...taskActionErrors.value, [documentId]: '重试没有确认成功。请刷新任务状态后再决定是否重试。' }
  } finally {
    const next = { ...retryingDocuments.value }
    delete next[documentId]
    retryingDocuments.value = next
  }
}

/**
 * 按权限、维护状态和响应确定性提示文档删除失败。
 * 接收错误对象，返回文本。
 * 由 removeDocument 触发。
 * 下游调用 messageOf。
 */
function deleteErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const status = error.response?.status
    if (status === 403) return '只有知识库所有者可以删除文档。'
    if (status === 404) return '文档已不存在，请刷新列表。'
    if (status === 409) return '知识库正在维护，暂时无法删除。刷新状态后重试。'
    if (status == null || status >= 500) return '删除结果暂未确认，请刷新文档列表核对后再操作。'
  }
  return messageOf(error, '删除失败，请刷新文档列表后重试。')
}

/**
 * 删除指定文档并清理该文档的列表、切片抽屉和状态订阅。
 * 接收document。
 * 由 当前模板的 @confirm 事件 触发。
 * 下游调用 library 的 deleteDocument、deleteErrorMessage。
 */
async function removeDocument(document: DocumentDTO) {
  const knowledgeBaseId = selectedKnowledgeBaseId.value
  if (knowledgeBaseId == null || !canManageSelectedKnowledgeBase.value
      || knowledgeIndex.value?.maintenance || deletingDocuments.value[document.id]
      || retryingDocuments.value[document.id] || cancellingDocuments.value[document.id]) return

  deletingDocuments.value = { ...deletingDocuments.value, [document.id]: true }
  documentActionErrors.value = { ...documentActionErrors.value, [document.id]: '' }
  notice.value = ''
  try {
    await deleteDocument(document.id)
    if (selectedKnowledgeBaseId.value !== knowledgeBaseId) return

    taskStreamControllers.get(document.id)?.abort()
    taskStreamControllers.delete(document.id)
    taskStreamAttempted.delete(document.id)
    documents.value = documents.value.filter((item) => item.id !== document.id)

    const nextTasks = { ...tasks.value }
    delete nextTasks[document.id]
    tasks.value = nextTasks
    const nextActionErrors = { ...taskActionErrors.value }
    delete nextActionErrors[document.id]
    taskActionErrors.value = nextActionErrors
    const nextDocumentErrors = { ...documentActionErrors.value }
    delete nextDocumentErrors[document.id]
    documentActionErrors.value = nextDocumentErrors

    if (chunkDocument.value?.id === document.id) {
      chunkRequest++
      chunkDrawerVisible.value = false
      chunkDocument.value = null
      chunkLoading.value = false
      chunks.value = []
      chunkTotal.value = 0
      chunkError.value = ''
    }
    notice.value = `文档「${document.fileName}」及其关联记录已删除。`
  } catch (error) {
    if (selectedKnowledgeBaseId.value === knowledgeBaseId) {
      documentActionErrors.value = {
        ...documentActionErrors.value,
        [document.id]: deleteErrorMessage(error),
      }
    }
  } finally {
    const nextDeleting = { ...deletingDocuments.value }
    delete nextDeleting[document.id]
    deletingDocuments.value = nextDeleting
  }
}

/** 将文档的任务快照转换为状态文案。接收文档 ID，返回文本。 */
function taskLabel(documentId: number): string {
  switch (tasks.value[documentId]?.status) {
    case 'WAITING': return '等待处理'
    case 'PROCESSING': return '解析中'
    case 'SUCCESS': return '可用于问答'
    case 'FAILED': return '解析失败'
    case 'CANCELLED': return '已取消'
    default: return '状态未知'
  }
}

/** 根据当前任务状态为文档行选择状态标签颜色。接收文档 ID，返回 'success' | 'danger' | 'warning' | 'default'。 */
function taskTagTheme(documentId: number): 'success' | 'danger' | 'warning' | 'default' {
  switch (tasks.value[documentId]?.status) {
    case 'SUCCESS': return 'success'
    case 'FAILED': return 'danger'
    case 'WAITING':
    case 'PROCESSING': return 'warning'
    default: return 'default'
  }
}

/** 读取当前任务适合在文档行展示的错误信息。接收文档 ID，返回文本。 */
function taskError(documentId: number): string {
  const task = tasks.value[documentId]
  if (!task || task.status === 'CANCELLED') return ''
  return task.status === 'FAILED' || task.status === 'UNKNOWN' || task.lastErrorMessage
    ? task.errorMessage ?? task.lastErrorMessage ?? ''
    : ''
}

/** 从任务快照取得用于定位失败的稳定原因码。接收文档 ID，返回文本。 */
function taskErrorCode(documentId: number): string {
  const task = tasks.value[documentId]
  if (task?.status === 'CANCELLED') return ''
  return task?.errorCode ?? task?.lastErrorCode ?? ''
}

// ---------- 按文档分页浏览切片 ----------
const chunkDrawerVisible = ref(false)
const chunkDocument = ref<DocumentDTO | null>(null)
const chunkLoading = ref(false)
const chunks = ref<DocumentChunkDTO[]>([])
const chunkError = ref('')
const chunkPage = ref(1)
const chunkTotal = ref(0)
const chunkPageSize = 20
let chunkRequest = 0

/**
 * 分页读取当前抽屉文档的切片，切换对象后丢弃旧请求的响应。
 * 接收页码。
 * 由 openChunks、当前模板的 @click 事件 触发。
 * 下游调用 library 的 listDocumentChunks、messageOf。
 */
async function loadChunks(page: number) {
  const document = chunkDocument.value
  if (!document) return

  const request = ++chunkRequest
  chunkPage.value = page
  chunkLoading.value = true
  chunkError.value = ''
  chunks.value = []
  try {
    const result = await listDocumentChunks(document.id, page, chunkPageSize)
    if (request !== chunkRequest || !chunkDrawerVisible.value) return
    chunks.value = result.records
    chunkPage.value = result.current
    chunkTotal.value = result.total
  } catch (error) {
    if (request === chunkRequest && chunkDrawerVisible.value) {
      chunkError.value = messageOf(error, '读取切片失败，请稍后重试。')
    }
  } finally {
    if (request === chunkRequest) chunkLoading.value = false
  }
}

/**
 * 切换切片抽屉到指定文档并加载第一页。
 * 接收document。
 * 由 当前模板的 @click 事件 触发。
 * 下游调用 loadChunks。
 */
async function openChunks(document: DocumentDTO) {
  chunkDocument.value = document
  chunkDrawerVisible.value = true
  chunkTotal.value = 0
  await loadChunks(1)
}

/** 根据文件字节数选择适合文档列表的容量单位。接收bytes，返回文本。 */
function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

onMounted(() => {
  void loadKnowledgeBases()
})

onUnmounted(() => {
  chunkRequest++
  clearTaskStreams()
  uploadController?.abort()
})
</script>

<template>
  <section class="library-page">
    <header class="page-head">
      <div>
        <h1>知识库与文档</h1>
        <p>选择知识库，管理共享、上传文档或查看解析状态。</p>
      </div>
    </header>

    <div v-if="USE_MOCK" class="mode-note">开发模拟数据 · 上传和解析状态仅用于界面演示</div>
    <t-alert v-if="pageError" theme="error" :message="pageError" class="page-alert" role="alert" />
    <t-alert v-if="notice" theme="success" :message="notice" class="page-alert" role="status" />
    <t-alert v-if="knowledgeIndex?.maintenance" theme="warning" class="page-alert" role="status">知识库正在重建索引，暂时只读。已有有效索引继续提供查询；上传、删除和重新处理须等待重建结束。</t-alert>
    <t-alert v-else-if="knowledgeIndex && !knowledgeIndex.activeGenerationId" theme="info" class="page-alert">此知识库尚无可信向量索引。已有文档请在模型管理中显式重建；新空知识库首次入库使用创建者的向量用途绑定。</t-alert>
    <section class="panel library-context" aria-labelledby="library-context-title">
      <div class="library-picker">
        <label for="current-library" class="field">
          <span>当前知识库</span>
          <select id="current-library" v-model="selectedKnowledgeBaseId"
            :disabled="uploading || loadingKnowledgeBases || knowledgeBases.length === 0"
            @change="onKnowledgeBaseChange">
            <option v-if="!knowledgeBases.length" :value="null">
              {{ loadingKnowledgeBases ? '正在读取知识库…' : '暂无可用知识库' }}
            </option>
            <option v-for="item in knowledgeBases" :key="item.id" :value="item.id">{{ knowledgeBaseLabel(item) }}</option>
          </select>
        </label>
        <t-button theme="primary" variant="outline" :disabled="loadingKnowledgeBases || uploading" @click="openCreateLibrary">
          新建知识库
        </t-button>
      </div>

      <div class="library-context-heading">
        <div>
          <h2 id="library-context-title">{{ selectedKnowledgeBase?.name ?? '知识库' }}</h2>
          <p v-if="selectedKnowledgeBase?.description">{{ selectedKnowledgeBase.description }}</p>
        </div>
        <t-button v-if="selectedKnowledgeBase" theme="primary" :disabled="readyDocumentCount === 0"
          @click="router.push({ path: '/ask', query: { knowledgeBaseId: String(selectedKnowledgeBaseId) } })">
          用此库问答
        </t-button>
      </div>
      <KnowledgeBaseManager
        v-if="selectedKnowledgeBase && signedInUserId && !USE_MOCK"
        :key="selectedKnowledgeBase.id"
        :knowledge-base="selectedKnowledgeBase"
        :is-owner="canManageSelectedKnowledgeBase"
        :deletion-blocked="uploading || knowledgeIndex?.maintenance === true"
        @updated="onLibraryUpdated"
        @removed="onLibraryRemoved"
      />
    </section>

    <dialog ref="createLibraryDialog" class="panel create-library-dialog" aria-labelledby="create-library-title"
      @cancel="creatingKnowledgeBase && $event.preventDefault()">
      <div class="panel-heading"><h2 id="create-library-title">新建知识库</h2></div>
      <form class="form-stack" @submit.prevent="submitKnowledgeBase">
        <label class="field">
          <span>知识库名称</span>
          <t-input v-model:value="knowledgeBaseName" :maxlength="100" autofocus
            placeholder="例如：人力资源制度" :disabled="creatingKnowledgeBase" />
        </label>
        <label class="field">
          <span>说明 <em>选填</em></span>
          <t-textarea v-model:value="knowledgeBaseDescription" :maxlength="500" :autosize="{ minRows: 3, maxRows: 6 }"
            placeholder="简单描述这组资料的主题" :disabled="creatingKnowledgeBase" />
        </label>
        <p v-if="createError" class="inline-error" role="alert">{{ createError }}</p>
        <div class="form-actions">
          <t-button type="button" variant="outline" :disabled="creatingKnowledgeBase" @click="createLibraryDialog?.close()">取消</t-button>
          <t-button type="submit" theme="primary" :loading="creatingKnowledgeBase"
            :disabled="creatingKnowledgeBase || !knowledgeBaseName.trim()">创建知识库</t-button>
        </div>
      </form>
    </dialog>

    <section v-if="canManageSelectedKnowledgeBase" class="panel upload-panel" aria-labelledby="upload-title">
      <div class="panel-heading"><h2 id="upload-title">上传文档</h2></div>
      <form class="form-stack" @submit.prevent="submitUpload">

          <!--
            t-upload 仅承担「选择文件」交互：theme=custom 自定义触发器、auto-upload=false 禁止组件自动上传、
            受控 :files="[]" 保证不残留列表。真正的指纹生成、请求键核对、断点续传仍在 submitUpload() 中原样执行。
          -->
          <t-upload
            theme="custom"
            :auto-upload="false"
            :files="[]"
            :accept="DOCUMENT_UPLOAD_ACCEPT"
            :disabled="uploading || knowledgeIndex?.maintenance"
            @select-change="onFileSelect"
          >
            <div class="file-picker" :class="{ 'file-selected': selectedFile }">
              <span class="file-icon" aria-hidden="true">
                <AddIcon />
              </span>
              <span class="file-copy">
                <strong>{{ selectedFile?.name ?? '选择一个文档' }}</strong>
                <small>{{ selectedFile ? formatSize(selectedFile.size) : 'PDF、Markdown、Word 或 Excel' }}</small>
              </span>
              <span class="choose-file">浏览</span>
            </div>
          </t-upload>

          <p class="field-hint">上传后自动解析，状态会显示在下方的文档列表。</p>
          <p v-if="uploadError" class="inline-error" role="alert">{{ uploadError }}</p>
          <div v-if="uploading" class="upload-progress" role="status" aria-live="polite">
            <div class="progress-track"><span :class="{ indeterminate: uploadPercent == null }" :style="{ width: `${uploadPercent ?? 0}%` }" /></div>
            <span v-if="uploadPhase === 'confirming'">文件字节已传完（100%），正在等待服务确认接收…</span>
            <span v-else-if="uploadPhase === 'checking'">正在按原请求键核对上传收据…</span>
            <span v-else-if="uploadPhase === 'hashing'">正在生成文件指纹并保存本次上传请求键…</span>
            <span v-else-if="uploadPercent != null">已传输 {{ uploadPercent }}%，等待服务确认接收。</span>
            <span v-else-if="uploadLoadedBytes > 0">已发送 {{ formatSize(uploadLoadedBytes) }}，等待服务确认接收。</span>
            <span v-else>正在准备文件并上传…</span>
          </div>
          <div class="upload-actions">
            <t-button
              theme="primary"
              variant="outline"
              type="submit"
              :disabled="uploading || !selectedFile || selectedKnowledgeBaseId == null || !canManageSelectedKnowledgeBase || knowledgeIndex?.maintenance"
            >
              {{ uploading ? '正在处理…' : uploadIntentKnown ? '核对并继续（沿用原请求键）' : '上传并开始解析' }}
            </t-button>
            <t-button
              v-if="uploading && (uploadPhase === 'transferring' || uploadPhase === 'confirming')"
              theme="default"
              variant="outline"
              size="small"
              type="button"
              @click="cancelUpload"
            >
              取消并核对状态
            </t-button>
          </div>
      </form>
    </section>

    <section class="panel document-panel">
      <p v-if="knowledgeIndex?.status === 'ready'" class="index-summary" role="status">
        活动索引已就绪 · {{ knowledgeIndex.dimension }} 维
      </p>
      <p v-else-if="knowledgeIndex?.status === 'requires_rebuild'" class="index-summary">当前知识库需要重建索引。</p>
      <p v-else-if="knowledgeIndex?.status === 'unconfigured'" class="index-summary">活动索引等待首次文档入库。</p>
      <p v-if="indexError" class="inline-error" role="alert">{{ indexError }}</p>
      <div class="document-heading">
        <div>
          <h2>文档与解析状态</h2>
          <p v-if="!canManageSelectedKnowledgeBase && selectedKnowledgeBase">共享库可查看文档和解析状态。</p>
        </div>
        <div class="document-tools">
          <span class="document-count">{{ documents.length }} 份文档</span>
          <t-button
            theme="default"
            variant="outline"
            size="small"
            :disabled="loadingDocuments || selectedKnowledgeBaseId == null"
            @click="selectedKnowledgeBaseId != null && loadDocuments(selectedKnowledgeBaseId)"
          >
            {{ loadingDocuments ? '刷新中…' : '刷新状态' }}
          </t-button>
        </div>
      </div>

      <dl v-if="documents.length" class="stat-strip">
        <div><dt>可用于问答</dt><dd class="ok">{{ readyDocumentCount }}</dd></div>
        <div><dt>排队或解析中</dt><dd :class="{ warn: processingCount > 0 }">{{ processingCount }}</dd></div>
      </dl>

      <div v-if="loadingKnowledgeBases || loadingDocuments" class="empty-state">正在读取知识库与文档…</div>
      <div v-else-if="knowledgeBases.length === 0" class="empty-state">
        <strong>还没有知识库</strong>
        <span>先在上方创建一个知识库，再上传第一份文档。</span>
      </div>
      <div v-else-if="documents.length === 0" class="empty-state">
        <strong>这个知识库还没有文档</strong>
        <span v-if="canManageSelectedKnowledgeBase">上传文档后，这里会显示处理进度。</span>
      </div>
      <t-table
        v-else
        row-key="id"
        size="small"
        :data="documents"
        :columns="documentColumns"
        bordered
        hover
      >
        <template #file="{ row }">
          <div class="document-cell">
            <div class="document-icon" aria-hidden="true">DOC</div>
            <div class="document-main">
              <strong :title="row.fileName">{{ row.fileName }}</strong>
              <span>{{ formatSize(row.fileSize) }} · 文档 #{{ row.id }}</span>
              <small v-if="taskError(row.id)" class="task-error">{{ taskError(row.id) }}</small>
              <small v-if="taskErrorCode(row.id)" class="task-error-code">错误码：{{ taskErrorCode(row.id) }}</small>
              <small v-if="taskWatchMessages[row.id]" class="task-watch-note">{{ taskWatchMessages[row.id] }}</small>
              <small v-if="taskActionErrors[row.id]" class="task-error">{{ taskActionErrors[row.id] }}</small>
              <small v-if="documentActionErrors[row.id]" class="task-error" role="alert">{{ documentActionErrors[row.id] }}</small>
            </div>
          </div>
        </template>
        <template #status="{ row }">
          <t-tag :theme="taskTagTheme(row.id)" variant="light-outline">
            {{ taskLabel(row.id) }}
          </t-tag>
        </template>
        <template #operation="{ row }">
          <!-- 解析成功（可用于问答）的文档可查看切片；只读操作，不对查看者再做所有者限制 -->
          <div class="document-operations">
            <t-button
              v-if="tasks[row.id]?.status === 'SUCCESS'"
              theme="primary"
              variant="outline"
              size="small"
              @click="openChunks(row)"
            >
              查看切片
            </t-button>
            <t-popconfirm v-if="canCancelTask(row.id)" :key="tasks[row.id].taskId"
              content="取消本次解析？原文件会保留，可以重新解析。"
              @confirm="cancelTask(row.id, tasks[row.id].taskId!)">
              <t-button variant="outline" size="small"
                :disabled="cancellingDocuments[row.id] || deletingDocuments[row.id] || retryingDocuments[row.id] || knowledgeIndex?.maintenance">
                {{ cancellingDocuments[row.id] ? '取消中…' : '取消解析' }}
              </t-button>
            </t-popconfirm>
            <t-button
              v-if="canManageSelectedKnowledgeBase && ['FAILED', 'CANCELLED'].includes(tasks[row.id]?.status)"
              theme="default"
              variant="outline"
              size="small"
              :disabled="retryingDocuments[row.id] || deletingDocuments[row.id] || cancellingDocuments[row.id] || knowledgeIndex?.maintenance"
              @click="retryTask(row.id)"
            >
              {{ retryingDocuments[row.id] ? '提交中…' : tasks[row.id]?.status === 'CANCELLED' ? '重新解析' : '重试解析' }}
            </t-button>
            <t-popconfirm
              v-if="canManageSelectedKnowledgeBase"
              :content="`删除「${row.fileName}」及其切片和处理记录？此操作无法撤销。`"
              @confirm="removeDocument(row)"
            >
              <t-button
                type="button"
                theme="danger"
                variant="text"
                size="small"
                :disabled="deletingDocuments[row.id] || retryingDocuments[row.id] || cancellingDocuments[row.id] || knowledgeIndex?.maintenance"
              >
                {{ deletingDocuments[row.id] ? '删除中…' : '删除' }}
              </t-button>
            </t-popconfirm>
            <span v-if="!canManageSelectedKnowledgeBase && tasks[row.id]?.status !== 'SUCCESS'" class="muted">—</span>
          </div>
        </template>
      </t-table>
    </section>

    <!-- 切片按文档读取，分页与错误状态由抽屉管理。 -->
    <t-drawer
      v-model:visible="chunkDrawerVisible"
      placement="right"
      size="480px"
      :header="false"
      :close-on-overlay-click="true"
      destroy-on-close
    >
      <div class="chunk-drawer">
        <div class="chunk-drawer-head">
          <h2>文档切片</h2>
          <p :title="chunkDocument?.fileName">
            {{ chunkDocument?.fileName }} · 文档 #{{ chunkDocument?.id }}
          </p>
          <t-tag v-if="USE_MOCK" theme="warning" variant="light-outline" size="small">
            开发模拟切片数据
          </t-tag>
        </div>

        <div v-if="chunkLoading" class="chunk-state">正在读取切片列表…</div>

        <div v-else-if="chunkError" class="chunk-state">
          <t-alert theme="error" :message="chunkError" class="chunk-alert" />
          <t-button variant="outline" @click="loadChunks(chunkPage)">重试读取</t-button>
        </div>

        <template v-else>
          <p class="chunk-summary muted">共 {{ chunkTotal }} 个切片 · 第 {{ chunkPage }} 页</p>
          <p v-if="!chunks.length" class="chunk-state">该文档暂无切片。</p>
          <ol class="chunk-list">
            <li v-for="item in chunks" :key="item.id" class="chunk-card">
              <div class="chunk-card-head">
                <t-tag theme="primary" variant="light-outline" size="small">#{{ item.chunkIndex }}</t-tag>
                <strong>{{ item.headingPath ?? `切片 ${item.chunkIndex}` }}</strong>
                <span class="muted">{{ item.charCount }} 字</span>
                <span v-if="item.pageNo != null" class="muted">第 {{ item.pageNo }} 页</span>
              </div>
              <p class="chunk-content">{{ item.content }}</p>
            </li>
          </ol>
          <div v-if="chunkTotal > chunkPageSize" class="chunk-pagination">
            <t-button variant="outline" :disabled="chunkLoading || chunkPage <= 1" @click="loadChunks(chunkPage - 1)">上一页</t-button>
            <t-button variant="outline" :disabled="chunkLoading || chunkPage * chunkPageSize >= chunkTotal" @click="loadChunks(chunkPage + 1)">下一页</t-button>
          </div>
        </template>
      </div>
    </t-drawer>
  </section>
</template>

<style scoped>
.index-summary {
  margin: 0 0 var(--space-3);
  color: var(--text-dim);
  font-size: var(--fs-sm);
}

.chunk-pagination {
  display: flex;
  gap: var(--space-3);
  margin-top: var(--space-4);
}

/* 页面容器：内容区限宽居中，避免 2560 宽屏下表单与表格拉成超长行 */
.library-page {
  width: min(100%, var(--content-max));
  margin: 0 auto;
  padding-bottom: var(--space-8);
}

.library-context,
.upload-panel { margin-bottom: var(--space-4); }

.library-context {
  display: grid;
  gap: var(--space-3);
  padding: var(--space-5);
}

.library-picker,
.library-context-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--space-3);
}

.library-picker .field { flex: 1 1 14rem; }
.library-picker select {
  width: min(100%, 28rem);
  padding: var(--space-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-panel-2);
  color: var(--text);
  font: inherit;
}

.create-library-dialog {
  width: min(480px, calc(100% - 2rem));
  max-height: calc(100dvh - 2rem);
  overflow: auto;
  overscroll-behavior: contain;
  border-color: var(--border);
  background: var(--bg-elevated);
  color: var(--text);
  box-shadow: var(--shadow-dialog);
}
.create-library-dialog::backdrop { background: rgb(18 20 28 / 52%); }
.create-library-dialog .form-actions { flex-wrap: wrap; justify-content: flex-end; }
.library-context-heading > div { flex: 1 1 14rem; min-width: 0; overflow-wrap: anywhere; }
.library-context-heading h2, .library-context-heading p { margin: 0; }
.library-context-heading p { margin-top: var(--space-1); color: var(--text-dim); }

.stat-strip {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-4);
  margin: 0 0 var(--space-3);
}

.stat-strip > div {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
}

.stat-strip dt {
  color: var(--text-faint);
  font-family: var(--font-mono);
  font-size: var(--fs-micro);
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.stat-strip dd {
  margin: 0;
  color: var(--text);
  font-size: var(--fs-base);
  font-weight: var(--fw-bold);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
  line-height: 1;
}

.stat-strip dd.ok {
  color: var(--ok);
}

.stat-strip dd.warn {
  color: var(--warn);
}

.mode-note {
  margin-bottom: var(--space-4);
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  padding: 10px 14px;
  background: var(--warn-soft);
  color: var(--warn);
  font-size: var(--fs-sm);
}

.inline-error {
  margin: 0;
  color: var(--err);
  font-size: var(--fs-sm);
  line-height: 1.5;
}

.upload-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.upload-progress {
  display: grid;
  gap: 6px;
  color: var(--text-dim);
  font-size: var(--fs-micro);
  line-height: 1.5;
}

.progress-track {
  height: 4px;
  overflow: hidden;
  border-radius: var(--radius-full);
  background: var(--bg-inset-deep);
}

.progress-track span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--accent);
  transition: width 150ms var(--ease-out);
}

.progress-track span.indeterminate {
  width: 35% !important;
  animation: upload-progress 1.25s ease-in-out infinite alternate;
}

.file-picker {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  width: 100%;
  min-height: 68px;
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-lg);
  padding: var(--space-3) var(--space-4);
  background: var(--bg-panel-2);
  cursor: pointer;
  transition: border-color var(--dur-base) var(--ease-out),
    background var(--dur-base) var(--ease-out);
}

.file-picker:hover,
.file-picker.file-selected {
  border-color: var(--accent);
  background: var(--accent-soft);
}

/* t-upload 的 custom 触发器默认是行内容器，这里拉满以贴合文件选择卡片宽度 */
:deep(.t-upload) {
  display: block;
}

:deep(.t-upload__trigger) {
  display: block;
  cursor: pointer;
}

.file-icon {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  flex: none;
  border-radius: var(--radius-md);
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 18px;
}

.file-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.file-copy strong {
  overflow: hidden;
  color: var(--text);
  font-size: var(--fs-sm);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-copy small {
  color: var(--text-dim);
  font-size: var(--fs-micro);
}

.choose-file {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 5px 10px;
  color: var(--text-dim);
  font-size: var(--fs-micro);
}

/* ---------- 文档列表 ---------- */
.document-heading,
.document-tools {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
}

.document-heading {
  align-items: flex-start;
  margin-bottom: var(--space-3);
}

.document-heading h2 {
  margin: 2px 0 4px;
  font-size: var(--fs-lg);
}

.document-heading p {
  color: var(--text-dim);
  font-size: var(--fs-sm);
}

.document-tools {
  flex: none;
}

.document-count {
  color: var(--text-dim);
  font-size: var(--fs-sm);
  white-space: nowrap;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 40px 12px;
  color: var(--text-dim);
  font-size: var(--fs-sm);
  text-align: center;
}

.empty-state strong {
  color: var(--text);
  font-size: var(--fs-base);
}

/* 文档名列：图标 + 文件名 / 元信息 / 错误提示 */
.document-cell {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  min-width: 0;
  padding: 2px 0;
}

.document-icon {
  display: grid;
  place-items: center;
  width: 34px;
  height: 38px;
  flex: none;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-inset);
  color: var(--accent);
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.5px;
}

.document-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.document-main strong {
  overflow: hidden;
  color: var(--text);
  font-size: var(--fs-sm);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.document-main span {
  color: var(--text-dim);
  font-size: var(--fs-micro);
}

.task-error,
.task-watch-note,
.task-error-code {
  font-size: var(--fs-micro);
  line-height: 1.45;
}

.document-operations {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-2);
  white-space: nowrap;
}

.task-error {
  color: var(--err);
}

.task-error-code {
  color: var(--err-dim);
  font-family: var(--font-mono);
}

.task-watch-note {
  color: var(--warn);
}

@keyframes upload-progress {
  from {
    transform: translateX(-45%);
  }
  to {
    transform: translateX(210%);
  }
}

/* ---------- 切片抽屉 ---------- */
.chunk-drawer {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: 2px;
}

.chunk-drawer-head {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.chunk-drawer-head h2 {
  font-size: var(--fs-lg);
}

.chunk-drawer-head p {
  overflow: hidden;
  color: var(--text-dim);
  font-size: var(--fs-sm);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chunk-state {
  padding: 24px 0;
  color: var(--text-dim);
  font-size: var(--fs-sm);
  text-align: center;
}

.chunk-alert {
  text-align: left;
}

.chunk-summary {
  font-size: var(--fs-sm);
}

.chunk-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.chunk-card {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 10px 12px;
  background: var(--bg-inset);
}

.chunk-card-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: 6px;
}

.chunk-card-head strong {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: var(--fs-base);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chunk-card-head .muted {
  flex: none;
  font-size: var(--fs-micro);
}

.chunk-content {
  margin: 0;
  color: var(--text-body);
  font-size: var(--fs-base);
  line-height: var(--lh-read);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

@media (max-width: 600px) {
  .page-head,
  .document-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .document-tools {
    width: 100%;
    justify-content: space-between;
  }
}
</style>
