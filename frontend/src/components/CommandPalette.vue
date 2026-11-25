<!-- 将应用导航、主题选择和页面操作组织为可搜索的键盘命令。 -->

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { SearchIcon } from 'tdesign-icons-vue-next'
import { useRouter } from 'vue-router'
import { useAuth } from '../stores/auth'
import { setThemePreference, activeTheme } from '../theme'

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ (e: 'update:visible', value: boolean): void }>()

const router = useRouter()
const auth = useAuth()

interface Command {
  id: string
  label: string
  hint: string
  group: '页面' | '操作'
  keywords: string
  run: () => void | Promise<void>
}

const query = ref('')
const activeIndex = ref(0)
const inputEl = ref<HTMLInputElement | null>(null)

const commands = computed<Command[]>(() => [
  {
    id: 'overview', label: '总览', hint: '/overview', group: '页面', keywords: 'overview home 首页 工作台',

    /** 从命令面板导航到总览页。 */
    run: () => { void router.push('/overview') },
  },
  {
    id: 'library', label: '知识库与文档', hint: '/library', group: '页面', keywords: 'library knowledge 上传 文档 知识库',

    /** 从命令面板导航到知识库页。 */
    run: () => { void router.push('/library') },
  },
  {
    id: 'ask', label: '智能问答', hint: '/ask', group: '页面', keywords: 'ask rag 问答 检索 提问',

    /** 从命令面板导航到问答页。 */
    run: () => { void router.push('/ask') },
  },
  {
    id: 'agent', label: 'Agent 助手', hint: '/agent', group: '页面', keywords: 'agent 工具 助手 会话',

    /** 从命令面板导航到Agent 页。 */
    run: () => { void router.push('/agent') },
  },
  {
    id: 'traces', label: '流程 Trace', hint: '/traces', group: '页面', keywords: 'trace 链路 阶段 耗时',

    /** 从命令面板导航到追踪页。 */
    run: () => { void router.push('/traces') },
  },
  {
    id: 'metrics', label: '运行指标', hint: '/metrics', group: '页面', keywords: 'metrics 指标 延迟 token 成本',

    /** 从命令面板导航到指标页。 */
    run: () => { void router.push('/metrics') },
  },
  {
    id: 'status', label: '代码状态', hint: '/status', group: '页面', keywords: 'status 健康 就绪 版本',

    /** 从命令面板导航到服务状态页。 */
    run: () => { void router.push('/status') },
  },
  {
    id: 'settings', label: '设置', hint: '/settings', group: '页面', keywords: 'settings model 模型 凭证 索引 设置',

    /** 从命令面板导航到设置页。 */
    run: () => { void router.push('/settings') },
  },
  {
    id: 'upload', label: '上传文档', hint: '前往知识库', group: '操作', keywords: 'upload 上传 文档',

    /** 从命令面板导航到知识库页。 */
    run: () => { void router.push('/library') },
  },
  {
    id: 'new-ask', label: '开始新的问答', hint: '清空当前输入', group: '操作', keywords: 'new 新会话 提问',

    /** 从命令面板导航到问答页。 */
    run: () => { void router.push({ path: '/ask', query: { new: String(Date.now()) } }) },
  },
  {
    id: 'theme', label: activeTheme.value === 'dark' ? '切换到亮色主题' : '切换到暗色主题',
    hint: '外观', group: '操作', keywords: 'theme 主题 暗色 亮色',

    /** 应用命令选中的主题偏好。下游调用 theme 的 setThemePreference。 */
    run: () => { setThemePreference(activeTheme.value === 'dark' ? 'light' : 'dark') },
  },
  {
    id: 'logout', label: '退出登录', hint: '清除本地票据', group: '操作', keywords: 'logout 退出 登出',

    /** 结束当前账号会话后打开登录页。 */
    run: async (): Promise<void> => {
      await auth.logout()
      void router.push('/login')
    },
  },
])

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return commands.value
  return commands.value.filter((item) =>
    `${item.label} ${item.hint} ${item.keywords}`.toLowerCase().includes(q),
  )
})

watch(filtered, () => {
  activeIndex.value = 0
})

watch(
  () => props.visible,
  async (open) => {
    if (!open) return
    query.value = ''
    activeIndex.value = 0
    await nextTick()
    inputEl.value?.focus()
  },
)

/** 关闭命令面板并通知父组件更新显示状态。由 runActive、onKeydown 触发。 */
function close() {
  emit('update:visible', false)
}

