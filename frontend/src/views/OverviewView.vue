<!-- 展示产品总览的依赖和使用数据，提供快捷问答及知识库跳转。 -->

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  getCapabilities,
  listTraces,
} from '../api/observability'
import { getMetric, getMetrics } from '../api/actuator'
import { listDocuments, listKnowledgeBases } from '../api/library'
import type {
  CapabilityReadinessResponseDTO,
  DocumentDTO,
  KnowledgeBaseDTO,
  MetricDetailDTO,
  TraceSummaryDTO,
} from '../types'

const router = useRouter()

const knowledgeBases = ref<KnowledgeBaseDTO[]>([])
const documents = ref<DocumentDTO[]>([])
const capabilities = ref<CapabilityReadinessResponseDTO | null>(null)
const traces = ref<TraceSummaryDTO[]>([])
const metrics = ref<Record<string, MetricDetailDTO>>({})
const loading = ref(true)
const loadWarnings = ref<string[]>([])

const draft = ref('')

let timer: ReturnType<typeof setInterval> | null = null

/** 刷新总览的健康、追踪和模型指标读数。下游调用 library 的 listKnowledgeBases、observability 的 getCapabilities。 */
async function load() {
  const warnings: string[] = []
  const [kbResult, capResult, traceResult] = await Promise.allSettled([
    listKnowledgeBases(),
    getCapabilities(),
    listTraces(50),
  ])

  if (kbResult.status === 'fulfilled') {
    knowledgeBases.value = kbResult.value
    // 总览只看一个知识库的文档：取第一个，避免 N 个库各拉一页
    const first = kbResult.value[0]
    if (first) {
      const docResult = await listDocuments(first.id).catch(() => null)
      if (docResult) documents.value = docResult.records
      else {
        documents.value = []
        warnings.push('文档列表')
      }
    } else {
      documents.value = []
    }
  } else {
    knowledgeBases.value = []
    documents.value = []
    warnings.push('知识库列表')
  }
  if (capResult.status === 'fulfilled') capabilities.value = capResult.value
  else {
    capabilities.value = null
    warnings.push('能力就绪状态')
  }
  if (traceResult.status === 'fulfilled') traces.value = traceResult.value
  else {
    traces.value = []
    warnings.push('请求 Trace')
  }

  if (!await loadMetrics()) warnings.push('运行指标')
  loadWarnings.value = warnings
  loading.value = false
}

// 只取三张卡需要的三个指标，接口不可用就留空，不阻塞整页
/**
 * 按指标列表读取总览需要的观测读数。
 * 返回布尔判定。
 * 由 load 触发。
 * 下游调用 actuator 的 getMetrics、actuator 的 getMetric。
 */
async function loadMetrics(): Promise<boolean> {
  try {
    const { names } = await getMetrics()
    const wanted = ['rag.generation.latency', 'rag.cost.usd', 'rag.requests.total'].filter((name) =>
      names.includes(name),
    )
    const details = await Promise.all(wanted.map((name) => getMetric(name).catch(() => null)))
    const next: Record<string, MetricDetailDTO> = {}
    for (const detail of details) if (detail) next[detail.name] = detail
    metrics.value = next
    return details.every((detail) => detail !== null)
  } catch {
    metrics.value = {}
    return false
  }
}

