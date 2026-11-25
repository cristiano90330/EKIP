/** 核对登录入口、账号类型提示和令牌失效后的交互文案。 */

import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import ts from 'typescript'
import { computed, ref } from 'vue'

const view = readFileSync(new URL('../src/views/LoginView.vue', import.meta.url), 'utf8')
const script = view.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)?.[1]
assert.ok(script)
const compiled = ts.transpileModule(script.replace(/^import .*$/gm, ''), {
  compilerOptions: { target: ts.ScriptTarget.ES2022 },
}).outputText

/**
 * 构造带假路由和认证 API 的登录表单，记录发出的注册、登录和导航请求。
 * 接收registerResult、query。
 * 由 切换注册与登录时保留用户名，清空密码和旧提示、注册密码长度与后端 6–20 位约束一致，非法输入不发送请求 触发。
 */
function form(registerResult = async () => '注册成功', query: Record<string, string> = {}) {
  const registered: string[][] = []
  const loggedIn: [string, string, boolean][] = []
  const navigated: string[] = []
  const state = new Function('ref', 'computed', 'useRouter', 'useRoute', 'useAuth', 'register', `${compiled}
    return { username, password, confirmPassword, isRegister, isAdmin, loading, error, notice, submit, setMode }`)(
    ref,
    computed,
    () => ({ push: async (path: string) => { navigated.push(path) } }),
    () => ({ query }),
    () => ({ login: async (...credentials: [string, string, boolean]) => { loggedIn.push(credentials) } }),
    async (...credentials: string[]) => { registered.push(credentials); return registerResult() },
  )
  state.username.value = 'test_user'
  return { state, registered, loggedIn, navigated }
}

/** 验证切换注册与登录时保留用户名，清空密码和旧提示，以 assert.equal 检查实际结果。 */
test('切换注册与登录时保留用户名，清空密码和旧提示', () => {
  const { state } = form()
  state.password.value = 'old-password'
  state.confirmPassword.value = 'old-password'
  state.error.value = '旧错误'
  state.notice.value = '旧成功提示'
  state.setMode('register')
  assert.equal(state.isRegister.value, true)
  assert.equal(state.username.value, 'test_user')
  assert.equal(state.password.value, '')
  assert.equal(state.confirmPassword.value, '')
  assert.equal(state.error.value, '')
  assert.equal(state.notice.value, '')
})

/** 验证注册密码长度与后端 6–20 位约束一致，非法输入不发送请求，以 assert.equal 检查实际结果。 */
test('注册密码长度与后端 6–20 位约束一致，非法输入不发送请求', async () => {
  const { state, registered } = form()
  state.setMode('register')
  for (const password of ['12345', 'x'.repeat(21)]) {
    state.password.value = password
    state.confirmPassword.value = password
    await state.submit()
    assert.equal(state.error.value, '密码长度为 6–20 位')
  }
  assert.equal(registered.length, 0)
  assert.equal(state.loading.value, false)
})

/** 验证确认密码不一致时保留表单并阻止注册，以 assert.equal 检查实际结果。 */
test('确认密码不一致时保留表单并阻止注册', async () => {
  const { state, registered } = form()
  state.setMode('register')
  state.password.value = 'Temp123456'
  state.confirmPassword.value = 'Different123'
  await state.submit()
  assert.equal(state.error.value, '两次输入的密码不一致')
  assert.equal(state.isRegister.value, true)
  assert.equal(registered.length, 0)
})

/** 验证注册成功回到登录并清空密码，保留用户名供登录，以 assert.deepEqual 检查实际结果。 */
test('注册成功回到登录并清空密码，保留用户名供登录', async () => {
  for (const password of ['123456', 'x'.repeat(20)]) {
    const { state, registered, loggedIn, navigated } = form()
    state.setMode('register')
    state.password.value = password
    state.confirmPassword.value = password
    await state.submit()
    assert.deepEqual(registered, [['test_user', password]])
    assert.equal(state.isRegister.value, false)
    assert.equal(state.username.value, 'test_user')
    assert.equal(state.password.value, '')
    assert.equal(state.confirmPassword.value, '')
    assert.equal(state.notice.value, '注册成功，请输入密码登录')
    assert.equal(state.loading.value, false)
    assert.equal(loggedIn.length, 0)
    assert.equal(navigated.length, 0)
  }
})

/** 验证重复用户名错误留在注册表单，允许修正后重试，以 assert.equal 检查实际结果。 */
test('重复用户名错误留在注册表单，允许修正后重试', async () => {
  const { state, loggedIn } = form(async () => { throw new Error('用户名已经存在') })
  state.setMode('register')
  state.password.value = 'Temp123456'
  state.confirmPassword.value = 'Temp123456'
  await state.submit()
  assert.equal(state.error.value, '用户名已经存在')
  assert.equal(state.isRegister.value, true)
  assert.equal(state.username.value, 'test_user')
  assert.equal(state.loading.value, false)
  assert.equal(loggedIn.length, 0)
})

/** 验证注册请求进行中阻止再次提交和切换模式，以 assert.equal 检查实际结果。 */
test('注册请求进行中阻止再次提交和切换模式', async () => {
  let finish!: (value: string) => void
  const { state, registered } = form(() => new Promise<string>(resolve => { finish = resolve }))
  state.setMode('register')
  state.password.value = 'Temp123456'
  state.confirmPassword.value = 'Temp123456'
  const pending = state.submit()
  assert.equal(state.loading.value, true)
  await state.submit()
  state.setMode('user')
  assert.equal(registered.length, 1)
  assert.equal(state.isRegister.value, true)
  finish('注册成功')
  await pending
  assert.equal(state.loading.value, false)
})

/** 验证已有账号登录仍进入知识库，以 assert.deepEqual 检查实际结果。 */
test('已有账号登录仍进入知识库', async () => {
  const { state, registered, loggedIn, navigated } = form()
  state.password.value = 'Temp123456'
  await state.submit()
  assert.deepEqual(loggedIn, [['test_user', 'Temp123456', false]])
  assert.deepEqual(navigated, ['/library'])
  assert.equal(registered.length, 0)
})

/** 验证切换账号后登录返回个人页，以 assert.deepEqual 检查实际结果。 */
test('切换账号后登录返回个人页', async () => {
  const { state, navigated } = form(undefined, { switch: '1' })
  state.password.value = 'Temp123456'
  await state.submit()
  assert.deepEqual(navigated, ['/account'])
})

/** 验证管理员登录提交管理员校验并进入账号管理，以 assert.deepEqual 检查实际结果。 */
test('管理员登录提交管理员校验并进入账号管理', async () => {
  const { state, loggedIn, navigated } = form()
  state.setMode('admin')
  state.password.value = 'Temp123456'
  await state.submit()
  assert.deepEqual(loggedIn, [['test_user', 'Temp123456', true]])
  assert.deepEqual(navigated, ['/settings?section=accounts'])
})
