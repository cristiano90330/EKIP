<!-- 把一次请求的各处理阶段渲染成按起点与时长排列的瀑布图。 -->

<script setup lang="ts">
import { computed } from 'vue'
import type { TraceDTO } from '../types'
import { pipelineEngineLabel, retrievalVersionLabel, providerLabel, evidenceScorerLabel, traceOutcomeLabel, traceReasonLabel } from '../ragExecution'

const props = defineProps<{ trace: TraceDTO }>()

const maxDuration = computed(() =>
  Math.max(1, ...props.trace.spans.map((s) => s.durationMs)),
)
const hasRewrite = computed(
  () => props.trace.rewrittenQuery !== props.trace.originalQuery,
)

/** 根据时长量级在毫秒和秒之间选择显示单位。接收ms。 */
function fmtMs(ms: number) {
  return ms >= 1000 ? `${(ms / 1000).toFixed(2)}s` : `${Math.round(ms)}ms`
}

/** 以固定小数位显示费用，缺失费用时留空。接收v。 */
function fmtUsd(v: number | null) {
  return v == null ? '' : `$${v.toFixed(6)}`
}
</script>

<template>
  <div class="waterfall">
    <div class="wf-head">
      <div class="wf-query-line">
        <span class="muted q-label">原始</span>
        <code class="q">{{ trace.originalQuery }}</code>
      </div>
      <div class="wf-query-line">
        <span class="muted q-label">改写</span>
        <code class="q" :class="{ rewritten: hasRewrite }">{{ trace.rewrittenQuery }}</code>
        <span v-if="hasRewrite" class="pill rewrite-tag">已改写</span>
        <span v-else class="pill">未改写</span>
      </div>
    </div>

    <dl class="wf-execution" aria-label="本次实际执行信息">
      <div v-if="trace.requestedPipelineEngine && trace.requestedPipelineEngine !== trace.pipelineEngine">
        <dt>请求选择</dt>
        <dd>{{ pipelineEngineLabel(trace.requestedPipelineEngine) }}</dd>
      </div>
      <div v-if="trace.requestedRetrievalVersion && trace.requestedRetrievalVersion !== trace.retrievalVersion">
        <dt>请求检索策略</dt>
        <dd>{{ retrievalVersionLabel(trace.requestedRetrievalVersion) }}</dd>
      </div>
      <div>
        <dt>实际流程</dt>
        <dd>{{ pipelineEngineLabel(trace.pipelineEngine) }}</dd>
      </div>
      <div>
        <dt>检索策略</dt>
        <dd>{{ retrievalVersionLabel(trace.retrievalVersion) }}<code v-if="trace.retrievalVersion">{{ trace.retrievalVersion }}</code></dd>
      </div>
      <div>
        <dt>实际模型提供方</dt>
        <dd>{{ providerLabel(trace.providerUsed) }}</dd>
      </div>
      <div>
        <dt>证据评分方式</dt>
        <dd>{{ evidenceScorerLabel(trace.evidenceScorer) }}<code v-if="trace.evidenceScorer">{{ trace.evidenceScorer }}</code></dd>
      </div>
      <div>
        <dt>处理结果</dt>
        <dd :title="trace.outcome || undefined">{{ traceOutcomeLabel(trace.outcome) }}</dd>
      </div>
      <div>
        <dt>状态原因</dt>
        <dd>{{ traceReasonLabel(trace.reasonCode) }}<code v-if="trace.reasonCode && trace.reasonCode !== 'none'">{{ trace.reasonCode }}</code></dd>
      </div>
    </dl>

    <div class="wf-total">
      <span class="muted">总耗时</span>
      <strong>{{ fmtMs(trace.totalMs) }}</strong>
      <span class="muted">· 入口</span>
      <span class="pill">{{ trace.entrypoint }}</span>
      <span class="muted">· 开始</span>
      <span>{{ new Date(trace.startTime).toLocaleTimeString('zh-CN', { hour12: false }) }}</span>
    </div>

    <div class="wf-rows">
      <div v-for="(s, i) in trace.spans" :key="i" class="wf-row">
        <div class="wf-stage">
          <span class="dot" :class="s.success ? 'ok' : 'err'" />
          <span class="stage-name">{{ s.stage }}</span>
        </div>
        <div class="wf-bar-wrap">
          <div
            class="wf-bar"
            :class="{ fail: !s.success }"
            :style="{ width: `${Math.max(2, (s.durationMs / maxDuration) * 100)}%` }"
          >
            <span class="bar-label">{{ fmtMs(s.durationMs) }}</span>
          </div>
        </div>
        <div class="wf-meta">
          <template v-if="s.totalTokens != null">
            <span class="meta-chip">{{ s.totalTokens }} tok</span>
            <span v-if="s.inputTokens != null && s.outputTokens != null" class="meta-chip">
              in {{ s.inputTokens }} / out {{ s.outputTokens }}
            </span>
            <span class="meta-chip cost">{{ fmtUsd(s.costUsd) }}</span>
          </template>
          <span v-if="!s.success && s.errorMessage" class="err-msg" :title="s.errorMessage">
            {{ s.errorMessage }}
          </span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.waterfall {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.wf-head {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 10px 12px;
  background: var(--bg-panel);
}

.wf-query-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.q-label {
  flex-shrink: 0;
  color: var(--text-dim);
  font-size: var(--fs-sm);
}

.q {
  color: var(--text);
  font-family: var(--font-mono);
  font-size: var(--fs-base);
  word-break: break-all;
}

.q.rewritten {
  color: var(--accent);
  font-weight: 600;
}

.rewrite-tag {
  border-color: var(--accent-line);
  color: var(--accent);
}

.wf-total {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  color: var(--text);
}

.wf-total strong {
  color: var(--accent);
  font-family: var(--font-mono);
  font-size: var(--fs-lg);
  font-variant-numeric: tabular-nums;
}

.wf-execution {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-3) var(--space-5);
  margin: 0;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 10px 12px;
  background: var(--bg-panel);
}

