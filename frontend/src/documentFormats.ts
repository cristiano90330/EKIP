/** 维护上传选择器和表单校验共用的文档扩展名。 */

/** Upload picker and selection validation share the backend's supported extension set. */
export const SUPPORTED_DOCUMENT_EXTENSIONS = [
  'pdf', 'md', 'markdown', 'docx', 'doc', 'pptx', 'ppt',
  'xlsx', 'xls', 'epub', 'xmind', 'txt', 'csv', 'json',
] as const

export const DOCUMENT_UPLOAD_ACCEPT = SUPPORTED_DOCUMENT_EXTENSIONS.map(extension => `.${extension}`).join(',')

const supported = new Set<string>(SUPPORTED_DOCUMENT_EXTENSIONS)

/** 从文件名提取小写扩展名并按上传格式表判断是否可选。接收名称，返回布尔判定。由 LibraryView.vue 的 onFileSelect 触发。 */
export function isSupportedDocumentName(name: string): boolean {
  const dot = name.lastIndexOf('.')
  return dot >= 0 && supported.has(name.slice(dot + 1).toLowerCase())
}
