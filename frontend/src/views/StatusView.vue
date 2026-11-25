<!-- 分别展示后台服务健康探针与问答能力就绪状态。 -->

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { getHealth, getAiHealth } from '../api/actuator'
import { getCapabilities, getStatus } from '../api/observability'
import type { CapabilityReadinessDTO, CapabilityReadinessResponseDTO, ObservabilityStatusDTO } from '../types'

const status = ref<ObservabilityStatusDTO | null>(null)
const backendHealth = ref('UNKNOWN')
const aiHealth = ref('UNKNOWN')
const backendHealthError = ref(false)
const aiHealthError = ref(false)
const error = ref('')
const readiness = ref<CapabilityReadinessResponseDTO | null>(null)
const readinessError = ref('')

const readinessRows = computed(() => {
  const value = readiness.value
  if (!value) return []
  const labels: Record<string, string> = {
    backend: '后端 API', database: 'PostgreSQL', redis: 'Redis', minio: 'MinIO',
    ingest: '文档入库', search: '知识检索', answer: '答案生成', chat: '多轮会话',
    evidenceGate: '证据验证', rerank: '重排',
  }
  return Object.entries(value.capabilities).flatMap(([key, item]) =>
    item ? [[labels[key] ?? key, item] as [string, CapabilityReadinessDTO]] : [],
  )
})

const readinessSummary = computed(() => {
  return readiness.value ? normalizedStatus(readiness.value.status) : 'UNKNOWN'
})

let timer: ReturnType<typeof setInterval> | null = null

/** 并行刷新 Java 和 AI 的健康探针结果。下游调用 actuator 的 getHealth、actuator 的 getAiHealth。 */
async function refreshHealth() {
  try {
    backendHealth.value = (await getHealth()).status
    backendHealthError.value = false
  } catch {
    backendHealth.value = 'UNKNOWN'
    backendHealthError.value = true
  }
  try {
    aiHealth.value = (await getAiHealth()).status
    aiHealthError.value = false
  } catch {
    aiHealth.value = 'UNKNOWN'
    aiHealthError.value = true
  }
}

/** 加载服务依赖摘要及当前能力快照。下游调用 observability 的 getStatus。 */
async function loadStatus() {
  try {
    status.value = await getStatus()
  } catch (e) {
    error.value = (e as Error).message || '拉取状态失败'
  }
}

/** 读取当前能力是否处在可用状态。下游调用 observability 的 getCapabilities。 */
async function refreshReadiness() {
  try {
    readiness.value = await getCapabilities()
    readinessError.value = ''
  } catch {
    readinessError.value = '暂时无法读取能力就绪状态。'
  }
}

