/** 核对文档切片的分页入口、来源元数据与知识库切换后的状态清理。 */

import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import ts from 'typescript'
import { ref } from 'vue'

const view = readFileSync(new URL('../src/views/LibraryView.vue', import.meta.url), 'utf8')
const functions = ['loadChunks', 'openChunks'].map(name =>
  view.match(new RegExp(`(?:async )?function ${name}\\([^]*?\\n\\}`, 'm'))?.[0] ?? '',
).join('\n')
const compiled = ts.transpileModule(functions, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText

/** 把真实切片抽屉方法接到模拟查询，并回传组件状态及查询参数供断言。接收fetchPage。由 查看切片通过真实读取边界取得当前文档正文、分页保持文档范围并使用服务端总数 触发。 */
function drawer(fetchPage = async (id: number, page: number, size: number) => ({
  records: [{ id: 10, documentId: id, knowledgeBaseId: 7, chunkIndex: 0, content: '真实切片内容', charCount: 6 }],
  current: page, size, total: 1, pages: 1,
})) {
  const requests: number[][] = []
  const state = {
    chunkDocument: ref(null), chunkDrawerVisible: ref(false), chunkLoading: ref(false),
    chunks: ref([]), chunkError: ref(''), chunkPage: ref(1), chunkTotal: ref(0),
    chunksUnavailable: ref(false),
  }
  const callbacks = new Function(...Object.keys(state), 'listDocumentChunks', 'messageOf', 'USE_MOCK',
    'MOCK_CHUNKS', `let chunkRequest = 0; const chunkPageSize = 20; ${compiled}; return {openChunks, loadChunks: typeof loadChunks === 'function' ? loadChunks : undefined}`)(
    ...Object.values(state), async (...args: [number, number, number]) => {
      requests.push(args)
      return fetchPage(...args)
    }, (error: Error) => error.message, false, [],
  )
  return { ...state, ...callbacks, requests }
}

/** 验证查看切片通过真实读取边界取得当前文档正文，以 assert.deepEqual 检查实际结果。 */
test('查看切片通过真实读取边界取得当前文档正文', async () => {
  const state = drawer()
  await state.openChunks({ id: 1, knowledgeBaseId: 7, fileName: 'first.txt' })
  assert.deepEqual(state.requests, [[1, 1, 20]])
  assert.equal(state.chunkDrawerVisible.value, true)
  assert.equal(state.chunks.value[0].content, '真实切片内容')
  assert.equal(state.chunkTotal.value, 1)
  assert.equal(state.chunkLoading.value, false)
  assert.equal(state.chunkError.value, '')
})

/** 验证分页保持文档范围并使用服务端总数，以 assert.deepEqual 检查实际结果。 */
test('分页保持文档范围并使用服务端总数', async () => {
  const state = drawer(async (id, page, size) => ({
    records: [{ id: page, documentId: id, knowledgeBaseId: 7, chunkIndex: page - 1,
      content: `第${page}页`, charCount: 3 }], current: page, size, total: 45, pages: 3,
  }))
  await state.openChunks({ id: 1, knowledgeBaseId: 7 })
  await state.loadChunks(2)
  assert.deepEqual(state.requests, [[1, 1, 20], [1, 2, 20]])
  assert.equal(state.chunkPage.value, 2)
  assert.equal(state.chunkTotal.value, 45)
  assert.equal(state.chunks.value[0].content, '第2页')
})

/** 验证上一份文档的晚到结果不覆盖当前切片，以 assert.equal 检查实际结果。 */
test('上一份文档的晚到结果不覆盖当前切片', async () => {
  const pending = new Map<number, (page: unknown) => void>()
  const state = drawer((id) => new Promise(resolve => pending.set(id, resolve)))
  const first = state.openChunks({ id: 1, knowledgeBaseId: 7 })
  const second = state.openChunks({ id: 2, knowledgeBaseId: 7 })

  /** 组装指定文档的模拟切片分页响应。接收记录 ID。由 上一份文档的晚到结果不覆盖当前切片 触发。 */
  const page = (id: number) => ({ records: [{ id, content: `文档${id}` }], current: 1, size: 20, total: 1, pages: 1 })
  pending.get(2)!(page(2))
  await second
  pending.get(1)!(page(1))
  await first
  assert.equal(state.chunkDocument.value.id, 2)
  assert.equal(state.chunks.value[0].content, '文档2')
  assert.equal(state.chunkLoading.value, false)
})

/** 验证读取错误可见且结束加载，重试仍读取同一文档，以 assert.equal 检查实际结果。 */
test('读取错误可见且结束加载，重试仍读取同一文档', async () => {
  let failed = true
  const state = drawer(async (id, page, size) => {
    if (failed) throw new Error('无权查看该文档切片')
    return { records: [], current: page, size, total: 0, pages: 0 }
  })
  await state.openChunks({ id: 1, knowledgeBaseId: 7 })
  assert.equal(state.chunkError.value, '无权查看该文档切片')
  assert.equal(state.chunkLoading.value, false)
  assert.deepEqual(state.chunks.value, [])
  failed = false
  await state.loadChunks(1)
  assert.equal(state.chunkError.value, '')
  assert.equal(state.chunkTotal.value, 0)
})

/** 验证关闭抽屉后忽略晚到正文，以 assert.deepEqual 检查实际结果。 */
test('关闭抽屉后忽略晚到正文', async () => {
  let finish!: (page: unknown) => void
  const state = drawer(() => new Promise(resolve => { finish = resolve }))
  const loading = state.openChunks({ id: 1, knowledgeBaseId: 7 })
  state.chunkDrawerVisible.value = false
  finish({ records: [{ content: '晚到内容' }], current: 1, size: 20, total: 1, pages: 1 })
  await loading
  assert.deepEqual(state.chunks.value, [])
  assert.equal(state.chunkLoading.value, false)
})
