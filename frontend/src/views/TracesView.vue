<!-- 按查询条件展示请求追踪列表及选定请求的阶段详情。 -->

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { listTraces, getTrace } from '../api/observability'
import type { TraceSummaryDTO, TraceDTO } from '../types'
import TraceWaterfall from '../components/TraceWaterfall.vue'
import { pipelineEngineLabel, retrievalVersionLabel, providerLabel, evidenceScorerLabel, traceOutcomeLabel, traceReasonLabel } from '../ragExecution'

const traces = ref<TraceSummaryDTO[]>([])
const selectedId = ref<string | null>(null)
const detail = ref<TraceDTO | null>(null)
const loadingList = ref(true)
const loadingDetail = ref(false)
const listError = ref('')
const detailError = ref('')

let timer: ReturnType<typeof setInterval> | null = null

/** 按当前入口条件读取追踪摘要并同步列表。下游调用 observability 的 listTraces。 */
async function refreshList() {
  try {
    traces.value = await listTraces(50)
    listError.value = ''
  } catch (e) {
    listError.value = (e as Error).message || '拉取列表失败'
  } finally {
    loadingList.value = false
  }
}

/**
 * 读取所选请求的详情并呈现阶段视图。
 * 接收t。
 * 由 当前模板的 @click 事件 触发。
 * 下游调用 observability 的 getTrace。
 */
async function select(t: TraceSummaryDTO) {
  const traceId = t.traceId
  selectedId.value = traceId
  loadingDetail.value = true
  detailError.value = ''
  detail.value = null
  try {
    const response = await getTrace(traceId)
    if (selectedId.value === traceId) detail.value = response
  } catch (e) {
    if (selectedId.value === traceId) detailError.value = (e as Error).message || '拉取详情失败'
  } finally {
    if (selectedId.value === traceId) loadingDetail.value = false
  }
}

