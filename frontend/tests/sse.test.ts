/** 核对 SSE 分帧、错误映射和 RAG 阅读期限，以及原始候选与公开证据的边界。 */

import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { parseJsonFrame, readSseFrames, type SseFrame } from '../src/api/sse.ts'
import { normalizeRagStreamResult, presentRagResult } from '../src/ragOutcome.ts'
import type { KnowledgeSourceDTO, RagOutcome } from '../src/types.ts'

/** 按片段 ID 和正文构造问答来源夹具。接收chunkId、content，返回 KnowledgeSourceDTO。由 问答终帧只展示最终获准证据、问答 参数表中的扩展名 触发。 */
function source(chunkId: number, content: string): KnowledgeSourceDTO {
  return { documentId: 7, chunkId, knowledgeBaseId: 5, chunkIndex: 0, content,
    distance: null, headingPath: null, pageNo: null, sourceType: 'text' }
}

/** 验证问答终帧只展示最终获准证据，以 assert.deepEqual 检查实际结果。 */
test('问答终帧只展示最终获准证据', () => {
  const raw = source(1, '原始候选全文')
  const evidence = source(2, '实际进入生成的引用')
  const result = normalizeRagStreamResult({ answer: '答案', outcome: 'success',
    sources: [raw], evidenceSources: [evidence] })
  assert.deepEqual(result.sources, [evidence])
  assert.deepEqual(normalizeRagStreamResult({ answer: '答案', outcome: 'success', sources: [raw] }).sources, [])
})

for (const outcome of ['no_evidence', 'dependency_unavailable', 'timeout'] as RagOutcome[]) {
  /** 验证问答 参数表中的扩展名，以 assert.deepEqual 检查实际结果。 */
  test(`问答 ${outcome} 终帧清除公开来源`, () => {
    const result = normalizeRagStreamResult({ answer: '未完成', outcome,
      sources: [source(1, '候选')], evidenceSources: [source(2, '此前通过审查的资料')] })
    assert.deepEqual(result.sources, [])
    assert.equal(presentRagResult(result).showSources, false)
    assert.equal(presentRagResult(result).showAnswer, false)
  })
}

interface Fixture {
  name: string
  wire: string
  frames: SseFrame[]
}

const fixtures: Fixture[] = JSON.parse(readFileSync(new URL('./fixtures/sse-contract.json', import.meta.url), 'utf8'))
const encoder = new TextEncoder()

/** 把指定字节分片装入可读流响应。接收parts，返回 Response。由 framesFromParts、回调 false 阻止同一块和后续块中的事件 触发。 */
function responseFromParts(parts: Uint8Array[]): Response {
  return new Response(new ReadableStream<Uint8Array>({
    /** 为测试流安排指定分片或注入读取失败。接收controller。由 responseFromParts 触发。 */
    start(controller) {
      for (const part of parts) controller.enqueue(part)
      controller.close()
    },
  }))
}

/**
 * 读取模拟响应中的 SSE 帧并按接收顺序返回。
 * 接收parts。
 * 由 S1：规范允许单 CR 分隔，包含跨块行分隔 触发。
 * 下游调用 responseFromParts、sse.ts 的 readSseFrames。
 */
async function framesFromParts(parts: Uint8Array[]): Promise<SseFrame[]> {
  const frames: SseFrame[] = []
  const response = responseFromParts(parts)
  await readSseFrames(response, frame => { frames.push(frame) })
  assert.equal(response.body?.locked, false)
  return frames
}

// 同一组固定输入在替换前后运行；遍历字节边界，包含 UTF-8 与 CRLF 拆分。
for (const fixture of fixtures) {
  test(fixture.name, async () => {
    const bytes = encoder.encode(fixture.wire)
    assert.deepEqual(await framesFromParts([bytes]), fixture.frames)
    for (let split = 1; split < bytes.length; split++) {
      assert.deepEqual(await framesFromParts([bytes.slice(0, split), bytes.slice(split)]), fixture.frames,
        `byte boundary ${split}`)
    }
    assert.deepEqual(await framesFromParts(Array.from(bytes, byte => Uint8Array.of(byte))), fixture.frames)
  })
}

/** 验证回调 false 阻止同一块和后续块中的事件，以 assert.deepEqual 检查实际结果。 */
test('回调 false 阻止同一块和后续块中的事件', async () => {
  const response = responseFromParts([
    encoder.encode('event: done\ndata: {}\n\nevent: delta\ndata: ignored\n\n'),
    encoder.encode('data: later\n\n'),
  ])
  const frames: SseFrame[] = []
  await readSseFrames(response, frame => { frames.push(frame); return false })
  assert.deepEqual(frames, [{ event: 'done', data: '{}' }])
  assert.equal(response.body?.locked, false)
})

