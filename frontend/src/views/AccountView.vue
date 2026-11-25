<!-- 展示登录账号资料和管理资格，提供账号切换入口。 -->

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getAccountAdminAccess, getAccountProfile, type AccountProfile } from '../api/auth'
import { USE_MOCK } from '../config'
import { useAuth } from '../stores/auth'

const auth = useAuth()
const account = ref<AccountProfile | null>(null)
const accountAdmin = ref<boolean | null>(null)
const loading = ref(false)
const switching = ref(false)
const error = ref('')

/**
 * 读取当前账号的资料与管理资格。
 * 由 当前模板的 @click 事件 触发。
 * 下游调用 auth 的 getAccountProfile、auth 的 getAccountAdminAccess。
 */
async function loadAccount() {
  loading.value = true
  error.value = ''
  try {
    const [profile, adminAccess] = await Promise.all([
      getAccountProfile(),
      USE_MOCK ? Promise.resolve(false) : getAccountAdminAccess().catch(() => null),
    ])
    account.value = profile
    accountAdmin.value = adminAccess
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '无法读取账号信息。'
  } finally {
    loading.value = false
  }
}

/** 退出当前登录并切换到账号登录页面。由 当前模板的 @click 事件 触发。 */
async function switchAccount() {
  if (switching.value) return
  switching.value = true
  account.value = null
  await auth.logout()
  window.location.replace('/login?switch=1')
}

onMounted(loadAccount)
</script>

<template>
  <section class="account-page">
    <header class="page-head">
      <h1>我的账号</h1>
      <t-button :loading="switching" :disabled="switching" variant="outline" @click="switchAccount">切换账号</t-button>
    </header>

    <section class="panel" aria-labelledby="account-info-title" :aria-busy="loading || switching">
      <div class="section-head"><h2 id="account-info-title">账号资料</h2></div>
      <p v-if="switching" role="status">正在退出当前账号…</p>
      <p v-else-if="loading" role="status">正在读取账号信息…</p>
      <div v-else-if="error" class="account-feedback">
        <p role="alert">{{ error }}</p>
        <t-button size="small" variant="outline" @click="loadAccount">重试</t-button>
      </div>
      <dl v-else-if="account" class="account-details">
        <div><dt>用户名</dt><dd>{{ account.username }}</dd></div>
        <div><dt>用户 ID</dt><dd>{{ account.id }}</dd></div>
        <div><dt>部门</dt><dd>{{ account.department || '未分配' }}</dd></div>
        <div>
          <dt>角色</dt>
          <dd v-if="accountAdmin === null" class="account-feedback">
            暂无法确认角色
            <t-button size="small" variant="outline" @click="loadAccount">重试</t-button>
          </dd>
          <dd v-else>{{ accountAdmin ? '管理员' : '用户' }}</dd>
        </div>
      </dl>
    </section>
  </section>
</template>

<style scoped>
.account-page { max-width: 720px; }
.account-details { display: grid; gap: var(--space-3); margin: 0; }
.account-details > div { display: grid; grid-template-columns: 6em minmax(0, 1fr); gap: var(--space-3); }
.account-details dt { color: var(--text-dim); }
.account-details dd { margin: 0; overflow-wrap: anywhere; }
.account-feedback { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-2); }
</style>
