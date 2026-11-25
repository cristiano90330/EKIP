/** 为页面识别当前账号提供统一的用户 ID 表示。 */

import { USE_MOCK } from '../config'
import { useAuth } from '../stores/auth'

/**
 * Reads the stable user ID from the authenticated access token for local UI
 * namespacing. Authorization decisions always remain on the server.
 */

/**
 * 将当前会话用户 ID 归一化为前端比较所用的字符串。
 * 返回文本或空值。
 * 由 RetrievalEvaluationPanel.vue 的 loadLibraries 触发。
 * 下游调用 auth 的 useAuth。
 */
export function currentUserId(): string | null {
  if (USE_MOCK) return '1'

  const token = useAuth().token
  const payload = token.split('.')[1]
  if (!payload) return null

  try {
    // 浏览器侧解码只为比较页面账号标识，资源访问仍由服务端鉴权。
    const normalized = payload.replace(/-/g, '+').replace(/_/g, '/')
    const claims = JSON.parse(atob(normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '='))) as {
      sub?: unknown
    }
    return typeof claims.sub === 'string' && claims.sub.length > 0 ? claims.sub : null
  } catch {
    return null
  }
}