/** 移动当前高亮索引并在命令列表边缘循环。接收step。由 onKeydown 触发。 */
function move(step: number) {
  const total = filtered.value.length
  if (total === 0) return
  activeIndex.value = (activeIndex.value + step + total) % total
}

/** 关闭面板后执行当前高亮命令。由 onKeydown、当前模板的 @click 事件 触发。下游调用 close。 */
async function runActive() {
  const item = filtered.value[activeIndex.value]
  if (!item) return
  close()
  await item.run()
}

/**
 * 把方向键、回车和退出键映射到命令操作。
 * 接收键盘事件。
 * 由 当前模板的 @keydown 事件 触发。
 * 下游调用 move、runActive。
 */
function onKeydown(e: KeyboardEvent) {
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    move(1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    move(-1)
  } else if (e.key === 'Enter') {
    e.preventDefault()
    void runActive()
  } else if (e.key === 'Escape') {
    e.preventDefault()
    close()
  }
}
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="palette-mask" @click.self="close">
      <div class="palette" role="dialog" aria-modal="true" aria-label="命令面板">
        <div class="p-search">
          <SearchIcon size="18px" />
          <input
            ref="inputEl"
            v-model="query"
            type="text"
            placeholder="搜索页面或操作…"
            aria-label="搜索页面或操作"
            @keydown="onKeydown"
          />
          <kbd>Esc</kbd>
        </div>

        <ul v-if="filtered.length" class="p-list">
          <li
            v-for="(item, index) in filtered"
            :key="item.id"
            class="p-item"
            :class="{ on: index === activeIndex }"
            @mouseenter="activeIndex = index"
            @click="runActive"
          >
            <span class="p-group">{{ item.group }}</span>
            <span class="p-label">{{ item.label }}</span>
            <span class="p-hint">{{ item.hint }}</span>
          </li>
        </ul>
        <p v-else class="p-empty">没有匹配的命令</p>

        <div class="p-foot">
          <span><kbd>↑</kbd><kbd>↓</kbd> 选择</span>
          <span><kbd>↵</kbd> 执行</span>
          <span><kbd>Esc</kbd> 关闭</span>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.palette-mask {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  justify-content: center;
  padding-top: 14vh;
  background: rgba(17, 17, 20, 0.32);
  backdrop-filter: blur(2px);
}

.palette {
  display: flex;
  flex-direction: column;
  width: min(92vw, 560px);
  max-height: 62vh;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
  background: var(--bg-panel);
  box-shadow: var(--shadow-dialog);
}

.p-search {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--border);
  color: var(--text-faint);
}

.p-search input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: none;
  background: transparent;
  color: var(--text);
  font: inherit;
  font-size: var(--fs-md);
}

.p-search input::placeholder {
  color: var(--text-faint);
}

.p-search kbd {
  padding: 3px 6px;
  border-radius: var(--radius-xs);
  background: var(--bg-inset);
  color: var(--text-faint);
  font-size: 10px;
}

.p-list {
  flex: 1;
  margin: 0;
  padding: 8px;
  overflow: auto;
  list-style: none;
}

.p-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border-radius: var(--radius-md);
  cursor: pointer;
}

.p-item.on {
  background: var(--accent-soft);
}

.p-group {
  flex: none;
  min-width: 32px;
  color: var(--text-faint);
  font-size: var(--fs-micro);
}

.p-item.on .p-group {
  color: var(--accent);
}

.p-label {
  color: var(--text);
  font-size: var(--fs-base);
}

.p-item.on .p-label {
  color: var(--accent);
  font-weight: var(--fw-semi);
}

.p-hint {
  margin-left: auto;
  overflow: hidden;
  color: var(--text-faint);
  font-family: var(--font-mono);
  font-size: var(--fs-micro);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.p-empty {
  padding: 28px 16px;
  color: var(--text-dim);
  font-size: var(--fs-base);
  text-align: center;
}

.p-foot {
  display: flex;
  gap: 16px;
  padding: 10px 16px;
  border-top: 1px solid var(--border);
  background: var(--bg-panel-2);
  color: var(--text-faint);
  font-size: var(--fs-micro);
}

.p-foot kbd {
  margin-right: 3px;
  padding: 2px 5px;
  border-radius: 4px;
  background: var(--bg-inset);
  color: var(--text-dim);
  font-size: 9.5px;
}
</style>