onMounted(() => {
  void load()
  timer = setInterval(() => {
    void load()
  }, 15_000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

// ---------- 派生数据 ----------
const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了'
  if (hour < 12) return '上午好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

const today = computed(() =>
  new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }),
)

const readyCount = computed(() => documents.value.filter((doc) => doc.status === 2).length)
const pendingDocuments = computed(() => documents.value.filter((doc) => doc.status !== 2))
// 卡片高度固定 160px，放 3 条会溢出；第 3 份之后交给「查看全部」
const recentDocuments = computed(() => documents.value.slice(0, 2))

const capabilityRows = computed(() => {
  const value = capabilities.value
  if (!value) return { ready: 0, total: 0 }
  const items = Object.values(value.capabilities).filter(Boolean)
  return {
    ready: items.filter((item) => item?.status === 'ready').length,
    total: items.length,
  }
})

const readinessLabel = computed(() => {
  switch (capabilities.value?.status) {
    case 'ready':
      return 'READY'
    case 'degraded':
      return 'DEGRADED'
    case 'not_ready':
      return 'NOT READY'
    default:
      return '—'
  }
})

const readinessTone = computed(() => {
  switch (capabilities.value?.status) {
    case 'ready':
      return 'ok'
    case 'degraded':
      return 'warn'
    case 'not_ready':
      return 'err'
    default:
      return 'warn'
  }
})

/** 按指标名和统计项读取用于总览计算的值。接收名称、statistic，返回数值或空值。 */
function metricValue(name: string, statistic: 'COUNT' | 'VALUE' | 'MAX' | 'TOTAL_TIME'): number | null {
  const detail = metrics.value[name]
  if (!detail) return null

  const hit = detail.measurements.find((item) => item.statistic === statistic)
  return hit ? hit.value : null
}

/** 用已有计时统计格式化生成延迟。返回文本。 */
function formatGenerationLatency(): string {
  const detail = metrics.value['rag.generation.latency']
  if (!detail) return '—'

  const max = detail.measurements.find((item) => item.statistic === 'MAX')
  const total = detail.measurements.find((item) => item.statistic === 'TOTAL_TIME')
  const count = detail.measurements.find((item) => item.statistic === 'COUNT')
  const seconds = max && max.value > 0
    ? max.value
    : total && count && count.value > 0
      ? total.value / count.value
      : null
  if (seconds == null) return '—'
  return seconds >= 1 ? `${seconds.toFixed(2)}s` : `${Math.round(seconds * 1000)}ms`
}

const requestTotal = computed(() => {
  const value = metricValue('rag.requests.total', 'COUNT') ?? metricValue('rag.requests.total', 'VALUE')
  return value == null ? '—' : Math.round(value).toLocaleString()
})

const costValue = computed(() => {
  const value = metricValue('rag.cost.usd', 'COUNT') ?? metricValue('rag.cost.usd', 'VALUE')
  return value == null ? '—' : `$${value.toFixed(2)}`
})

// 近 1 小时请求分布：用最近 50 条 trace 的起始时刻分 12 桶，不造假数据
const TREND_BUCKETS = 12
const trend = computed(() => {
  const windowMs = 60 * 60 * 1000
  const step = windowMs / TREND_BUCKETS
  const base = Date.now() - windowMs
  const buckets = new Array<number>(TREND_BUCKETS).fill(0)
  for (const trace of traces.value) {
    const index = Math.floor((trace.startTime - base) / step)
    if (index >= 0 && index < TREND_BUCKETS) buckets[index] += 1
  }
  return buckets
})

const trendMax = computed(() => Math.max(1, ...trend.value))
const trendTotal = computed(() => trend.value.reduce((sum, value) => sum + value, 0))

const sparkLine = computed(() => {
  const data = trend.value
  const width = 300
  const height = 56
  const pad = 5
  const stepX = width / (data.length - 1)
  return data
    .map((value, index) => {
      const x = (index * stepX).toFixed(1)
      const y = (height - pad - (value / trendMax.value) * (height - pad * 2)).toFixed(1)
      return `${index === 0 ? 'M' : 'L'}${x},${y}`
    })
    .join(' ')
})

const sparkArea = computed(() => `${sparkLine.value} L300,56 L0,56 Z`)

// ---------- 交互 ----------
/** 把快捷提问交给问答页。由 当前模板的 @submit.prevent 事件 触发。 */
function ask() {
  const question = draft.value.trim()
  router.push(question ? { path: '/ask', query: { q: question } } : '/ask')
}

/** 从总览打开指定知识库的问答入口。由 当前模板的 @click 事件 触发。 */
function openDocumentAsk() {
  router.push('/ask')
}
</script>

<template>
  <section class="overview">
    <header class="page-head">
      <div>
        <h1>{{ greeting }}</h1>
        <p>
          {{ today }}
          <span v-if="documents.length">· {{ readyCount }} 份文档可用于问答</span>
          <span v-if="pendingDocuments.length">· {{ pendingDocuments.length }} 份等待解析</span>
        </p>
      </div>
      <div class="page-head-actions">
        <t-button theme="primary" @click="router.push('/library')">
          上传文档
          <template #suffix><span aria-hidden="true">＋</span></template>
        </t-button>
      </div>
    </header>

    <t-alert v-if="loadWarnings.length" theme="warning" class="overview-alert" role="status">
      部分总览数据暂时无法读取：{{ loadWarnings.join('、') }}。其余数据仍可查看，请稍后刷新。
    </t-alert>

    <div class="bento">
      <!-- 快速提问：2×2，主任务 -->
      <section class="card hero">
        <div class="card-cap">Quick ask</div>
        <h2>问点什么？<br />答案会带上<em>可核对的原文</em>。</h2>
        <form class="askbox" @submit.prevent="ask">
          <input
            v-model="draft"
            type="text"
            :maxlength="500"
            placeholder="例如：父子块在什么场景下才生效？"
            aria-label="提问"
          />
          <div class="askbox-foot">
            <span class="pill">{{ knowledgeBases[0]?.name ?? '尚未创建知识库' }}</span>
            <span v-if="knowledgeBases.length > 1" class="pill">+{{ knowledgeBases.length - 1 }}</span>
            <t-button theme="primary" type="submit" size="small">提问 →</t-button>
          </div>
        </form>
        <div class="suggests">
          <button type="button" @click="draft = '文档上传后如何变成可检索片段？'">上传后如何变成片段</button>
          <button type="button" @click="draft = 'Rerank 模型在问答流程里起什么作用？'">rerank 有什么作用</button>
          <button type="button" @click="draft = '向量索引重建需要多久？'">索引重建要多久</button>
        </div>
      </section>

      <!-- 待处理 -->
      <section class="card stat">
        <div class="card-head">
          <span class="card-cap">待处理</span>
          <span class="card-link" @click="router.push('/library')">详情</span>
        </div>
        <div class="big">{{ pendingDocuments.length }}</div>
        <p class="note">
          <template v-if="pendingDocuments.length">未完成解析的文档，问答暂不可用</template>
          <template v-else-if="loading">正在读取…</template>
          <template v-else>全部文档已就绪</template>
        </p>
      </section>

      <!-- 能力就绪 -->
      <section class="card stat tint">
        <div class="card-head">
          <span class="card-cap">能力就绪</span>
          <span class="card-link">{{ readinessLabel }}</span>
        </div>
        <div class="big accent">{{ capabilityRows.ready }}/{{ capabilityRows.total || '—' }}</div>
        <p class="note">
          <span class="dot" :class="readinessTone" />
          依赖、检索、生成、证据门
        </p>
      </section>

      <!-- 最近文档 -->
      <section class="card wide">
        <div class="card-head">
          <span class="card-cap">最近文档</span>
          <span class="card-link" @click="router.push('/library')">
            查看全部 {{ documents.length }} 份
          </span>
        </div>
        <ul v-if="recentDocuments.length" class="doc-list">
          <li v-for="doc in recentDocuments" :key="doc.id" @click="openDocumentAsk">
            <span class="doc-badge">{{ (doc.fileType ?? 'doc').slice(0, 3).toUpperCase() }}</span>
            <span class="doc-name">
              {{ doc.fileName }}
              <small>文档 #{{ doc.id }}</small>
            </span>
            <span class="doc-state" :class="doc.status === 2 ? 'ok' : 'warn'">
              {{ doc.status === 2 ? '已完成' : doc.status === 1 ? '解析中' : '待解析' }}
            </span>
          </li>
        </ul>
        <p v-else class="note">{{ loading ? '正在读取文档…' : '还没有文档，先去知识库上传第一份资料。' }}</p>
      </section>

      <!-- 请求分布 -->
      <section class="card wide">
        <div class="card-head">
          <span class="card-cap">近 1 小时请求分布</span>
          <span class="card-link">{{ trendTotal }} 条 Trace</span>
        </div>
        <div class="spark">
          <svg viewBox="0 0 300 56" preserveAspectRatio="none" role="img" aria-label="近一小时请求分布">
            <defs>
              <linearGradient id="trend-fill" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stop-color="var(--accent)" stop-opacity="0.22" />
                <stop offset="100%" stop-color="var(--accent)" stop-opacity="0" />
              </linearGradient>
            </defs>
            <path :d="sparkArea" fill="url(#trend-fill)" />
            <path :d="sparkLine" fill="none" stroke="var(--accent)" stroke-width="2" stroke-linejoin="round" stroke-linecap="round" />
          </svg>
        </div>
        <p class="note">
          <template v-if="trendTotal">按最近 50 条 Trace 的起始时刻分桶</template>
          <template v-else>最近一小时没有命中记录，先提一个问题。</template>
        </p>
      </section>

      <!-- 生成延迟 -->
      <section class="card stat">
        <div class="card-head"><span class="card-cap">生成延迟</span></div>
        <div class="big">{{ formatGenerationLatency() }}</div>
        <p class="note">rag.generation.latency</p>
      </section>

      <!-- 成本 -->
      <section class="card stat dark">
        <div class="card-head">
          <span class="card-cap">成本 / 请求量</span>
          <span class="card-link" @click="router.push('/metrics')">明细</span>
        </div>
        <div class="big">{{ costValue }}</div>
        <p class="note">{{ requestTotal }} 次请求累计</p>
      </section>
    </div>
  </section>
</template>

<style scoped>
.overview {
  display: flex;
  flex-direction: column;
  min-height: 100%;
}

.bento {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  grid-auto-rows: 160px;
  gap: var(--space-4);
}

.card {
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
}

.c2 {
  grid-column: span 2;
}

.r2 {
  grid-row: span 2;
}

/* 快速提问占 2×2 */
.hero {
  grid-column: span 2;
  grid-row: span 2;
  padding: var(--space-5);
}

.hero h2 {
  margin-top: var(--space-3);
  font-size: 23px;
  font-weight: var(--fw-bold);
  line-height: 1.35;
  letter-spacing: -0.03em;
}

.hero h2 em {
  color: var(--accent);
  font-style: normal;
}

.askbox {
  margin-top: auto;
  padding: 12px 12px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--bg-panel);
}

