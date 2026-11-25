/** 封装知识库管理、文档上传与回执查询、切片浏览和任务状态订阅的页面 API。 */

import { USE_MOCK } from '../config'
import { http } from './http'
import {
  mockCreateKnowledgeBase,
  mockDeleteDocument,
  mockGetDocumentTask,
  mockListDocuments,
  mockListDocumentChunks,
  mockListKnowledgeBases,
  mockLookupUploadReceipt,
  mockUploadDocument,
} from './mock'
import type { DocumentChunkPageDTO, DocumentPageDTO, DocumentTaskDTO, KnowledgeBaseDTO } from '../types'
import type { DocumentUploadReceiptDTO, UploadReceiptLookupDTO } from '../types'
import { useAuth } from '../stores/auth'
import { parseJsonFrame, readSseFrames } from './sse'

/**
 * 读取当前账号可见的自有和共享知识库。
 * 返回 KnowledgeBaseDTO[]。
 * 由 KnowledgeIndexManager.vue 的 loadKnowledgeBases、RetrievalEvaluationPanel.vue 的 loadLibraries 触发。
 * 下游调用 KnowledgeBaseController 的对应路由、mock 的 mockListKnowledgeBases。
 */
export const listKnowledgeBases = (): Promise<KnowledgeBaseDTO[]> =>
  USE_MOCK ? mockListKnowledgeBases() : http.get<KnowledgeBaseDTO[], KnowledgeBaseDTO[]>('/knowledge-base/list')

/**
 * 提交名称和描述创建知识库。
 * 接收名称、说明，返回文本。
 * 由 LibraryView.vue 的 submitKnowledgeBase 触发。
 * 下游调用 KnowledgeBaseController 的对应路由、mock 的 mockCreateKnowledgeBase。
 */
export const createKnowledgeBase = (name: string, description: string): Promise<string> =>
  USE_MOCK
    ? mockCreateKnowledgeBase(name, description)
    : http.post<string, string>('/knowledge-base', { name, description })

export interface KnowledgeBaseRemoval {
  filesPendingCleanup: number
}

export interface KnowledgeBaseMember {
  userId: number
  username: string
}

export interface KnowledgeBaseUser {
  id: number
  username: string
}

/**
 * 更新指定知识库的名称和描述。
 * 接收知识库 ID、名称、说明，返回文本。
 * 由 KnowledgeBaseManager.vue 的 saveDetails 触发。
 * 下游调用 KnowledgeBaseController 的对应路由。
 */
export const updateKnowledgeBase = (id: number, name: string, description: string): Promise<string> =>
  http.put<string, string>(`/knowledge-base/${id}`, { name, description })

/**
 * 删除整库并取得待清理原文件数量。
 * 接收知识库 ID，返回 KnowledgeBaseRemoval。
 * 由 KnowledgeBaseManager.vue 的 removeLibrary 触发。
 * 下游调用 KnowledgeBaseController 的对应路由。
 */
export const deleteKnowledgeBase = (id: number): Promise<KnowledgeBaseRemoval> =>
  http.delete<KnowledgeBaseRemoval, KnowledgeBaseRemoval>(`/knowledge-base/${id}`, { timeout: 180_000 })

/**
 * 读取指定知识库的共享成员。
 * 接收知识库 ID，返回 KnowledgeBaseMember[]。
 * 由 KnowledgeBaseManager.vue 的 loadMembers 触发。
 * 下游调用 KnowledgeBaseController 的对应路由。
 */
export const listKnowledgeBaseMembers = (id: number): Promise<KnowledgeBaseMember[]> =>
  http.get<KnowledgeBaseMember[], KnowledgeBaseMember[]>(`/knowledge-base/${id}/members`)

/**
 * 按用户名查询可添加的成员候选。
 * 接收用户名，返回 KnowledgeBaseUser[]。
 * 由 KnowledgeBaseManager.vue 的 search 触发。
 * 下游调用 KnowledgeBaseController 的对应路由。
 */
export const searchKnowledgeBaseUsers = (username: string): Promise<KnowledgeBaseUser[]> =>
  http.get<KnowledgeBaseUser[], KnowledgeBaseUser[]>('/user/search', { params: { username } })

