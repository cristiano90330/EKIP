<!-- 收集登录和注册输入，选择普通或管理员入口并处理认证结果。 -->

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '../stores/auth'
import { register } from '../api/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuth()

const username = ref('')
const password = ref('')
const confirmPassword = ref('')
type LoginMode = 'user' | 'admin' | 'register'
const mode = ref<LoginMode>('user')
const isRegister = computed(() => mode.value === 'register')
const isAdmin = computed(() => mode.value === 'admin')
const loading = ref(false)
const error = ref('')
const notice = ref('')

/** 切换登录或注册表单并重置旧反馈。接收next。由 当前模板的 @click 事件 触发。 */
function setMode(next: LoginMode) {
  if (loading.value) return
  mode.value = next
  password.value = ''
  confirmPassword.value = ''
  error.value = ''
  notice.value = ''
}

/** 按当前模式提交账号表单，处理认证结果和页面跳转。由 当前模板的 @submit.prevent 事件 触发。下游调用 auth 的 register。 */
async function submit() {
  if (loading.value) return
  error.value = ''
  notice.value = ''
  if (!username.value.trim() || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  if (isRegister.value && (password.value.length < 6 || password.value.length > 20)) {
    error.value = '密码长度为 6–20 位'
    return
  }
  if (isRegister.value && password.value !== confirmPassword.value) {
    error.value = '两次输入的密码不一致'
    return
  }
  loading.value = true
  try {
    if (isRegister.value) {
      await register(username.value, password.value)
      mode.value = 'user'
      password.value = ''
      confirmPassword.value = ''
      notice.value = '注册成功，请输入密码登录'
    } else {
      await auth.login(username.value, password.value, isAdmin.value)
      const destination = isAdmin.value ? '/settings?section=accounts'
        : route.query.switch === '1' ? '/account' : '/library'
      await router.push(destination)
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '操作失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <form class="login-card" @submit.prevent="submit">
      <span class="login-mark" aria-hidden="true">EK</span>
      <h1 class="login-title">{{ isAdmin ? '管理员登录' : 'EKIP 知识平台' }}</h1>
      <p v-if="isAdmin" class="muted login-sub">使用已配置的管理员账号登录账号管理。</p>
      <p v-else class="muted login-sub">{{ isRegister ? '创建账号，开始使用知识平台' : route.query.switch === '1' ? '登录要切换的账号' : '登录后访问知识库与模型设置' }}</p>

      <label class="field">
        <span>用户名</span>
        <t-input
          v-model:value="username"
          autocomplete="username"
          placeholder="请输入用户名"
          :disabled="loading"
        />
      </label>
      <label class="field">
        <span>密码</span>
        <t-input
          v-model:value="password"
          type="password"
          :autocomplete="isRegister ? 'new-password' : 'current-password'"
          :placeholder="isRegister ? '请输入 6–20 位密码' : '请输入密码'"
          :disabled="loading"
        />
      </label>

      <label v-if="isRegister" class="field">
        <span>确认密码</span>
        <t-input
          v-model:value="confirmPassword"
          type="password"
          autocomplete="new-password"
          placeholder="请再次输入密码"
          :disabled="loading"
        />
      </label>

      <t-alert v-if="error" theme="error" :message="error" class="login-alert" role="alert" />
      <t-alert v-if="notice" theme="success" :message="notice" class="login-alert" />

      <t-button theme="primary" type="submit" block class="login-btn" :loading="loading">
        <template v-if="isRegister">{{ loading ? '注册中…' : '注册' }}</template>
        <template v-else-if="isAdmin">{{ loading ? '正在登录…' : '进入账号管理' }}</template>
        <template v-else>{{ loading ? '登录中…' : '登录' }}</template>
      </t-button>
      <div v-if="mode === 'user'" class="login-options">
        <t-button theme="primary" variant="text" type="button" :disabled="loading" @click="setMode('register')">没有账号？注册</t-button>
        <t-button variant="outline" type="button" :disabled="loading" @click="setMode('admin')">管理员登录</t-button>
      </div>
      <t-button v-else theme="primary" variant="text" type="button" :disabled="loading" @click="setMode('user')">返回普通登录</t-button>
    </form>
  </div>
</template>

<style scoped>
.login-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  padding: var(--space-4);
  background: var(--page-gradient);
}

.login-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  width: min(100%, 380px);
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
  padding: var(--space-6);
  background: var(--bg-panel);
  box-shadow: var(--shadow-3);
}

.login-mark {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  margin-bottom: 2px;
  border-radius: 10px;
  background: linear-gradient(140deg, var(--indigo-500), var(--indigo-600));
  color: #fff;
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 700;
}

.login-title {
  margin: 0;
  font-size: var(--fs-2xl);
  letter-spacing: -0.03em;
}

.login-sub {
  margin: -4px 0 var(--space-1);
  font-size: var(--fs-sm);
  line-height: 1.6;
}

.login-btn {
  margin-top: var(--space-1);
}

.login-options {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: var(--space-2);
}
</style>
