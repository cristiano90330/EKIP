/** 把 Agent 回答中的内联引用拆成正文和可定位来源的片段。 */

import type { KnowledgeSourceDTO } from './types'

export interface AgentAnswerPart {
  text: string
  source?: KnowledgeSourceDTO
}

/** 把回答中的引用标记解析为来源片段，保留普通正文及无法匹配的标记。接收文本、来源列表，返回 AgentAnswerPart[]。 */
export function agentAnswerParts(text: string, sources: KnowledgeSourceDTO[] = []): AgentAnswerPart[] {
  const known = new Map(sources
    .filter((source) => typeof source.citationId === 'string' && /^S\d+_\d+(?:_O\d+)?$/.test(source.citationId))
    .map((source) => [source.citationId, source]))
  const parts: AgentAnswerPart[] = []
  const pattern = /\[\[cite:(S\d+_\d+(?:_O\d+)?)\]\]/g
  let cursor = 0

  for (const match of text.matchAll(pattern)) {
    const source = known.get(match[1])
    if (!source) continue
    const index = match.index ?? 0
    if (index > cursor) parts.push({ text: text.slice(cursor, index) })
    parts.push({ text: match[0], source })
    cursor = index + match[0].length
  }
  if (cursor < text.length) parts.push({ text: text.slice(cursor) })
  return parts
}