/** 向指定账号授予知识库成员权限。接收知识库 ID、账号 ID，返回文本。下游调用 KnowledgeBaseController 的对应路由。 */
export const addKnowledgeBaseMember = (id: number, userId: number): Promise<string> =>
  http.post<string, string>(`/knowledge-base/${id}/members`, { userId })

/** 移除指定账号的知识库成员权限。接收知识库 ID、账号 ID，返回文本。下游调用 KnowledgeBaseController 的对应路由。 */
export const removeKnowledgeBaseMember = (id: number, userId: number): Promise<string> =>
  http.delete<string, string>(`/knowledge-base/${id}/members/${userId}`)

/**
 * 读取指定知识库的分页文档条目。
 * 接收知识库 ID、页码、页大小，返回文档分页。
 * 由 RetrievalEvaluationPanel.vue 的 loadDocumentPage、LibraryView.vue 的 loadDocuments 触发。
 * 下游调用 DocumentController.page、mock 的 mockListDocuments。
 */
export const listDocuments = (knowledgeBaseId: number, page = 1, size = 100): Promise<DocumentPageDTO> =>
  USE_MOCK
    ? mockListDocuments(knowledgeBaseId)
    : http.get<DocumentPageDTO, DocumentPageDTO>('/document/list', {
        params: { knowledgeBaseId, page, size },
      })

/**
 * 提交指定文档的删除请求。
 * 接收文档 ID，返回文本。
 * 由 LibraryView.vue 的 removeDocument 触发。
 * 下游调用 DocumentController.delete、mock 的 mockDeleteDocument。
 */
export const deleteDocument = (documentId: number): Promise<string> => USE_MOCK
  ? mockDeleteDocument(documentId)
  : http.delete<string, string>(`/document/${documentId}`)

/**
 * 按页读取文档的切片正文与来源元数据。
 * 接收文档 ID、页码、页大小，返回含正文和元数据的切片分页。
 * 由 RetrievalEvaluationPanel.vue 的 loadChunkPage、LibraryView.vue 的 loadChunks 触发。
 * 下游调用 DocumentController.chunks、mock 的 mockListDocumentChunks。
 */
export const listDocumentChunks = (documentId: number, page = 1, size = 20): Promise<DocumentChunkPageDTO> =>
  USE_MOCK
    ? mockListDocumentChunks(documentId, page, size)
    : http.get<DocumentChunkPageDTO, DocumentChunkPageDTO>(`/document/${documentId}/chunks`, { params: { page, size } })

/** POST /document/upload?knowledgeBaseId= with multipart file */
export interface UploadProgress {
  loaded: number
  total: number | null
  percent: number | null
}

/**
 * 将文件按指定请求键上传到知识库，返回可用于后续状态追踪的受理回执。
 * 接收知识库 ID、浏览器文件、上传请求键、可选的取消信号和进度回调。
 * 由 LibraryView.vue 的 submitUpload 触发。
 * 下游调用 DocumentController.upload、mock 的 mockUploadDocument。
 */
export const uploadDocument = (
  knowledgeBaseId: number,
  file: File,
  requestKey: string,
  options: { signal?: AbortSignal; onProgress?: (progress: UploadProgress) => void } = {},
): Promise<DocumentUploadReceiptDTO> => {
  if (USE_MOCK) return mockUploadDocument(knowledgeBaseId, file, requestKey)

  const form = new FormData()
  form.append('file', file, file.name)
  // 将上游保存的请求键随文件提交，使网络断开后的重发仍可与原回执对账。
  return http.post<DocumentUploadReceiptDTO, DocumentUploadReceiptDTO>('/document/upload', form, {
    params: { knowledgeBaseId },
    headers: { 'Idempotency-Key': requestKey },
    timeout: 180_000,
    signal: options.signal,

    /** 把传输字节数转换为页面进度，总大小未知时保留空百分比。接收{ loaded, total }。由 uploadDocument 触发。 */
    onUploadProgress: ({ loaded, total }) => {
      options.onProgress?.({ loaded, total: total ?? null, percent: total ? Math.min(100, Math.round(loaded * 100 / total)) : null })
    },
  })
}

