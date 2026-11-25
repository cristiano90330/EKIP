<!-- 让管理员分页查看账号、维护部门并修改账号可用状态。 -->

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { assignDepartment, createDepartment, deleteDepartment, listAccounts, listDepartments,
  renameDepartment, updateAccountStatus, type Department, type ManagedAccount } from '../api/accounts'

const accounts = ref<ManagedAccount[]>([])
const nextAfterId = ref<number | null>(null)
const loading = ref(false)
const saving = ref(false)
const loaded = ref(false)
const error = ref('')
const notice = ref('')
const departments = ref<Department[]>([])
const departmentsReady = ref(false)
const departmentName = ref('')
const editingDepartment = ref<Department | null>(null)

/**
 * 按账号游标更新列表并记录加载或失败状态。
 * 接收后续页游标。
 * 由 refresh、当前模板的 @click 事件 触发。
 * 下游调用 accounts 的 listAccounts。
 */
async function load(afterId = 0) {
  if (loading.value || saving.value) return
  loading.value = true
  error.value = ''
  notice.value = ''
  try {
    const page = await listAccounts(afterId)
    accounts.value = afterId === 0 ? page.accounts : [...accounts.value, ...page.accounts]
    nextAfterId.value = page.nextAfterId
    loaded.value = true
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '账号列表加载失败，请重试。'
  } finally {
    loading.value = false
  }
}

/**
 * 提交指定账号的新启停状态并替换该行。
 * 接收account、enabled。
 * 由 当前模板的 @confirm 事件 触发。
 * 下游调用 accounts 的 updateAccountStatus。
 */
async function changeStatus(account: ManagedAccount, enabled: boolean) {
  if (account.currentAdmin || loading.value || saving.value) return
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const updated = await updateAccountStatus(account.id, enabled)
    accounts.value = accounts.value.map(item => item.id === updated.id ? updated : item)
    notice.value = updated.username + (updated.enabled ? ' 已启用。' : ' 已禁用。')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '账号状态更新失败，请重试。'
  } finally {
    saving.value = false
  }
}

/** 加载部门目录并标记分配选项的可用状态。由 refresh 触发。下游调用 accounts 的 listDepartments。 */
async function loadDepartments() {
  try {
    departments.value = await listDepartments()
    departmentsReady.value = true
  } catch (cause) {
    departmentsReady.value = false
    error.value = cause instanceof Error ? cause.message : '部门列表读取失败。'
  }
}

/** 把所选部门的名称装入编辑表单。接收department。由 当前模板的 @click 事件 触发。 */
function editDepartment(department: Department) {
  editingDepartment.value = department
  departmentName.value = department.name
}

/** 退出部门编辑并清空输入。由 saveDepartment、removeDepartment 触发。 */
function resetDepartment() {
  editingDepartment.value = null
  departmentName.value = ''
}

/**
 * 按表单模式创建或改名部门，并同步已加载账号的部门名称。
 * 由 当前模板的 @submit.prevent 事件 触发。
 * 下游调用 changeDepartment、accounts 的 renameDepartment。
 */
async function saveDepartment() {
  const name = departmentName.value.trim()
  const editing = editingDepartment.value
  if (!name || saving.value || loading.value) return
  await changeDepartment(async () => {
    if (editing) {
      const next = await renameDepartment(editing.id, name)
      departments.value = departments.value.map(item => item.id === next.id ? next : item)
      accounts.value = accounts.value.map(item => item.department === editing.name ? { ...item, department: next.name } : item)
    } else {
      departments.value = [...departments.value, await createDepartment(name)]
    }
    resetDepartment()
    notice.value = '部门已保存。'
  })
}

/**
 * 删除指定部门并清除本地列表中的对应分配。
 * 接收department。
 * 由 当前模板的 @confirm 事件 触发。
 * 下游调用 changeDepartment、accounts 的 deleteDepartment。
 */
async function removeDepartment(department: Department) {
  await changeDepartment(async () => {
    await deleteDepartment(department.id)
    departments.value = departments.value.filter(item => item.id !== department.id)
    accounts.value = accounts.value.map(item => item.department === department.name ? { ...item, department: null } : item)
    if (editingDepartment.value?.id === department.id) resetDepartment()
    notice.value = '部门已删除。'
  })
}

/**
 * 读取选择器的部门值，提交账号归属变更并刷新该行。
 * 接收account、页面事件。
 * 由 当前模板的 @change 事件 触发。
 * 下游调用 changeDepartment、accounts 的 assignDepartment。
 */
async function assign(account: ManagedAccount, event: Event) {
  const select = event.target as HTMLSelectElement
  const value = select.value
  select.value = account.department ?? ''
  const department = departments.value.find(item => item.name === value)
  await changeDepartment(async () => {
    const updated = await assignDepartment(account.id, department?.id ?? null)
    accounts.value = accounts.value.map(item => item.id === updated.id ? updated : item)
    notice.value = '账号部门已更新。'
  })
}

