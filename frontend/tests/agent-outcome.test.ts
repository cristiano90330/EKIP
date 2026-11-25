/** 核对 Agent 回合停止原因的页面标签。 */

import assert from 'node:assert/strict'
import test from 'node:test'
import { agentStopReasonLabel } from '../src/agentOutcome.ts'

/** 验证Agent 的 FINAL_ANSWER 不能覆盖失败或无证据终态（实时与历史共用），以 assert.equal 检查实际结果。 */
test('Agent 的 FINAL_ANSWER 不能覆盖失败或无证据终态（实时与历史共用）', () => {
  assert.equal(agentStopReasonLabel({ stopReason: 'FINAL_ANSWER', outcome: 'dependency_unavailable' }), '依赖不可用')
  assert.equal(agentStopReasonLabel({ stopReason: 'FINAL_ANSWER', outcome: 'timeout' }), '处理超时')
  assert.equal(agentStopReasonLabel({ stopReason: 'FINAL_ANSWER', outcome: 'no_evidence' }), '无获准证据')
  assert.equal(agentStopReasonLabel({ stopReason: 'FINAL_ANSWER', degraded: true }), 'AI 服务降级')
})

/** 验证成功、取消、容量和非正常停止保留明确含义，以 assert.equal 检查实际结果。 */
test('成功、取消、容量和非正常停止保留明确含义', () => {
  assert.equal(agentStopReasonLabel({ stopReason: 'FINAL_ANSWER', outcome: 'success' }), '正常完成')
  assert.equal(agentStopReasonLabel({ stopReason: 'CANCELLED', outcome: 'timeout' }), '已取消')
  assert.equal(agentStopReasonLabel({ stopReason: 'FINAL_ANSWER', outcome: 'dependency_unavailable', reasonCode: 'support_context_capacity_exceeded' }), '容量不足')
  assert.equal(agentStopReasonLabel({ stopReason: 'FINAL_ANSWER', reasonCode: 'other', degradeReason: 'model_context_capacity_exceeded' }), '容量不足')
  assert.equal(agentStopReasonLabel({ stopReason: 'MAX_TOOL_CALLS', outcome: 'success' }), '工具次数上限')
  assert.equal(agentStopReasonLabel({}), '未知')
})