/**
 * 按知识库和请求键核对可能已提交的上传。
 * 接收知识库 ID、上传请求键、取消信号，返回回执查询结果。
 * 由 LibraryView.vue 的 submitUpload 触发。
 * 下游调用 DocumentController.uploadReceipt、mock 的 mockLookupUploadReceipt。
 */
export const lookupUploadReceipt = (
  knowledgeBaseId: number,
  requestKey: string,
  signal?: AbortSignal,
): Promise<UploadReceiptLookupDTO> => USE_MOCK
  ? mockLookupUploadReceipt(knowledgeBaseId, requestKey)
  : http.get<UploadReceiptLookupDTO, UploadReceiptLookupDTO>('/document/upload/receipt', {
      params: { knowledgeBaseId, requestKey },
      signal,
    })

/**
 * 读取指定文档的最新任务快照。
 * 接收文档 ID，返回任务状态快照。
 * 由 LibraryView.vue 的 loadTaskStatuses、LibraryView.vue 的 readTaskSnapshot 触发。
 * 下游调用 DocumentController.getTask、mock 的 mockGetDocumentTask。
 */
export const getDocumentTask = (documentId: number): Promise<DocumentTaskDTO> =>
  USE_MOCK
    ? mockGetDocumentTask(documentId)
    : http.get<DocumentTaskDTO, DocumentTaskDTO>(`/document/${documentId}/task`)

/**
 * 提交解析重试并接收最新任务快照。
 * 接收文档 ID，返回任务状态快照。
 * 由 LibraryView.vue 的 retryTask 触发。
 * 下游调用 DocumentController.retry、mock 的 mockGetDocumentTask。
 */
export const retryDocumentTask = (documentId: number): Promise<DocumentTaskDTO> =>
  USE_MOCK
    ? mockGetDocumentTask(documentId)
    : http.post<DocumentTaskDTO, DocumentTaskDTO>(`/document/${documentId}/retry`)

/**
 * 对指定文档的任务提交取消并取得服务端确认的状态。
 * 接收文档 ID、任务 ID，返回任务状态快照。
 * 由 LibraryView.vue 的 cancelTask 触发。
 * 下游调用 DocumentController.cancelTask。
 */
export const cancelDocumentTask = (documentId: number, taskId: number): Promise<DocumentTaskDTO> =>
  http.post<DocumentTaskDTO, DocumentTaskDTO>(`/document/${documentId}/task/${taskId}/cancel`)

/**
 * 携带当前会话凭据订阅任务帧，并在流接通后触发快照对账。
 * 接收文档 ID、取消信号、onTask、onConnected。
 * 由 LibraryView.vue 的 startTaskStream 触发。
 * 下游调用 DocumentController.streamTask、auth 的 useAuth。
 */
export async function watchDocumentTask(
  documentId: number,
  signal: AbortSignal,
  onTask: (task: DocumentTaskDTO) => void,
  onConnected?: () => void | Promise<void>,
): Promise<void> {
  if (USE_MOCK) return

  const response = await fetch(`/api/document/${documentId}/task/stream`, {
    headers: { Authorization: `Bearer ${useAuth().token}`, Accept: 'text/event-stream' },
    signal,
  })
  if (!response.ok || !response.body) throw new Error(`task stream ${response.status}`)

  // Reconcile after the server has subscribed and sent headers, closing the snapshot/subscribe race.
  await onConnected?.()

  await readSseFrames(response, (frame) => {
    const value = parseJsonFrame<Record<string, unknown>>(frame)
    if (!value) return
    const record = (value.task ?? value.data ?? value) as Partial<DocumentTaskDTO>
    if (typeof record.status !== 'string') return
    try {
      onTask({
        documentId,
        status: record.status,
        taskId: record.taskId,
        attempt: record.attempt,
        retryCount: record.retryCount,
        autoRetryCount: record.autoRetryCount,
        manualRetryCount: record.manualRetryCount,
        errorMessage: record.errorMessage ?? null,
        errorCode: record.errorCode,
        lastErrorMessage: record.lastErrorMessage,
        lastErrorCode: record.lastErrorCode,
        startTime: record.startTime,
        finishTime: record.finishTime,
      })
    } catch {
      // Preserve the task watcher's snapshot reconciliation after a rejected update.
    }
  })
  if (!signal.aborted) throw new Error('task stream closed')
}
