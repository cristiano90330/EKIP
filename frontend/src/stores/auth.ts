/** 维护前端登录状态、到期时间及标签页间的会话同步。 */

import { defineStore } from 'pinia'
import { login as loginApi, logout as logoutApi } from '../api/auth'
import type { LoginVO } from '../types'

export const TOKEN_KEY = 'token'
export const REFRESH_KEY = 'refreshToken'
/** 访问令牌的到期时刻（epoch ms）。后端 LoginVO.expiresIn 早就返回了，此前被 setTokens 丢掉。 */
export const EXPIRES_KEY = 'tokenExpiresAt'
/** 提前换票的安全余量：距 access 过期不足 60 秒就先刷，正常路径上就不该再有 401。 */
export const REFRESH_SKEW_MS = 60_000

/** 从浏览器存储读取访问凭据的失效时刻。由 state、syncFromStorage 触发。 */
const readExpiresAt = () => Number(localStorage.getItem(EXPIRES_KEY) ?? '0')

export const useAuth = defineStore('auth', {
  /** 根据已有浏览器存储初始化认证状态。下游调用 readExpiresAt。 */
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) ?? '',
    refreshToken: localStorage.getItem(REFRESH_KEY) ?? '',
    expiresAt: readExpiresAt(),
  }),
  getters: {
    /** 判断访问凭据是否进入主动续期窗口。接收s。 */
    needsRefresh: (s) => s.expiresAt > 0 && Date.now() >= s.expiresAt - REFRESH_SKEW_MS,
    /** 判断访问凭据是否已超过失效时刻。接收s。 */
    accessExpired: (s) => s.expiresAt > 0 && Date.now() >= s.expiresAt,
  },
  actions: {
    /** 更新当前会话令牌与期限并同步持久状态。接收vo。由 login 触发。 */
    setTokens(vo: LoginVO) {
      this.token = vo.token
      this.refreshToken = vo.refreshToken
      this.expiresAt = Date.now() + vo.expiresIn * 1000
      localStorage.setItem(TOKEN_KEY, vo.token)
      localStorage.setItem(REFRESH_KEY, vo.refreshToken)
      localStorage.setItem(EXPIRES_KEY, String(this.expiresAt))
    },
    /** 清除内存和本地持久会话信息。由 logout 触发。 */
    clearAuth() {
      this.token = ''
      this.refreshToken = ''
      this.expiresAt = 0
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(REFRESH_KEY)
      localStorage.removeItem(EXPIRES_KEY)
    },
    /** 重新读取共享的登录数据并写入当前标签页状态。由 startStorageSync 触发。下游调用 readExpiresAt。 */
    syncFromStorage() {
      this.token = localStorage.getItem(TOKEN_KEY) ?? ''
      this.refreshToken = localStorage.getItem(REFRESH_KEY) ?? ''
      this.expiresAt = readExpiresAt()
    },
    /**
     * 跨标签页同步（家族级联吊销的误伤解药）。
     *
     * A 页刷新会轮换掉刷新票；B 页内存里那份就成了「已消费的旧票」。B 页拿它去刷新，在服务端
     * 与窃取者的重放**完全同形**（签名有效、记录已没了），于是被判重放 → 整族吊销 → 用户全端被踢。
     * storage 事件 + 刷新时现读 localStorage，把这种同形情况压到「两个标签页在同一百毫秒内并发
     * 刷新」才可能发生。
     *
     * storage 事件只在**其它**标签页改动 localStorage 时触发，本页自己的 setTokens 不会回调，
     * 所以不存在自激循环。由 main.ts 在 pinia 装好之后调用一次。
     */

    /** 安装存储变更监听器以同步其他标签页的会话。下游调用 syncFromStorage。 */
    startStorageSync() {
      window.addEventListener('storage', (e) => {
        // e.key === null 是 localStorage.clear()：整份同步（等价于本地被登出）
        if (e.key === null || e.key === TOKEN_KEY || e.key === REFRESH_KEY || e.key === EXPIRES_KEY) {
          this.syncFromStorage()
        }
        const signedOut = e.key === null || ((e.key === TOKEN_KEY || e.key === REFRESH_KEY) && e.newValue === null)
        if (signedOut && window.location.pathname !== '/login') {
          // 其它标签页退出时卸载当前账号页面，避免切换后继续显示上一账号的数据。
          window.location.replace('/login')
        }
      })
    },

    /** 通过认证 API 建立指定类型的账号会话并保存凭据。接收u、p、asAdmin。下游调用 auth 的 login、setTokens。 */
    async login(u: string, p: string, asAdmin = false) {
      const vo = await loginApi(u, p, asAdmin)
      this.setTokens(vo)
    },
    /** 结束远端会话并清理本地认证状态。下游调用 clearAuth、auth 的 logout。 */
    async logout() {
      const rt = this.refreshToken
      this.clearAuth()
      if (rt) {
        try {
          await logoutApi(rt)
        } catch {
          /* 后端不可达也视为已登出；refresh 会在自身过期后自然失效 */
        }
      }
    },
  },
})
