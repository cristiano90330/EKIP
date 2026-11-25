<!-- 提供操作审计筛选和游标分页列表。 -->

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { listAuditLogs, type AuditEntry, type AuditFilters } from '../api/audit'

const actions: Record<string, string> = {
  'account.status_changed': '账号启停',
  'kb.created': '创建知识库',
  'kb.updated': '编辑知识库',
  'kb.deleted': '删除知识库',
  'rbac.member_added': '添加共享成员',
  'rbac.member_removed': '移除共享成员',
  'document.uploaded': '上传文档',
  'knowledge.created': '上传文档（历史记录）',
  'knowledge.deleted': '删除文档',
  'system.api_key_created': '签发 MCP Key',
  'system.api_key_revoked': '吊销 MCP Key',
  'agent.memory_approval_decided': 'Agent 记忆审批',
}
const outcomes: Record<string, string> = {
  success: '成功', accepted: '已受理', denied: '已拒绝', failed: '失败', partial: '部分成功', canceled: '已取消',
}

const draft = reactive<AuditFilters>({ from: '', to: '', action: '', outcome: '' })
const applied = ref<AuditFilters>({ ...draft })
const entries = ref<AuditEntry[]>([])
const nextBeforeId = ref<number | null>(null)
const loading = ref(false)
const loaded = ref(false)
const error = ref('')

/**
 * 应用筛选并按游标替换或追加审计条目。
 * 接收append。
 * 由 当前模板的 @submit.prevent 事件 触发。
 * 下游调用 audit 的 listAuditLogs。
 */
async function load(append = false) {
  if (loading.value || (append && nextBeforeId.value === null)) return

  const filters = append ? applied.value : { ...draft }

  if (filters.from && filters.to && filters.from > filters.to) {
    error.value = '开始日期应早于或等于结束日期。'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const page = await listAuditLogs(filters, append ? nextBeforeId.value ?? undefined : undefined)
    applied.value = filters
    entries.value = append ? [...entries.value, ...page.entries] : page.entries
    nextBeforeId.value = page.nextBeforeId
    loaded.value = true
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '审计记录加载失败。'
  } finally {
    loading.value = false
  }
}

onMounted(() => { void load() })
</script>

<template>
  <section class="panel audit-panel" aria-labelledby="audit-heading">
    <h2 id="audit-heading">操作审计</h2>
    <form class="audit-filters" @submit.prevent="load()">
      <label>开始日期<input v-model="draft.from" type="date"></label>
      <label>结束日期<input v-model="draft.to" type="date"></label>
      <label>动作
        <select v-model="draft.action">
          <option value="">全部动作</option>
          <option v-for="(label, code) in actions" :key="code" :value="code">{{ label }}</option>
        </select>
      </label>
      <label>结果
        <select v-model="draft.outcome">
          <option value="">全部结果</option>
          <option v-for="(label, code) in outcomes" :key="code" :value="code">{{ label }}</option>
        </select>
      </label>
      <t-button type="submit" theme="primary" :disabled="loading">查询</t-button>
    </form>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="loading" class="muted" role="status">正在查询…</p>
    <p v-else-if="loaded && !entries.length" class="muted">没有符合条件的审计记录。</p>
    <div v-if="entries.length" class="table-scroll">
      <table>
        <thead>
          <tr><th>时间</th><th>操作人</th><th>动作</th><th>对象</th><th>结果</th><th>请求 ID</th></tr>
        </thead>
        <tbody>
          <tr v-for="entry in entries" :key="entry.id">
            <td>{{ entry.createdAt.replace('T', ' ').slice(0, 19) }}</td>
            <td>{{ entry.actorUsername ?? (entry.actorUserId === null ? '—' : '账号 #' + entry.actorUserId) }}</td>
            <td :title="entry.action">{{ actions[entry.action] ?? entry.action }}</td>
            <td>{{ [entry.targetType, entry.targetId].filter(Boolean).join(' ') || '—' }}</td>
            <td>{{ outcomes[entry.outcome] ?? entry.outcome }}</td>
            <td><code>{{ entry.requestId ?? '—' }}</code></td>
          </tr>
        </tbody>
      </table>
    </div>
    <t-button v-if="nextBeforeId !== null" variant="text" :disabled="loading" @click="load(true)">加载更多</t-button>
  </section>
</template>

<style scoped>
.audit-panel h2 { margin-top: 0; }
.audit-filters { display: flex; flex-wrap: wrap; align-items: end; gap: var(--space-3); }
.audit-filters label { display: grid; gap: var(--space-2); }
.audit-filters input, .audit-filters select { padding: var(--space-2); color: var(--text); background: var(--bg-panel-2); border: 1px solid var(--border); border-radius: var(--radius-sm); font: inherit; }
.table-scroll { margin-top: var(--space-3); overflow-x: auto; }
table { width: 100%; border-collapse: collapse; text-align: left; }
th, td { padding: var(--space-3); border-bottom: 1px solid var(--border); white-space: nowrap; }
.error { color: var(--err); }
</style>