/** 统一部门写操作的等待状态和失败提示。接收action。由 saveDepartment、removeDepartment 触发。 */
async function changeDepartment(action: () => Promise<void>) {
  if (saving.value || loading.value || !departmentsReady.value) return
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    await action()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '部门操作未完成。'
  } finally {
    saving.value = false
  }
}

/** 刷新账号首页和部门目录。由 当前模板的 @click 事件 触发。下游调用 load、loadDepartments。 */
function refresh() {
  void load()
  void loadDepartments()
}

onMounted(refresh)
</script>

<template>
  <section class="panel account-panel" aria-labelledby="account-management-heading">
    <div class="account-heading">
      <h2 id="account-management-heading">账号管理</h2>
      <t-button variant="outline" :disabled="loading || saving" @click="refresh">刷新列表</t-button>
    </div>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="notice" role="status" class="notice">{{ notice }}</p>
    <p v-if="loading" role="status" class="muted">正在读取账号…</p>
    <p v-if="loaded && !accounts.length" class="muted">暂无账号。</p>

    <div v-if="accounts.length" class="account-table-scroll">
      <table class="account-table">
        <thead>
          <tr><th scope="col">用户名</th><th scope="col">部门</th><th scope="col">状态</th><th scope="col">操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="account in accounts" :key="account.id">
            <th scope="row">{{ account.username }}</th>
            <td>
              <select :aria-label="account.username + ' 的部门'" :value="account.department ?? ''"
                :disabled="loading || saving || !departmentsReady" @change="assign(account, $event)">
                <option value="">未分配</option>
                <option v-for="department in departments" :key="department.id" :value="department.name">{{ department.name }}</option>
              </select>
            </td>
            <td>{{ account.enabled ? '已启用' : '已禁用' }}</td>
            <td>
              <span v-if="account.currentAdmin" class="muted">当前管理员</span>
              <t-popconfirm
                v-else-if="account.enabled"
                content="禁用后，该账号需要在重新启用后再次登录。确定禁用？"
                @confirm="changeStatus(account, false)"
              >
                <t-button theme="danger" variant="outline" :disabled="loading || saving">禁用</t-button>
              </t-popconfirm>
              <t-button v-else variant="outline" :disabled="loading || saving" @click="changeStatus(account, true)">
                启用
              </t-button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <t-button v-if="nextAfterId !== null" variant="text" :disabled="loading || saving" @click="load(nextAfterId)">
      加载更多
    </t-button>
    <section class="department-section" aria-labelledby="department-heading">
      <h3 id="department-heading">部门管理</h3>
      <form class="department-form" @submit.prevent="saveDepartment">
        <label>部门名称 <input v-model="departmentName" maxlength="100" :disabled="saving || !departmentsReady"></label>
        <t-button type="submit" :disabled="loading || saving || !departmentsReady || !departmentName.trim()">
          {{ editingDepartment ? '保存名称' : '新增部门' }}
        </t-button>
        <t-button v-if="editingDepartment" variant="text" :disabled="saving" @click="resetDepartment">取消编辑</t-button>
      </form>
      <ul v-if="departments.length">
        <li v-for="department in departments" :key="department.id" class="department-form">
          <span>{{ department.name }}</span>
          <t-button size="small" variant="text" :disabled="loading || saving" @click="editDepartment(department)">改名</t-button>
          <t-popconfirm content="删除此部门并清空所属账号的部门字段？" @confirm="removeDepartment(department)">
            <t-button size="small" theme="danger" variant="text" :disabled="loading || saving">删除</t-button>
          </t-popconfirm>
        </li>
      </ul>
    </section>
  </section>
</template>

<style scoped>
.account-heading { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); }
.account-heading h2 { margin: 0; }
.account-table-scroll { overflow-x: auto; }
.account-table { width: 100%; border-collapse: collapse; text-align: left; }
.account-table th, .account-table td { padding: var(--space-3); border-bottom: 1px solid var(--border); }
.account-table tbody th { overflow-wrap: anywhere; }
.account-table td:nth-child(n + 2) { white-space: nowrap; }
.department-section { border-top: 1px solid var(--border); margin-top: var(--space-5); }
.department-form { display: flex; align-items: center; flex-wrap: wrap; gap: var(--space-2); }
.account-panel input, .account-panel select { padding: var(--space-1) var(--space-2); color: var(--text); background: var(--bg-panel-2); border: 1px solid var(--border); border-radius: var(--radius-sm); }
.department-section ul { display: grid; gap: var(--space-2); padding: 0; }
</style>