onMounted(() => {
  loadStatus()
  refreshHealth()
  refreshReadiness()
  timer = setInterval(() => {
    refreshHealth()
    refreshReadiness()
  }, 10000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

/** 统一后端探针状态的大小写与缺省状态。接收s，返回文本。由 statusClass、statusLabel 触发。 */
function normalizedStatus(s: string): string {
  const value = s.toUpperCase()
  if (value === 'UP' || value === 'OK') return 'READY'
  if (value === 'DOWN' || value === 'NOT_READY') return 'UNAVAILABLE'
  return value
}

/**
 * 将状态映射到健康卡片的颜色类。
 * 接收s。
 * 由 statusTagTheme 触发。
 * 下游调用 normalizedStatus。
 */
function statusClass(s: string) {
  const value = normalizedStatus(s)
  if (value === 'READY') return 'ok'
  if (value === 'UNAVAILABLE') return 'err'
  return 'warn'
}

/** 生成状态面板展示的就绪标签。接收s。下游调用 normalizedStatus。 */
function statusLabel(s: string) {
  switch (normalizedStatus(s)) {
    case 'READY': return '就绪'
    case 'DEGRADED': return '部分可用'
    case 'UNAVAILABLE': return '不可用'
    default: return '未知'
  }
}

// 状态点的 ok / warn / err 语义映射到 t-tag 主题
/** 把状态映射到标签组件的主题。接收s，返回 'success' | 'warning' | 'danger' | 'default'。下游调用 statusClass。 */
function statusTagTheme(s: string): 'success' | 'warning' | 'danger' | 'default' {
  switch (statusClass(s)) {
    case 'ok': return 'success'
    case 'err': return 'danger'
    case 'warn': return 'warning'
    default: return 'default'
  }
}

/** 选择符合稳定状态码格式的可展示原因。接收item。 */
function safeReasonCode(item: CapabilityReadinessDTO) {
  const code = item.reasonCode
  return typeof code === 'string' && /^[a-z0-9_]{1,80}$/.test(code) && code !== 'none' ? code : ''
}
</script>

<template>
  <div class="status">
    <div class="page-head">
      <div>
        <h1>代码状态</h1>
        <p>能力就绪与存活状态每 10 秒刷新</p>
      </div>
    </div>

    <div v-if="error" class="error-banner" role="alert">{{ error }}</div>

    <div class="cols">
      <!-- /health checks process liveness; readiness is reported separately below. -->
      <section class="card col">
        <h3>进程存活</h3>
        <p class="section-hint">此处的 /health 只表示进程能响应，不代表问答或入库依赖已就绪。</p>
        <div class="health-row">
          <span class="dot" :class="statusClass(backendHealth)" />
          <span class="health-name">后端</span>
          <span class="health-src muted">/actuator/health · 存活</span>
          <strong class="health-val">{{ backendHealth }}</strong>
        </div>
        <small v-if="backendHealthError" class="health-read-error" role="status">后端存活状态读取失败，暂时无法确认。</small>
        <div class="health-row">
          <span class="dot" :class="statusClass(aiHealth)" />
          <span class="health-name">AI 服务</span>
          <span class="health-src muted">/health · 存活</span>
          <strong class="health-val">{{ aiHealth }}</strong>
        </div>
        <small v-if="aiHealthError" class="health-read-error" role="status">AI 服务存活状态读取失败，暂时无法确认。</small>
      </section>

      <section class="card col readiness-card">
        <div class="readiness-heading">
          <h3>能力就绪</h3>
          <t-tag :theme="statusTagTheme(readinessSummary)" variant="light-outline">
            {{ statusLabel(readinessSummary) }}
          </t-tag>
        </div>
        <p class="section-hint">由后端汇总依赖和 AI 能力；不替代真实问答/上传结果。</p>
        <p v-if="readinessError" class="readiness-error" role="alert">{{ readinessError }}</p>
        <div v-else-if="!readinessRows.length" class="muted">正在读取能力状态…</div>
        <div v-else class="capability-list">
          <div v-for="[name, item] in readinessRows" :key="name" class="capability-row">
            <span class="dot" :class="statusClass(item.status)" />
            <span>{{ name }}</span>
            <strong>{{ statusLabel(item.status) }}</strong>
            <code v-if="safeReasonCode(item)">{{ safeReasonCode(item) }}</code>
          </div>
        </div>
      </section>

      <!-- ② 代码 -->
      <section class="card col">
        <h3>代码</h3>
        <dl class="kv">
          <div class="kv-row">
            <dt>应用版本</dt>
            <dd>{{ status?.appVersion ?? '—' }}</dd>
          </div>
          <div class="kv-row">
            <dt>分支</dt>
            <dd>{{ status?.branch ?? '—' }}</dd>
          </div>
          <div class="kv-row">
            <dt>提交</dt>
            <dd class="mono">{{ status?.commitSha ?? '—' }}</dd>
          </div>
        </dl>
      </section>

      <!-- ③ 配置 -->
      <section class="card col">
        <h3>配置快照</h3>
        <dl class="kv">
          <div class="kv-row">
            <dt>检索路线</dt>
            <dd>{{ status?.retrievalVersion ?? '—' }}</dd>
          </div>
          <div class="kv-row">
            <dt>压缩模式</dt>
            <dd>{{ status?.compressionMode ?? '—' }}</dd>
          </div>
          <div class="kv-row">
            <dt>向量库</dt>
            <dd>{{ status?.vectorStore ?? '—' }}</dd>
          </div>
          <div class="kv-row">
            <dt>Trace 保留</dt>
            <dd>{{ status?.traceRetention ?? '—' }}</dd>
          </div>
        </dl>
      </section>
    </div>
  </div>
</template>

<style scoped>
.status {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

/* 卡片栅格：每张卡自带圆角与柔影，间距由 gap 提供 */
.cols {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-4);
  align-items: start;
}

/* 第二行：代码(1) + 配置快照(2)，与首行 进程(1) + 就绪(2) 对齐 */
.cols > .card:nth-child(4) {
  grid-column: span 2;
}

.cols > .card {
  min-width: 0;
}

.col h3 {
  margin: 0 0 var(--space-3);
  color: var(--text);
  font-size: var(--fs-md);
}

.section-hint {
  margin: -6px 0 var(--space-2);
  color: var(--text-dim);
  font-size: var(--fs-micro);
  line-height: 1.55;
}

.readiness-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}

.readiness-heading h3 {
  margin-bottom: var(--space-3);
}

.readiness-card {
  grid-column: span 2;
}

.capability-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 0 var(--space-4);
}

.capability-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  font-size: var(--fs-sm);
}

.capability-row + .capability-row {
  margin-top: 2px;
}

.capability-row strong {
  margin-left: auto;
  font-size: var(--fs-micro);
  font-weight: 500;
  white-space: nowrap;
}

.capability-row code {
  color: var(--warn);
  font-family: var(--font-mono);
  font-size: var(--fs-micro);
}

.readiness-error {
  color: var(--warn);
  font-size: var(--fs-sm);
}

.health-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 10px;
  border-radius: var(--radius-sm);
  background: var(--bg-panel-2);
}

.health-row + .health-row {
  margin-top: 6px;
}

.health-read-error {
  display: block;
  margin: 4px 10px 8px;
  color: var(--warn);
  font-size: var(--fs-sm);
}

.health-name {
  font-size: var(--fs-base);
  font-weight: 600;
}

.health-src {
  color: var(--text-dim);
  font-family: var(--font-mono);
  font-size: var(--fs-micro);
}

.health-val {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  font-variant-numeric: tabular-nums;
}

.kv {
  margin: 0;
}

.kv-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-3);
  padding: 7px 0;
}

.kv-row dt {
  color: var(--text-dim);
  font-size: var(--fs-base);
}

.kv-row dd {
  margin: 0;
  font-size: var(--fs-base);
  font-variant-numeric: tabular-nums;
}

@media (max-width: 900px) {
  .cols {
    grid-template-columns: minmax(0, 1fr);
  }

  .readiness-card,
  .cols > .card:nth-child(4) {
    grid-column: auto;
  }
}

@media (max-width: 640px) {

  .health-row {
    flex-wrap: wrap;
  }

  .health-src {
    order: 3;
    width: 100%;
    padding-left: 16px;
  }
}
</style>
