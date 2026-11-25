/** 统一业务请求鉴权与响应解包，协调令牌刷新和失效后的登录跳转。 */

import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { REFRESH_KEY, useAuth } from '../stores/auth'
import router from '../router'
import { refresh } from './auth'
import type { Result } from '../types'

/** 用有效访问令牌构造请求授权头。接收t。由 onBusinessError 触发。 */
const authHeader = (t: string) => (t ? { Authorization: `Bearer ${t}` } : {})

// 账户公开端点直接发送请求，失败时向页面报告原始错误。
/** 识别不应递归触发会话续期的认证端点。接收url。由 onBusinessError 触发。 */
const isAuthEndpoint = (url?: string) =>
  !!url && (url.includes('/user/register') || url.includes('/user/login') || url.includes('/user/refresh') || url.includes('/user/logout'))

// —— 单飞刷新：同一批并发 401 / 同一批提前刷新只发一次 /user/refresh，其余复用同一个 promise ——
let inflight: Promise<string | null> | null = null

/**
 * 复用并发请求共享的刷新操作，回传新令牌或刷新失败状态。
 * 返回文本或空值。
 * 由 ensureFresh、onBusinessError 触发。
 * 下游调用 auth 的 useAuth、auth 的 refresh。
 */
function refreshOnce(): Promise<string | null> {
  if (!inflight) {
    const auth = useAuth()
    // 现读 localStorage 而不是用 auth.refreshToken：本页内存态可能已被另一个标签页轮换掉，
    // 拿那张陈旧票去刷 = 在服务端与窃取者的重放同形 → 整族被吊销（stores/auth.ts 的同步注释）。
    // 捕获发起续期时的令牌，等响应返回后再检查账号是否已被切换。
    const rt = localStorage.getItem(REFRESH_KEY) ?? ''
    const task = rt
      ? refresh(rt)
          .then((vo) => {
            // 登出或切换账号后，旧刷新请求即使成功也不能写回令牌。
            if (localStorage.getItem(REFRESH_KEY) !== rt) return null
            auth.setTokens(vo)
            return vo.token
          })
          .catch(() => null)
      : Promise.resolve<string | null>(null)
    inflight = task.finally(() => {
      inflight = null
    })
  }
  return inflight
}

/** 清除本地会话并把页面导航到登录入口。由 ensureFresh、onUnauthorized 触发。下游调用 auth 的 useAuth。 */
function forceLogout() {
  // 刷新都失败了 = refresh 票据已失效，只清本地、不必再打后端登出接口
  useAuth().clearAuth()
  if (router.currentRoute.value.path !== '/login') router.push('/login')
}

/**
 * 发请求前的惰性提前刷新（3.1 P2-16 收尾）：access 快过期就先静默换票，
 * 让 401 在正常路径上根本不出现（此前每个失效周期都要有真实请求撞一次 401）。
 *
 * 用「请求前惰性检查」而不是后台 setInterval：后台标签页的定时器被浏览器节流，
 * 而且用户不操作时刷新是纯浪费。401 被动刷新链路原样保留作兜底。
 */

/** 在发送普通请求前处理接近过期的会话。下游调用 auth 的 useAuth、refreshOnce。 */
async function ensureFresh(): Promise<void> {
  const auth = useAuth()
  // 未登录 / 本地无令牌态（expiresAt 为 0）→ 不刷新，直接放行
  if (!auth.token || !auth.needsRefresh) return

  const stillValidBeforeRefresh = !auth.accessExpired
  const refreshToken = localStorage.getItem(REFRESH_KEY)
  const newToken = await refreshOnce()
  if (newToken) return
  if (localStorage.getItem(REFRESH_KEY) !== refreshToken) throw new Error('登录状态已变更，请重试')
  // 提前刷新失败但旧 access 自身还没过期 → 照常带旧票发出去，把判断权交回 401 兜底链，
  // 不提前登出（否则一次后端抖动就会把正在打字的用户踢回登录页）
  if (stillValidBeforeRefresh) return
  forceLogout()
  throw new Error('登录已过期，请重新登录')
}

type Retryable = InternalAxiosRequestConfig & { _retried?: boolean }

// raw 客户端对非 Result 响应只做 401 兜底，不在这里解包业务错误。
/** 按请求是否已经重试处理认证失败，避免重复刷新和重放。接收err。下游调用 forceLogout。 */
const onUnauthorized = (err: AxiosError) => {
  if (err.response?.status === 401) forceLogout()
  return Promise.reject(err)
}

// 业务错误带真实 HTTP 状态码（400/403/422/500…），axios 走 reject 分支，
// 若不从 Result 体里取 message，调用方只能看到 "Request failed with status code 422"。
// 401 特殊处理：先静默刷新换回新票并「重放原请求一次」，刷新失败才登出跳登录。
/** 对可续期的业务错误重放请求，其余失败交还页面调用方。接收err。下游调用 isAuthEndpoint、refreshOnce。 */
const onBusinessError = async (err: AxiosError<Result<unknown>>) => {
  const cfg = err.config as Retryable | undefined
  const status = err.response?.status

  // 给同一业务请求最多一次重放机会，认证接口失败直接交给调用方处理。
  if (status === 401 && cfg && !cfg._retried && !isAuthEndpoint(cfg.url)) {
    cfg._retried = true
    const refreshToken = localStorage.getItem(REFRESH_KEY)
    const newToken = await refreshOnce()
    if (newToken) {
      Object.assign(cfg.headers, authHeader(newToken))
      return http(cfg) // 重放：走同一个 http 实例，享受解包与错误文案
    }
    if (localStorage.getItem(REFRESH_KEY) !== refreshToken) throw new Error('登录状态已变更，请重试')
    forceLogout()
    return Promise.reject(new Error('登录已过期，请重新登录'))
  }

  const message = err.response?.data?.message
  if (message) err.message = message
  // 保留 AxiosError 的 response/status/config，让上传等调用方可以按真实 HTTP
  // 状态区分确定拒绝与结果未知，并决定是否需要用原请求键查询回执。
  return Promise.reject(err)
}

// 业务端点：后端统一 Result<T> 包装 → 成功解包 data、失败抛错
export const http = axios.create({ baseURL: '/api', timeout: 15000 })
http.interceptors.request.use(async (cfg) => {
  if (!isAuthEndpoint(cfg.url)) {
    await ensureFresh()
    // 提前刷新可能更新令牌，发送业务请求时重新读取。
    Object.assign(cfg.headers, authHeader(useAuth().token))
  }
  return cfg
})
http.interceptors.response.use(
  (res) => {
    if (res.data.code !== 200) throw new Error(res.data.message ?? '请求失败')
    return res.data.data
  },
  onBusinessError,
)

// 非 Result 响应端点用：保留 AxiosResponse，由调用方读取 response.data。
// 当前供 Actuator 端点和直接返回问答 DTO 的 /knowledge/ask 使用。
// 3.1 P1-15 已收口：`/actuator/**` 的管理面（metrics / loggers / env）现在要认证，
// 所以本实例与 http 一样挂 ensureFresh——观测页常常长时间开着，access 票 15 分钟过期后
// 不提前换就会 401 被 forceLogout 踢登出。（原注释预告的正是这一步。）
// health / info / prometheus 仍然 permitAll，ensureFresh 只在临期才发请求，白挂的成本可忽略。
export const raw = axios.create({ baseURL: '/api', timeout: 15000 })
raw.interceptors.request.use(async (cfg) => {
  await ensureFresh()
  Object.assign(cfg.headers, authHeader(useAuth().token))
  return cfg
})
raw.interceptors.response.use(undefined, onUnauthorized)
