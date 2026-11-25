/** 核对模型协议表单与模型用途绑定相关的界面契约。 */

import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { MODEL_PROTOCOLS, newModelDraft, type ModelDraft } from '../src/modelTypes.ts'
import { getModelProviders, resolveModel } from '../src/api/mockModels.ts'

const contract = JSON.parse(readFileSync(new URL('../../tests/fixtures/model-protocol-contract.json', import.meta.url), 'utf8'))

/** 验证前端与共享契约只提供四种生产协议，以 assert.deepEqual 检查实际结果。 */
test('前端与共享契约只提供四种生产协议', async () => {
  assert.deepEqual(MODEL_PROTOCOLS.map(item => item.value).sort(), [...contract.supportedProtocols].sort())
  const catalog = await getModelProviders()
  assert.deepEqual(catalog.protocols.map(item => item.id).sort(), [...contract.supportedProtocols].sort())
  const ollama = catalog.providers.find(item => item.id === 'ollama')!
  assert.deepEqual(ollama.protocols, ['openai_chat', 'openai_embedding'])
  assert.equal(ollama.defaultBaseUrl, 'http://localhost:11434/v1')
})

/** 验证演示预览拒绝停用协议，不模拟自动迁移，以 assert.rejects 检查实际结果。 */
test('演示预览拒绝停用协议，不模拟自动迁移', async () => {
  const retired = { ...newModelDraft('chat'), name: 'legacy', model: 'example',
    protocol: 'ollama_chat', baseUrl: 'http://localhost:11434' }
  await assert.rejects(resolveModel(retired as unknown as ModelDraft), /模型类型与协议不匹配/)
})
