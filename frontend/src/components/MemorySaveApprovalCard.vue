<!-- 展示 Agent 的记忆申请状态，并将批准或拒绝决定送到对应会话。 -->

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { decideMemorySaveApproval, getMemorySaveApproval, type MemorySaveApproval } from '../api/agent'

const props = defineProps<{
  sessionId: string
  approvalId: string
  initialApproval?: MemorySaveApproval
  busy: boolean
}>()

const approval = ref<MemorySaveApproval | null>(props.initialApproval ?? null)
const loading = ref(false)
const error = ref('')
const statusLabels: Record<MemorySaveApproval['state'], string> = {
  PENDING: '等待你的确认',
  PROCESSING: '正在确认保存结果',
  SAVED: '已保存到长期记忆',
  DUPLICATE: '匹配的记忆已存在',
  REJECTED: '已拒绝，未保存',
  FAILED: '保存结果未能确认，请在长期记忆中查看',
  EXPIRED: '审批已过期',
}
const kindLabels: Record<string, string> = { FACT: '事实', PREFERENCE: '偏好', PROFILE: '个人资料' }

/** 按会话和申请 ID 读取该记忆审批的当前状态。由 当前模板的 @click 事件 触发。下游调用 agent 的 getMemorySaveApproval。 */
async function refresh() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    approval.value = await getMemorySaveApproval(props.sessionId, props.approvalId)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '无法查询审批状态。'
  } finally {
    loading.value = false
  }
}

/**
 * 提交本次审批决定并刷新卡片反馈。
 * 接收审批决定。
 * 由 当前模板的 @click 事件 触发。
 * 下游调用 agent 的 decideMemorySaveApproval。
 */
async function decide(decision: 'APPROVE' | 'REJECT') {
  if (loading.value || props.busy || approval.value?.state !== 'PENDING') return
  loading.value = true
  error.value = ''
  try {
    approval.value = await decideMemorySaveApproval(props.sessionId, props.approvalId, decision)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '审批结果未确认，请刷新状态。'
  } finally {
    loading.value = false
  }
}

onMounted(() => { void refresh() })
</script>

<template>
  <section class="memory-approval" aria-label="长期记忆保存审批">
    <div class="approval-heading">
      <strong>保存长期记忆</strong>
      <span v-if="approval" role="status">{{ statusLabels[approval.state] }}</span>
      <span v-else-if="loading" role="status">正在读取审批状态…</span>
    </div>
    <template v-if="approval?.state === 'PENDING'">
      <p v-if="approval.kind" class="muted">类型 {{ kindLabels[approval.kind] || approval.kind }}</p>
      <p class="approval-content">{{ approval.content }}</p>
      <div class="approval-actions">
        <t-button size="small" theme="primary" :disabled="busy || loading || !!error" @click="decide('APPROVE')">
          批准保存
        </t-button>
        <t-button size="small" variant="outline" :disabled="busy || loading || !!error" @click="decide('REJECT')">
          拒绝
        </t-button>
      </div>
    </template>
    <p v-if="error" class="approval-error" role="alert">{{ error }}</p>
    <t-button v-if="error || approval?.state === 'PROCESSING'" size="small" variant="text" :disabled="loading" @click="refresh">
      刷新审批状态
    </t-button>
  </section>
</template>

<style scoped>
.memory-approval { display: grid; gap: var(--space-2); margin-top: var(--space-3); padding: var(--space-3); border: 1px solid var(--border); border-radius: var(--radius-md); background: var(--bg-panel-2); }
.approval-heading, .approval-actions { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-3); }
.approval-heading span { font-size: var(--fs-sm); color: var(--text-dim); }
.memory-approval p { margin: 0; }
.approval-content { white-space: pre-wrap; overflow-wrap: anywhere; }
.approval-error { color: var(--err); }
</style>
