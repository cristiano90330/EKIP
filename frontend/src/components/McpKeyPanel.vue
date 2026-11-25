<!-- 列出当前账号的 MCP Key，并提供签发、复制和吊销流程。 -->

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { issueMcpKey, listMcpKeys, revokeMcpKey, type IssuedMcpKey, type McpKey } from '../api/mcpKeys'

const keys = ref<McpKey[]>([])
const name = ref('')
const issued = ref<IssuedMcpKey | null>(null)
const loading = ref(false)
const loaded = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const copyStatus = ref('')

/** 读取账号已有 Key 并刷新列表。由 issue、当前模板的 @click 事件 触发。下游调用 mcpKeys 的 listMcpKeys。 */
async function load() {
  error.value = ''
  loading.value = true
  try {
    keys.value = await listMcpKeys()
    loaded.value = true
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '无法读取密钥列表。'
  } finally {
    loading.value = false
  }
}

/** 提交名称签发新 Key，将本次完整值显示给用户。由 当前模板的 @submit.prevent 事件 触发。下游调用 mcpKeys 的 issueMcpKey、load。 */
async function issue() {
  const label = name.value.trim()
  if (!label || saving.value || loading.value) return
  saving.value = true
  error.value = ''
  notice.value = ''
  copyStatus.value = ''
  try {
    issued.value = await issueMcpKey(label)
    name.value = ''
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '签发失败。'
  } finally {
    saving.value = false
  }
}

/**
 * 吊销指定记录并更新当前列表的撤销状态。
 * 接收key。
 * 由 当前模板的 @confirm 事件 触发。
 * 下游调用 mcpKeys 的 revokeMcpKey。
 */
async function revoke(key: McpKey) {
  if (saving.value || loading.value) return
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    await revokeMcpKey(key.id)
    keys.value = keys.value.map(item => item.id === key.id ? { ...item, status: 'REVOKED' } : item)
    notice.value = '已吊销该密钥。'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '吊销失败。'
  } finally {
    saving.value = false
  }
}

/** 将当前签发的 Key 写入剪贴板并提示复制结果。由 当前模板的 @click 事件 触发。 */
async function copyKey() {
  if (!issued.value) return
  try {
    await navigator.clipboard.writeText(issued.value.apiKey)
    copyStatus.value = '已复制。'
  } catch {
    copyStatus.value = '无法自动复制，请选中密钥手动复制。'
  }
}

onMounted(() => { void load() })
</script>

<template>
  <section class="panel key-panel" aria-labelledby="mcp-key-heading">
    <div class="panel-heading">
      <h2 id="mcp-key-heading">MCP Key</h2>
      <t-button variant="outline" :disabled="loading || saving" @click="load">刷新列表</t-button>
    </div>
    <p class="muted">外部 MCP 客户端使用此密钥访问当前账号可读的知识库。</p>
    <form class="key-form" @submit.prevent="issue">
      <label>密钥名称<input v-model="name" maxlength="100" required :disabled="saving"></label>
      <t-button type="submit" theme="primary" :disabled="loading || saving || !name.trim()">签发密钥</t-button>
    </form>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="loading" class="muted" role="status">正在读取密钥…</p>
    <p v-else-if="loaded && !keys.length" class="muted">暂无 MCP 密钥。</p>

    <div v-if="keys.length" class="table-scroll">
      <table>
        <thead>
          <tr><th>名称</th><th>前缀</th><th>状态</th><th>最近使用</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="key in keys" :key="key.id">
            <th scope="row">{{ key.name }}</th>
            <td><code>{{ key.keyPrefix }}</code></td>
            <td>{{ key.status === 'REVOKED' ? '已吊销' : '已启用' }}</td>
            <td>{{ key.lastUsedTime?.replace('T', ' ') ?? '未使用' }}</td>
            <td>
              <t-popconfirm v-if="key.status === 'ACTIVE'" content="吊销后，该密钥将无法继续调用 MCP。"
                @confirm="revoke(key)">
                <t-button size="small" variant="outline" theme="danger" :disabled="loading || saving">吊销</t-button>
              </t-popconfirm>
              <span v-else>—</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <t-dialog :visible="issued !== null" header="保存新密钥" :footer="false"
      :close-on-overlay-click="false" width="min(520px, 94vw)" @close="issued = null">
      <div v-if="issued" class="key-reveal">
        <p>「{{ issued.name }}」的完整密钥仅显示这一次。</p>
        <label>完整密钥<input :value="issued.apiKey" readonly autocomplete="off" spellcheck="false"></label>
        <div class="key-actions">
          <t-button theme="primary" @click="copyKey">复制密钥</t-button>
          <t-button variant="outline" @click="issued = null">关闭</t-button>
        </div>
        <p v-if="copyStatus" role="status">{{ copyStatus }}</p>
      </div>
    </t-dialog>
  </section>
</template>

<style scoped>
.panel-heading, .key-form, .key-actions { display: flex; align-items: center; flex-wrap: wrap; gap: var(--space-3); }
.panel-heading { justify-content: space-between; }
.panel-heading h2 { margin: 0; }
.key-form { align-items: end; }
.key-panel label, .key-reveal { display: grid; gap: var(--space-2); }
.key-panel input, .key-reveal input { width: 100%; padding: var(--space-2); color: var(--text); background: var(--bg-panel-2); border: 1px solid var(--border); border-radius: var(--radius-sm); font: inherit; }
.key-reveal p { margin: 0; }
.table-scroll { margin-top: var(--space-3); overflow-x: auto; }
table { width: 100%; border-collapse: collapse; text-align: left; }
th, td { padding: var(--space-3); border-bottom: 1px solid var(--border); }
td { white-space: nowrap; }
.error { color: var(--err); }
</style>
