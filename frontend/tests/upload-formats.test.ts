/** 核对文件选择回调和许可扩展名的一致性。 */

import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import ts from 'typescript'
import { DOCUMENT_UPLOAD_ACCEPT, isSupportedDocumentName, SUPPORTED_DOCUMENT_EXTENSIONS } from '../src/documentFormats.ts'

const view = readFileSync(new URL('../src/views/LibraryView.vue', import.meta.url), 'utf8')
const callback = view.match(/function onFileSelect\(files: File\[\]\) \{[\s\S]*?\n\}/)?.[0]
assert.ok(callback, 'Exercise the actual upload selection callback')
const compiled = ts.transpileModule(callback, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText

/** 给实际文件选择方法注入可观察状态，回传选中文件、错误和回执检查次数。由 实际文件选择回调接受 参数表中的扩展名、拒绝未支持和无扩展名文件，并允许清空选择 触发。 */
function selection() {
  const selectedFile: { value: File | null } = { value: null }
  const uploadError = { value: '' }
  let inspected = 0
  const select: (files: File[]) => void = new Function('isSupportedDocumentName', 'selectedFile',
    'uploadError', 'notice', 'uploadIntentKnown', 'inspectSelectedUploadIntent', `${compiled}; return onFileSelect`)(
    isSupportedDocumentName, selectedFile, uploadError, { value: '' }, { value: false }, () => { inspected++ },
  )
  return { select, selectedFile, uploadError, inspections: () => inspected }
}

for (const extension of SUPPORTED_DOCUMENT_EXTENSIONS) {
  /** 验证实际文件选择回调接受 参数表中的扩展名，以 assert.equal 检查实际结果。 */
  test(`实际文件选择回调接受 ${extension.toUpperCase()}`, () => {
    const state = selection()
    const file = new File(['fixture'], `probe.${extension.toUpperCase()}`)
    state.select([file])
    assert.equal(state.selectedFile.value, file)
    assert.equal(state.uploadError.value, '')
    assert.equal(state.inspections(), 1)
  })
}

/** 验证拒绝未支持和无扩展名文件，并允许清空选择，以 assert.equal 检查实际结果。 */
test('拒绝未支持和无扩展名文件，并允许清空选择', () => {
  const state = selection()

  for (const name of ['probe.exe', 'document', 'report.pdf.exe']) {
    state.select([new File(['fixture'], name)])
    assert.equal(state.selectedFile.value, null)
    assert.ok(state.uploadError.value)
  }
  state.select([])
  assert.equal(state.selectedFile.value, null)
  assert.equal(state.uploadError.value, '')
  assert.equal(state.inspections(), 0)
})

/** 验证文件选择器与实际校验只维护一份格式清单，以 assert.ok 检查实际结果。 */
test('文件选择器与实际校验只维护一份格式清单', () => {
  assert.ok(view.includes(':accept="DOCUMENT_UPLOAD_ACCEPT"'))
  assert.equal(DOCUMENT_UPLOAD_ACCEPT.split(',').length, 14)
})
