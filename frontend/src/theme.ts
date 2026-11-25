/** 保存用户主题偏好，解析系统明暗模式并同步到页面根节点。 */

/**
 * 主题模式：默认跟随系统，允许在顶栏手动锁定亮色 / 暗色。
 *
 * 真正的色值在 styles/tokens.css（自有组件）与 styles/tdesign.css（TDesign）里，
 * 这里只负责把 <html theme-mode> 与「偏好 + 系统设置」的组合结果保持同步。
 * index.html 里有一段等价的内联脚本，在首屏渲染前预置属性，避免闪白/闪黑；
 * 两处读取同一个 localStorage 键，改动必须同步。
 */

import { ref } from 'vue'

export type ThemeMode = 'dark' | 'light'
/** system = 跟随系统；light / dark = 用户显式锁定 */
export type ThemePreference = 'system' | ThemeMode

export const THEME_LABELS: Record<ThemePreference, string> = {
  system: '跟随系统',
  light: '亮色',
  dark: '暗色',
}

/** 与 index.html 一致：只存 'light' / 'dark'，'system' 视为未设置 */
export const THEME_STORAGE_KEY = 'ekip-theme'

const LIGHT_QUERY = '(prefers-color-scheme: light)'

/** 从浏览器媒体查询派生实际系统明暗模式。返回 ThemeMode。由 resolveTheme、initTheme 触发。 */
export function resolveSystemTheme(): ThemeMode {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return 'light'
  return window.matchMedia(LIGHT_QUERY).matches ? 'light' : 'dark'
}

/** 读取本地保存的主题偏好，对无效值使用应用默认值。返回 ThemePreference。 */
function readPreference(): ThemePreference {
  try {
    const stored = window.localStorage.getItem(THEME_STORAGE_KEY)
    if (stored === 'light' || stored === 'dark') return stored
  } catch {
    /* 隐私模式下 localStorage 可能抛错，按跟随系统处理 */
  }
  return 'system'
}

/** 当前偏好（供顶栏绑定） */
export const themePreference = ref<ThemePreference>(readPreference())

/** 实际生效的模式，即 <html theme-mode> 的值 */
export const activeTheme = ref<ThemeMode>(
  themePreference.value === 'system' ? resolveSystemTheme() : themePreference.value,
)

/**
 * 把固定或跟随系统的偏好解析为明暗主题。
 * 接收preference，返回 ThemeMode。
 * 由 setThemePreference、initTheme 触发。
 * 下游调用 resolveSystemTheme。
 */
export function resolveTheme(preference: ThemePreference = themePreference.value): ThemeMode {
  return preference === 'system' ? resolveSystemTheme() : preference
}

/** 在文档根节点写入当前主题标记，供组件和样式变量读取。接收mode。由 setThemePreference、initTheme 触发。 */
export function applyTheme(mode: ThemeMode): void {
  activeTheme.value = mode
  document.documentElement.setAttribute('theme-mode', mode)
}

/**
 * 保存用户选择并立即同步实际主题。
 * 接收preference。
 * 由 App.vue 的 onThemeSelect、CommandPalette.vue 的 run 触发。
 * 下游调用 applyTheme、resolveTheme。
 */
export function setThemePreference(preference: ThemePreference): void {
  themePreference.value = preference
  try {
    if (preference === 'system') window.localStorage.removeItem(THEME_STORAGE_KEY)
    else window.localStorage.setItem(THEME_STORAGE_KEY, preference)
  } catch {
    /* 存不下只影响下次打开时的偏好，不影响本次生效 */
  }
  applyTheme(resolveTheme(preference))
}

/** 应用启动主题并注册系统主题订阅，返回解除订阅的方法。返回 () => void。下游调用 applyTheme、resolveTheme。 */
export function initTheme(): () => void {
  applyTheme(resolveTheme())

  if (typeof window.matchMedia !== 'function') return () => {}

  const media = window.matchMedia(LIGHT_QUERY)

  /** 在跟随系统模式下更新页面根节点主题。由 initTheme 触发。下游调用 applyTheme、resolveSystemTheme。 */
  const onSystemChange = () => {
    if (themePreference.value === 'system') applyTheme(resolveSystemTheme())
  }

  media.addEventListener('change', onSystemChange)
  return () => media.removeEventListener('change', onSystemChange)
}
