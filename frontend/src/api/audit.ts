/** 按筛选条件和游标读取管理员可见的操作审计记录。 */

import { http } from './http'

export interface AuditEntry {
  id: number
  actorUserId: number | null
  actorUsername: string | null
  action: string
  targetType: string | null
  targetId: string | null
  outcome: string
  requestId: string | null
  createdAt: string
}

interface AuditPage {
  entries: AuditEntry[]
  nextBeforeId: number | null
}

export interface AuditFilters {
  from: string
  to: string
  action: string
  outcome: string
}

/**
 * 按操作条件和翻页游标查询审计条目。
 * 接收filters、历史页游标，返回 AuditPage。
 * 由 AuditLogPanel.vue 的 load 触发。
 * 下游调用 AuditLogController。
 */
export const listAuditLogs = (filters: AuditFilters, beforeId?: number): Promise<AuditPage> =>
  http.get<AuditPage, AuditPage>('/audit/logs', {
    params: {
      from: filters.from || undefined,
      to: filters.to || undefined,
      action: filters.action || undefined,
      outcome: filters.outcome || undefined,
      beforeId,
    },
  })