onMounted(() => {
  refreshList()
  timer = setInterval(refreshList, 5000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

/** 将毫秒时间戳显示为本地时分秒。接收epochMs。 */
function fmtTime(epochMs: number) {
  return new Date(epochMs).toLocaleTimeString('zh-CN', { hour12: false })
}

/** 根据时长量级在毫秒和秒之间选择显示单位。接收ms。 */
function fmtMs(ms: number) {
  return ms >= 1000 ? `${(ms / 1000).toFixed(2)}s` : `${Math.round(ms)}ms`
}
</script>

<template>
  <div class="traces-page">
    <header class="page-head">
      <div>
        <h1>流程 Trace</h1>
        <p>检索与生成各阶段耗时；每 5 秒刷新最近 50 条</p>
      </div>
    </header>

    <div class="traces">
      <div class="left">
        <div class="section-head">
          <h2>最近请求</h2>
          <span class="muted">limit 50</span>
        </div>

      <div v-if="listError" class="error-banner" role="alert">{{ listError }}</div>
      <div v-else-if="loadingList" class="empty">加载中…</div>
      <div v-else-if="traces.length === 0" class="empty">暂无 trace，触发一次 /rag/chat 或 /agent/chat 后出现</div>

      <ul v-else class="trace-list">
        <li
          v-for="t in traces"
          :key="t.traceId"
          class="trace-item"
          :class="{ active: t.traceId === selectedId }"
          @click="select(t)"
        >
          <div class="ti-top">
            <span class="dot" :class="t.success ? 'ok' : 'err'" />
            <span class="ti-time">{{ fmtTime(t.startTime) }}</span>
            <t-tag size="small" variant="light">{{ t.entrypoint }}</t-tag>
            <span class="ti-dur">{{ fmtMs(t.totalMs) }}</span>
          </div>
          <div class="ti-q">{{ t.originalQuery }}</div>
          <div class="ti-execution" v-if="t.requestedPipelineEngine && t.requestedPipelineEngine !== t.pipelineEngine">
            <span>请求选择：{{ pipelineEngineLabel(t.requestedPipelineEngine) }}</span>
          </div>
          <div class="ti-execution">
            <span>流程：{{ pipelineEngineLabel(t.pipelineEngine) }}</span>
            <span :title="t.retrievalVersion || undefined">策略：{{ retrievalVersionLabel(t.retrievalVersion) }}</span>
          </div>
          <div class="ti-execution">
            <span :title="t.providerUsed || undefined">提供方：{{ providerLabel(t.providerUsed) }}</span>
            <span :title="t.evidenceScorer || undefined">评分：{{ evidenceScorerLabel(t.evidenceScorer) }}</span>
          </div>
          <div class="ti-sub">
            <span>{{ t.spanCount }} 阶段</span>
            <span :title="t.outcome || undefined">{{ traceOutcomeLabel(t.outcome) }}</span>
            <span v-if="!t.success" class="ti-fail">失败</span>
          </div>
          <div v-if="!t.success || (t.reasonCode && t.reasonCode !== 'none')" class="ti-reason" :title="t.reasonCode || undefined">
            原因：{{ traceReasonLabel(t.reasonCode) }}
          </div>
        </li>
      </ul>
    </div>

    <div class="right">
      <template v-if="loadingDetail">
        <div class="empty">加载详情…</div>
      </template>
      <template v-else-if="detailError">
        <div class="error-banner" role="alert">{{ detailError }}</div>
      </template>
      <template v-else-if="detail">
        <TraceWaterfall :trace="detail" />
      </template>
      <template v-else>
        <div class="empty">点击左侧某条 trace 查看阶段瀑布</div>
      </template>
    </div>
    </div>
  </div>
</template>

<style scoped>
/* 页面容器：两栏撑满剩余高度，左栏列表与右栏瀑布各自滚动 */
.traces-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.traces {
  display: grid;
  grid-template-columns: 340px minmax(0, 1fr);
  flex: 1;
  gap: var(--space-4);
  min-height: 0;
}

.left {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.trace-list {
  display: flex;
  flex: 1;
  flex-direction: column;
  margin: 0;
  padding: 0;
  overflow: auto;
  gap: 2px;
  padding: 8px;
  list-style: none;
  border: 1px solid transparent;
  border-radius: var(--radius-xl);
  background: var(--bg-panel);
  box-shadow: var(--shadow-2);
}

:root[theme-mode='dark'] .trace-list {
  border-color: var(--border);
  box-shadow: var(--shadow-1);
}

/* 条目：圆角块，选中态用淡靛蓝底，不再用左侧色条 */
.trace-item {
  padding: 9px 10px;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background var(--dur-base) var(--ease-out);
}

.trace-item:hover {
  background: var(--bg-panel-2);
}

.trace-item.active {
  background: var(--accent-soft);
}

.trace-item.active .ti-q {
  color: var(--accent);
}

.ti-top {
  display: flex;
  align-items: center;
  gap: 6px;
}

.ti-time {
  color: var(--text-dim);
  font-family: var(--font-mono);
  font-size: var(--fs-micro);
  font-variant-numeric: tabular-nums;
}

.ti-dur {
  margin-left: auto;
  color: var(--accent);
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.ti-q {
  display: -webkit-box;
  margin-top: 6px;
  overflow: hidden;
  color: var(--text);
  font-size: var(--fs-base);
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.ti-execution {
  display: flex;
  flex-wrap: wrap;
  gap: 3px 10px;
  margin-top: 4px;
  color: var(--text-dim);
  font-size: var(--fs-micro);
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.ti-sub {
  display: flex;
  gap: var(--space-2);
  margin-top: 4px;
  color: var(--text-dim);
  font-size: var(--fs-micro);
}

.ti-fail {
  color: var(--err);
}

.ti-reason {
  margin-top: 4px;
  color: var(--warn);
  font-size: var(--fs-micro);
  overflow-wrap: anywhere;
}

.right {
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: auto;
  border: 1px solid transparent;
  border-radius: var(--radius-xl);
  background: var(--bg-panel);
  box-shadow: var(--shadow-2);
}

:root[theme-mode='dark'] .right {
  border-color: var(--border);
  box-shadow: var(--shadow-1);
}

.right > .empty {
  margin: auto;
  background: transparent;
}

@media (max-width: 900px) {
  .traces-page {
    height: auto;
  }

  .traces {
    grid-template-columns: 1fr;
  }

  .trace-list {
    max-height: 320px;
  }
}
</style>
