<!-- 从运维接口获取指标样本，并按统计量和单位展示读数。 -->

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { getMetrics, getMetric } from '../api/actuator'
import type { MetricDetailDTO } from '../types'

const cards = ref<MetricDetailDTO[]>([])
const loading = ref(true)
const error = ref('')
const partialError = ref('')

let timer: ReturnType<typeof setInterval> | null = null

// 优先展示的关键指标；其余 rag.*/agent.* 兜底补足
const PRIORITY = [
  'rag.requests.total',
  'rag.requests.active',
  'rag.rewrite.latency',
  'rag.rerank.latency',
  'rag.compression.latency',
  'rag.generation.latency',
  'rag.llm.tokens.consumed',
  'rag.cost.usd',
  'agent.requests.total',
  'agent.chat.latency',
]

/** 读取指标目录和详情并更新当前读数。下游调用 actuator 的 getMetrics、actuator 的 getMetric。 */
async function refresh() {
  try {
    const { names } = await getMetrics()
    const wanted = PRIORITY.filter((n) => names.includes(n))
    const rest = names.filter((n) => /^(rag|agent)\./.test(n) && !wanted.includes(n))
    const picked = [...wanted, ...rest].slice(0, 12)
    const details = await Promise.all(
      picked.map((n) => getMetric(n).catch(() => null)),
    )
    cards.value = details.filter((d): d is MetricDetailDTO => d != null)
    partialError.value = details.some((detail) => detail === null)
      ? '部分指标暂时无法读取，当前卡片仅显示成功返回的指标。'
      : ''
    error.value = ''
  } catch (e) {
    error.value = (e as Error).message || '拉取指标失败'
    partialError.value = ''
  } finally {
    loading.value = false
  }
}

/** 根据指标名判断是否使用计时展示规则。接收名称。由 primaryValue、secondaryValue 触发。 */
function isLatency(name: string) {
  return /latency/.test(name)
}

/** 从指标统计项中选取主值或由总值和计数计算平均值。接收m。下游调用 isLatency。 */
function primaryValue(m: MetricDetailDTO) {
  if (isLatency(m.name)) {
    const mx = m.measurements.find((x) => x.statistic === 'MAX')
    // 后端 micrometer 未启用 max 统计时 MAX=0，回退为平均延迟 TOTAL_TIME/COUNT（真实数据推导）
    if (mx && mx.value > 0) return mx
    const total = m.measurements.find((x) => x.statistic === 'TOTAL_TIME')
    const count = m.measurements.find((x) => x.statistic === 'COUNT')
    if (total && count && count.value > 0) return { statistic: 'AVG', value: total.value / count.value }
    return mx ?? m.measurements[0]
  }

  const order = ['COUNT', 'VALUE', 'MAX', 'TOTAL_TIME']

  for (const s of order) {
    const hit = m.measurements.find((x) => x.statistic === s)
    if (hit) return hit
  }
  return m.measurements[0]
}

/** 为耗时指标补充采样次数。接收m。下游调用 isLatency。 */
function secondaryValue(m: MetricDetailDTO) {
  if (isLatency(m.name)) {
    return m.measurements.find((x) => x.statistic === 'COUNT')
  }
  return undefined
}

/** 按指标名为采样值选择耗时、比例、容量或数量格式。接收名称、v。下游调用 isLatency。 */
function fmtValue(name: string, v: number) {
  if (name.includes('cost')) return `$${v.toFixed(4)}`
  if (name.includes('memory')) return `${(v / 1024 / 1024).toFixed(0)} MB`
  if (name.includes('tokens')) return v >= 1000 ? `${(v / 1000).toFixed(1)}k` : `${Math.round(v)}`
  if (name.includes('cpu')) return `${(v * 100).toFixed(0)}%`
  if (isLatency(name)) {
    return v >= 1 ? `${v.toFixed(2)}s` : `${Math.round(v * 1000)}ms`
  }
  return Math.round(v).toLocaleString()
}

onMounted(() => {
  refresh()
  timer = setInterval(refresh, 5000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="metrics">
    <header class="page-head">
      <div>
        <h1>运行指标</h1>
        <p>/actuator/metrics · 每 5 秒刷新 · 取 rag.* 与 agent.* 前 12 项</p>
      </div>
    </header>

    <div v-if="error" class="error-banner" role="alert">{{ error }}</div>
    <div v-else-if="partialError" class="error-banner" role="status">{{ partialError }}</div>
    <div v-else-if="loading" class="empty">加载中…</div>
    <div v-else-if="cards.length === 0" class="empty">暂无 rag.* / agent.* 指标，触发一次请求后出现</div>

    <div v-else class="mgrid">
      <article v-for="m in cards" :key="m.name" class="mcard">
        <div class="mcap" :title="m.description || m.name">{{ m.name }}</div>
        <div class="mval">{{ fmtValue(m.name, primaryValue(m).value) }}</div>
        <div class="msub">
          <span class="pill">{{ primaryValue(m).statistic }}</span>
          <span v-if="secondaryValue(m)">{{ secondaryValue(m)?.value }} 次</span>
        </div>
      </article>
    </div>
  </div>
</template>

<style scoped>
.metrics {
  display: flex;
  flex-direction: column;
  min-height: 100%;
}

.mgrid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--space-4);
}

/* 指标卡：无标题栏，靠「小号等宽标签 + 大数字」建立扫读节奏 */
.mcard {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 128px;
  padding: var(--space-4) var(--space-5);
  border: 1px solid transparent;
  border-radius: var(--radius-xl);
  background: var(--bg-panel);
  box-shadow: var(--shadow-2);
}

:root[theme-mode='dark'] .mcard {
  border-color: var(--border);
  box-shadow: var(--shadow-1);
}

.mcap {
  overflow: hidden;
  color: var(--text-faint);
  font-family: var(--font-mono);
  font-size: var(--fs-micro);
  letter-spacing: 0.02em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mval {
  margin-top: auto;
  color: var(--text);
  font-family: var(--font-mono);
  font-size: 30px;
  font-weight: var(--fw-bold);
  font-variant-numeric: tabular-nums;
  line-height: 1;
  letter-spacing: -0.035em;
}

.msub {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: 8px;
  color: var(--text-faint);
  font-size: var(--fs-micro);
  font-variant-numeric: tabular-nums;
}

@media (max-width: 1100px) {
  .mgrid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 620px) {
  .mgrid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
