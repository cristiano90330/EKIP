/** 封装注册、普通及管理员登录、续期、登出和个人资料请求。 */

import axios from 'axios'
import { USE_MOCK } from '../config'
import { http } from './http'
import { mockLogin, mockRegister } from './mock'
import type { LoginVO, Result } from '../types'

export interface AccountProfile {
  id: number
  username: string
  department: string | null
}

const mockUsernameKey = 'ekip:mock-username'
const adminAccessPath = '/user/account-admin/access'

// 刷新、登出和登录中的角色校验显式携带各自的凭证，绕开已登录会话的刷新重试。
const authClient = axios.create({ baseURL: '/api', timeout: 15000 })

/**
 * 提交用户名和密码给注册接口并返回受理结果。
 * 接收用户名、密码。
 * 由 LoginView.vue 的 submit 触发。
 * 下游调用 UserController、mock 的 mockRegister。
 */
export const register = (username: string, password: string): Promise<string> =>
  USE_MOCK
    ? mockRegister()
    : http.post<string, string>('/user/register', { username, password })

/**
 * 用指定或当前会话令牌检查账号管理资格。
 * 接收访问令牌，返回布尔判定。
 * 由 login、AccountView.vue 的 loadAccount 触发。
 * 下游调用 AccountAdminController 的管理资格入口、UserController。
 */
export async function getAccountAdminAccess(accessToken?: string): Promise<boolean> {
  if (!accessToken) return http.get<boolean, boolean>(adminAccessPath)

  const { data } = await authClient.get<Result<boolean>>(adminAccessPath, {
    headers: { Authorization: `Bearer ${accessToken}` },
  })
  if (data.code !== 200) throw new Error('管理员权限校验失败。')
  return data.data === true
}

/** 提交凭据完成登录，管理员模式还会核对账号管理资格。接收用户名、密码、asAdmin，返回登录凭据和期限。下游调用 UserController、mock 的 mockLogin。 */
export async function login(username: string, password: string, asAdmin = false): Promise<LoginVO> {
  if (USE_MOCK) {
    if (asAdmin) throw new Error('演示模式未配置管理员。')
    const tokens = await mockLogin(username, password)
    sessionStorage.setItem(mockUsernameKey, username)
    return tokens
  }

  const tokens = await http.post<LoginVO, LoginVO>('/user/login', { username, password })

  if (asAdmin) {
    // 新令牌在管理员校验通过前不会写入浏览器的登录状态。
    let allowed = false
    try {
      allowed = await getAccountAdminAccess(tokens.token)
    } catch {
      throw new Error('暂时无法校验管理员权限，请稍后重试。')
    } finally {
      if (!allowed) void logout(tokens.refreshToken).catch(() => {})
    }
    if (!allowed) throw new Error('该账号没有管理员权限，请使用普通登录。')
  }
  return tokens
}

/**
 * 读取当前令牌所属账号的用户 ID、用户名和部门。
 * 返回 AccountProfile。
 * 由 AccountView.vue 的 loadAccount 触发。
 * 下游调用 UserController.profile、UserController。
 */
export const getAccountProfile = (): Promise<AccountProfile> => USE_MOCK
  ? Promise.resolve({ id: 1, username: sessionStorage.getItem(mockUsernameKey) ?? '演示账号', department: null })
  : http.get<AccountProfile, AccountProfile>('/user/me')

/** 使用刷新令牌续期并接收一组新凭据。接收刷新令牌，返回登录凭据和期限。由 http.ts 的 refreshOnce 触发。 */
export const refresh = async (refreshToken: string): Promise<LoginVO> => {
  const res = await authClient.post<{ data: LoginVO }>('/user/refresh', { refreshToken })
  return res.data.data
}

/** 将刷新令牌交给服务端结束登录会话。接收刷新令牌，返回 unknown。由 login 触发。 */
export const logout = (refreshToken: string): Promise<unknown> =>
  USE_MOCK ? Promise.resolve(sessionStorage.removeItem(mockUsernameKey))
    : authClient.post('/user/logout', { refreshToken })
