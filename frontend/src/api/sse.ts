/** 将 Fetch 响应体解析成 SSE 事件并解析 JSON 载荷。 */

/**
 * 浏览器端 SSE 读取工具（fetch + ReadableStream）。
 *
 * 为什么不用原生 EventSource：
 *   1. EventSource 只能发 GET，无法携带请求体（问答/对话都是 POST）；
 *   2. EventSource 不能设置 Authorization 头，token 只能塞 query string（会进访问日志）。
 *
 * eventsource-parser 负责协议分帧；本模块保留项目的回调、空白与 EOF 兼容行为。
 * 请求、鉴权和重连由调用方管理，解析器不会重新提交请求。
 */

import { createParser } from 'eventsource-parser'

export interface SseFrame {
  event: string
  data: string
}

/**
 * 按字节流读取 SSE，解码后向回调分发完整事件帧。
 * 接收HTTP 响应、onFrame。
 * 由 agent.ts 的 streamAgentChat、knowledge.ts 的 readKnowledgeStream 触发。
 */
export async function readSseFrames(
  response: Response,
  onFrame: (frame: SseFrame) => boolean | void,
): Promise<void> {
  if (!response.body) throw new Error('响应没有可读流')

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let stopped = false
  let exhausted = false
  const parser = createParser({
    /** 把解析器事件转换成统一帧并记录调用方的停止要求。接收待显示的消息。由 readSseFrames 触发。 */
    onEvent(message) {
      if (stopped) return
      stopped = onFrame({
        event: (message.event ?? 'message').trim(),
        // 保留 B0 按 data 行 trim 的公开行为，避免替换解析器改变现有调用者载荷。
        data: message.data.split('\n').map(line => line.trim()).join('\n'),
      }) === false
    },
  })

  try {
    // TextDecoder 在多次读取间保留不完整的字符，解析器继续拼接尚未结束的 SSE 帧。
    while (!stopped) {
      const { value, done } = await reader.read()
      if (done) {
        exhausted = true
        // B0 允许最后一帧省略空行；仅在正常 EOF 消费，取消/读失败不交付半帧。
        parser.feed(decoder.decode() + '\n\n')
        break
      }
      parser.feed(decoder.decode(value, { stream: true }))
    }
  } finally {
    if (!exhausted) {
      try {
        await reader.cancel()
      } catch {
        // 清理失败不覆盖调用者异常或已经交付的业务终态。
      }
    }
    reader.releaseLock()
  }
}

/**
 * 将事件帧的数据解码为所需的 JSON 值，空载荷返回空值。
 * 接收frame。
 * 由 agent.ts 的 streamAgentChat、knowledge.ts 的 readKnowledgeStream 触发。
 */
export function parseJsonFrame<T>(frame: SseFrame): T | null {
  try {
    return JSON.parse(frame.data) as T
  } catch {
    return null
  }
}
