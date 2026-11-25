/** 封装外部 MCP 访问 Key 的列表、签发和吊销请求。 */

import { http } from './http'

export interface McpKey {
  id: number
  name: string
  keyPrefix: string
  status: 'ACTIVE' | 'REVOKED'
  expiresAt: string | null
  lastUsedTime: string | null
  createTime: string
}

export interface IssuedMcpKey {
  name: string
  apiKey: string
}

const keysPath = '/mcp/keys'

/**
 * 读取账号的 MCP Key 摘要列表。
 * 返回 McpKey[]。
 * 由 McpKeyPanel.vue 的 load 触发。
 * 下游调用 McpApiKeyController。
 */
export const listMcpKeys = (): Promise<McpKey[]> => http.get<McpKey[], McpKey[]>(keysPath)

/**
 * 签发指定名称的 MCP Key 并返回一次性的完整值。
 * 接收名称。
 * 由 McpKeyPanel.vue 的 issue 触发。
 * 下游调用 McpApiKeyController。
 */
export const issueMcpKey = (name: string): Promise<IssuedMcpKey> =>
  http.post<IssuedMcpKey, IssuedMcpKey>(keysPath, { name })

/**
 * 吊销指定 MCP Key 并返回操作结果。
 * 接收MCP Key ID。
 * 由 McpKeyPanel.vue 的 revoke 触发。
 * 下游调用 McpApiKeyController。
 */
export const revokeMcpKey = (id: number): Promise<string> =>
  http.delete<string, string>(keysPath + '/' + id)