.askbox input {
  width: 100%;
  border: 0;
  outline: none;
  background: transparent;
  color: var(--text);
  font: inherit;
  font-size: var(--fs-base);
}

.askbox input::placeholder {
  color: var(--text-faint);
}

.askbox-foot {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--border);
}

.askbox-foot .pill {
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.askbox-foot .t-button {
  margin-left: auto;
}

.suggests {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.suggests button {
  padding: 5px 10px;
  border: 0;
  border-radius: var(--radius-full);
  background: var(--accent-soft);
  color: var(--accent);
  font: inherit;
  font-size: var(--fs-micro);
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease-out);
}

.suggests button:hover {
  background: var(--accent-line);
}

/* 数字卡 */
.stat {
  justify-content: flex-start;
}

.stat .big {
  margin-top: auto;
  font-size: 40px;
  font-weight: var(--fw-bold);
  letter-spacing: -0.04em;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}

.stat .big.accent {
  color: var(--accent);
}

.stat .note {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  color: var(--text-dim);
  font-size: var(--fs-sm);
}

.tint {
  background: var(--accent-soft);
  box-shadow: none;
}

:root[theme-mode='dark'] .tint {
  background: var(--bg-panel-2);
}

.tint .card-cap,
.tint .note {
  color: var(--accent);
}

.tint .card-link {
  color: var(--accent);
}

.dark {
  background: var(--indigo-950);
  color: var(--text);
  box-shadow: var(--shadow-3);
}

.dark .card-cap,
.dark .card-link,
.dark .note {
  color: var(--indigo-300);
}

.dark .big {
  color: var(--white);
}

/* 列表卡 */
.wide {
  grid-column: span 2;
  padding: var(--space-4) var(--space-5);
}

.doc-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.doc-list li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 8px;
  border-radius: var(--radius-md);
  cursor: pointer;
}