.wf-execution dt {
  color: var(--text-dim);
  font-size: var(--fs-micro);
}

.wf-execution dd {
  margin: 4px 0 0;
  color: var(--text);
  font-size: var(--fs-sm);
  overflow-wrap: anywhere;
}

.wf-execution code {
  display: block;
  margin-top: 3px;
  color: var(--text-dim);
  font-family: var(--font-mono);
  font-size: var(--fs-micro);
}

.wf-rows {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.wf-row {
  display: grid;
  grid-template-columns: 180px minmax(0, 1fr) 220px;
  align-items: center;
  gap: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 8px 12px;
  background: var(--bg-panel);
}

.wf-stage {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
}

.stage-name {
  overflow: hidden;
  color: var(--text);
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wf-bar-wrap {
  min-width: 0;
}

.wf-bar {
  position: relative;
  height: 20px;
  min-width: 6px;
  border-radius: var(--radius-sm);
  background: linear-gradient(90deg, var(--accent-dim), var(--accent));
}

.wf-bar.fail {
  background: linear-gradient(90deg, var(--err), var(--err-dim));
}

.bar-label {
  position: absolute;
  top: 50%;
  right: 8px;
  transform: translateY(-50%);
  color: #fff;
  font-size: var(--fs-micro);
  font-variant-numeric: tabular-nums;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.45);
  white-space: nowrap;
}

.wf-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
}

.meta-chip {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 1px 6px;
  background: var(--bg-panel-2);
  color: var(--text-dim);
  font-family: var(--font-mono);
  font-size: var(--fs-micro);
  font-variant-numeric: tabular-nums;
}

.meta-chip.cost {
  border-color: var(--warn-line);
  color: var(--warn);
}

.err-msg {
  max-width: 200px;
  overflow: hidden;
  color: var(--err);
  font-size: var(--fs-micro);
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 1080px) {
  .wf-row {
    grid-template-columns: 150px minmax(0, 1fr);
  }

  .wf-meta {
    grid-column: 1 / -1;
    justify-content: flex-start;
  }
}
</style>
