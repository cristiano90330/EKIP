/** 将 Agent 回合的停止原因转换为聊天界面使用的状态标签。 */

import type { AgentDoneEvent } from './api/agent'
import { contextCapacityMessage } from './ragOutcome'

const STOP_REASON_LABELS: Record<string, string> = {
  FINAL_ANSWER: '正常完成',
  FINAL_ANSWER_TRUNCATED: '输出截断',
  CONTENT_FILTERED: '内容过滤',
  EMPTY_RESPONSE: '空回复',
  STUCK_REPEATED: '无进展中止',
  MAX_ITERATIONS: '轮次上限',
  MAX_TOOL_CALLS: '工具次数上限',
  CANCELLED: '已取消',
  MEMORY_APPROVAL_REQUIRED: '已发起审批',
  BUDGET_EXCEEDED: '时间预算超限',
  LLM_DEGRADED: 'AI 服务降级',
}

/**
 * 按已完成帧的停止原因和结果生成聊天状态文案。
 * 接收done，返回文本。
 * 由 agent-outcome.test.ts 的 Agent 的 FINAL_ANSWER 不能覆盖失败或无证据终态（实时与历史共用）、agent-outcome.test.ts 的 成功、取消、容量和非正常停止保留明确含义 触发。
 * 下游调用 ragOutcome 的 contextCapacityMessage。
 */
export function agentStopReasonLabel(done: AgentDoneEvent): string {
  if (done.stopReason === 'CANCELLED') return '已取消'
  if (done.stopReason === 'MEMORY_APPROVAL_REQUIRED') return STOP_REASON_LABELS.MEMORY_APPROVAL_REQUIRED
  if (contextCapacityMessage(done.reasonCode) || contextCapacityMessage(done.degradeReason)) return '容量不足'
  if (done.outcome === 'dependency_unavailable') return '依赖不可用'
  if (done.outcome === 'timeout') return '处理超时'
  if (done.degraded) return 'AI 服务降级'
  if (done.outcome === 'no_evidence') return '无获准证据'
  return done.stopReason ? (STOP_REASON_LABELS[done.stopReason] ?? done.stopReason) : '未知'
}