.doc-list li:hover {
  background: var(--bg-panel-2);
}

.doc-badge {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  flex: none;
  border-radius: var(--radius-sm);
  background: var(--bg-inset);
  color: var(--text-dim);
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: var(--fw-bold);
}

.doc-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--text);
  font-size: var(--fs-base);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-name small {
  display: block;
  color: var(--text-faint);
  font-family: var(--font-mono);
  font-size: var(--fs-micro);
}

.doc-state {
  flex: none;
  color: var(--text-dim);
  font-size: var(--fs-micro);
}

.doc-state.ok {
  color: var(--ok);
}

.doc-state.warn {
  color: var(--warn);
}

.spark {
  margin-top: auto;
}

.spark svg {
  display: block;
  width: 100%;
  height: 56px;
  overflow: visible;
}

@media (max-width: 1100px) {
  .bento {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    grid-auto-rows: auto;
  }

  /* 两列时 hero 不再撑两行，否则中间会留出一大块空白 */
  .hero {
    grid-column: span 2;
    grid-row: auto;
    min-height: 280px;
  }

  .wide {
    grid-column: span 2;
  }
}

@media (max-width: 640px) {
  .bento {
    grid-template-columns: minmax(0, 1fr);
    grid-auto-rows: auto;
  }

  .hero,
  .wide,
  .c2 {
    grid-column: span 1;
    grid-row: auto;
  }

  .stat .big {
    font-size: 32px;
  }
}
</style>