/** 验证读取失败向上传播并释放 reader，以 assert.rejects 检查实际结果。 */
test('读取失败向上传播并释放 reader', async () => {
  const failure = new Error('socket interrupted')
  const response = new Response(new ReadableStream<Uint8Array>({
    /** 为测试流安排指定分片或注入读取失败。接收controller。 */
    start(controller) { controller.error(failure) },
  }))
  await assert.rejects(readSseFrames(response, () => {}), error => error === failure)
  assert.equal(response.body?.locked, false)
})

/** 验证调用者处理失败向上传播并释放 reader，以 assert.rejects 检查实际结果。 */
test('调用者处理失败向上传播并释放 reader', async () => {
  const failure = new Error('handler failed')
  const response = responseFromParts([encoder.encode('data: value\n\n')])
  await assert.rejects(readSseFrames(response, () => { throw failure }), error => error === failure)
  assert.equal(response.body?.locked, false)
})

/** 验证缺少响应体明确失败，以 assert.rejects 检查实际结果。 */
test('缺少响应体明确失败', async () => {
  await assert.rejects(readSseFrames(new Response(null), () => {}), /响应没有可读流/)
})

/** 验证JSON解析只处理语法，不替业务校验载荷字段，以 assert.deepEqual 检查实际结果。 */
test('JSON解析只处理语法，不替业务校验载荷字段', () => {
  assert.deepEqual(parseJsonFrame({ event: 'done', data: '{"answer":"你好"}' }), { answer: '你好' })
  assert.equal(parseJsonFrame({ event: 'message', data: '{invalid' }), null)
  assert.equal(parseJsonFrame({ event: 'message', data: '' }), null)
})

/** 验证S1：规范允许单 CR 分隔，包含跨块行分隔，以 assert.deepEqual 检查实际结果。 */
test('S1：规范允许单 CR 分隔，包含跨块行分隔', async () => {
  const bytes = encoder.encode('event: delta\rdata: 中文\r\revent: done\rdata: {}\r\r')
  const expected = [{ event: 'delta', data: '中文' }, { event: 'done', data: '{}' }]
  assert.deepEqual(await framesFromParts(Array.from(bytes, byte => Uint8Array.of(byte))), expected)
})

/** 验证S1：提前停止取消未完成的底层流，以 assert.equal 检查实际结果。 */
test('S1：提前停止取消未完成的底层流', async () => {
  let cancelled = 0
  const response = new Response(new ReadableStream<Uint8Array>({
    /** 为测试流安排指定分片或注入读取失败。接收controller。 */
    start(controller) { controller.enqueue(encoder.encode('event: done\ndata: {}\n\n')) },

    /** 记录测试流被取消的次数，并可模拟清理失败。 */
    cancel() { cancelled++ },
  }))
  await readSseFrames(response, () => false)
  assert.equal(cancelled, 1)
  assert.equal(response.body?.locked, false)
})

/** 验证S1：回调失败取消底层流，取消失败不覆盖原始错误，以 assert.rejects 检查实际结果。 */
test('S1：回调失败取消底层流，取消失败不覆盖原始错误', async () => {
  let cancelled = 0
  const failure = new Error('handler failed')
  const response = new Response(new ReadableStream<Uint8Array>({
    /** 为测试流安排指定分片或注入读取失败。接收controller。 */
    start(controller) { controller.enqueue(encoder.encode('data: value\n\n')) },

    /** 记录测试流被取消的次数，并可模拟清理失败。 */
    cancel() { cancelled++; throw new Error('cleanup failed') },
  }))
  await assert.rejects(readSseFrames(response, () => { throw failure }), error => error === failure)
  assert.equal(cancelled, 1)
  assert.equal(response.body?.locked, false)
})

/** 验证S1：取消错误向上传播，半段帧不作为完成帧交付，以 assert.rejects 检查实际结果。 */
test('S1：取消错误向上传播，半段帧不作为完成帧交付', async () => {
  const aborted = new DOMException('user stopped', 'AbortError')
  let controller: ReadableStreamDefaultController<Uint8Array>
  const response = new Response(new ReadableStream<Uint8Array>({
    /** 为测试流安排指定分片或注入读取失败。接收value。 */
    start(value) {
      controller = value
      controller.enqueue(encoder.encode('data: complete\n\ndata: unfinished'))
    },
  }))
  const frames: SseFrame[] = []
  await assert.rejects(readSseFrames(response, frame => {
    frames.push(frame)
    controller.error(aborted)
  }), error => error === aborted)
  assert.deepEqual(frames, [{ event: 'message', data: 'complete' }])
  assert.equal(response.body?.locked, false)
})
