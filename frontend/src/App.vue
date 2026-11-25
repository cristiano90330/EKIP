<!-- 渲染全站应用外壳、导航、账号及主题菜单，并连接全局快捷操作。 -->

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { RouterView, RouterLink, useRoute, useRouter } from 'vue-router'
import {
  ChevronDownIcon,
  DesktopIcon,
  LogoutIcon,
  MoonIcon,
  SearchIcon,
  SunnyIcon,
  UserCircleIcon,
} from 'tdesign-icons-vue-next'
import { useAuth } from './stores/auth'
import { setThemePreference, THEME_LABELS, themePreference, type ThemePreference } from './theme'
import CommandPalette from './components/CommandPalette.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuth()

const isLoginPage = computed(() => route.path === '/login')

/** 从应用外壳发起账号登出并恢复登录入口。由 当前模板的 @click 事件 触发。 */
async function logout() {
  await auth.logout()
  router.push('/login')
}

// ---------- 主导航 ----------
// 页面数量从 7 项压到 6 项：Trace / 指标 / 状态 收进「观测」下拉，
// 与 B 的「顶栏胶囊 + 命令面板」一致，避免顶栏被功能清单撑满。
const NAV = [
  { to: '/overview', label: '总览' },
  { to: '/library', label: '知识库' },
  { to: '/ask', label: '问答' },
  { to: '/agent', label: 'Agent' },
]

const OBSERVE_PATHS = ['/traces', '/metrics', '/status'] as const
const observing = computed(() => OBSERVE_PATHS.includes(route.path as (typeof OBSERVE_PATHS)[number]))

const OBSERVE_OPTIONS = [
  { value: '/traces', content: '流程 Trace' },
  { value: '/metrics', content: '运行指标' },
  { value: '/status', content: '代码状态' },
]

/** 将观测菜单中的选项映射到对应页面路由。接收option。由 当前模板的 @click 事件 触发。 */
function onObserveSelect(option: { value: string }) {
  router.push(option.value)
}

// ---------- 命令面板 ----------
const paletteOpen = ref(false)

/** 在应用外壳监听命令面板快捷键并切换展开状态。接收键盘事件。 */
function onGlobalKeydown(e: KeyboardEvent) {
  if (!(e.metaKey || e.ctrlKey) || e.key.toLowerCase() !== 'k') return
  e.preventDefault()
  paletteOpen.value = !paletteOpen.value
}

onMounted(() => window.addEventListener('keydown', onGlobalKeydown))
onUnmounted(() => window.removeEventListener('keydown', onGlobalKeydown))

// ---------- 主题切换 ----------
// 三态：跟随系统 / 锁定亮色 / 锁定暗色。选择结果写入 localStorage（system 则清空）。
const THEME_ICONS = {
  system: DesktopIcon,
  light: SunnyIcon,
  dark: MoonIcon,
}

const themeLabel = computed(() => THEME_LABELS[themePreference.value])
const themeIcon = computed(() => THEME_ICONS[themePreference.value])

const themeOptions = computed(() =>
  (['system', 'light', 'dark'] as ThemePreference[]).map((value) => ({
    value,
    content: THEME_LABELS[value],
    active: themePreference.value === value,
  })),
)

/**
 * 把菜单选择交给主题模块保存并应用。
 * 接收option。
 * 由 当前模板的 @click 事件 触发。
 * 下游调用 theme 的 setThemePreference。
 */
function onThemeSelect(option: { value: ThemePreference }) {
  setThemePreference(option.value)
}
</script>

<template>
  <div v-if="!isLoginPage" class="shell">
    <header class="topbar">
      <RouterLink to="/overview" class="brand">
        <span class="brand-mark" aria-hidden="true">EK</span>
        <span>知识平台</span>
      </RouterLink>

      <nav class="nav" aria-label="主导航">
        <RouterLink
          v-for="item in NAV"
          :key="item.to"
          :to="item.to"
          class="nav-pill"
          active-class="active"
        >
          {{ item.label }}
        </RouterLink>

        <t-dropdown
          trigger="click"
          placement="bottom-left"
          :options="OBSERVE_OPTIONS"
          @click="onObserveSelect"
        >
          <button type="button" class="nav-pill" :class="{ active: observing }">
            观测
            <ChevronDownIcon size="14px" />
          </button>
        </t-dropdown>

        <RouterLink to="/settings" class="nav-pill" active-class="active">设置</RouterLink>
      </nav>

      <button type="button" class="search-trigger" @click="paletteOpen = true">
        <SearchIcon />
        <span>搜索页面或操作</span>
        <kbd>Ctrl K</kbd>
      </button>

      <div class="topbar-actions">
        <t-dropdown trigger="click" placement="bottom-right" :options="themeOptions" @click="onThemeSelect">
          <button
            class="icon-btn"
            type="button"
            :title="`主题：${themeLabel}（点击切换）`"
            :aria-label="`切换主题，当前为${themeLabel}`"
          >
            <component :is="themeIcon" />
          </button>
        </t-dropdown>
        <RouterLink to="/account" class="icon-btn" title="我的账号" aria-label="我的账号">
          <UserCircleIcon />
        </RouterLink>
        <button class="icon-btn" type="button" title="退出登录" aria-label="退出登录" @click="logout">
          <LogoutIcon />
        </button>
      </div>
    </header>

    <div class="body">
      <main class="content">
        <RouterView />
      </main>
    </div>

    <CommandPalette v-model:visible="paletteOpen" />
  </div>
  <RouterView v-else />
</template>
