<!-- 选择切片真值并运行检索或批量评测，维护本地回归基线。 -->

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { compareEvaluation, evaluateBatch, evaluateRetrieval, type BatchEvaluation, type EvaluationCase,
  type EvaluationMetrics, type EvaluationRegression, type RetrievalEvaluation } from '../api/evaluation'
import { listDocumentChunks, listDocuments, listKnowledgeBases } from '../api/library'
import { currentUserId } from '../api/userIdentity'
import { safeRagReasonCode } from '../ragOutcome'
import type { DocumentChunkPageDTO, DocumentPageDTO, KnowledgeBaseDTO } from '../types'

const libraries = ref<KnowledgeBaseDTO[]>([])
const libraryId = ref<number | null>(null)
const documents = ref<DocumentPageDTO | null>(null)
const documentId = ref<number | null>(null)
const chunks = ref<DocumentChunkPageDTO | null>(null)
const question = ref('')
const markedIds = ref<number[]>([])
const loading = ref(false)
const running = ref(false)
const loadedLibraries = ref(false)
const dataError = ref('')
const evaluationError = ref('')
const result = ref<RetrievalEvaluation | null>(null)
const MAX_BATCH_CASES = 20
interface Baseline { cases: EvaluationCase[]; k: 5; total: EvaluationMetrics }
const cases = ref<EvaluationCase[]>([])
const batch = ref<BatchEvaluation | null>(null)
const baseline = ref<Baseline | null>(null)
const regression = ref<EvaluationRegression | null>(null)
const batchError = ref('')
const batchRunning = ref(false)
let evaluationRequest: AbortController | null = null

const selectableDocuments = computed(() => documents.value?.records.filter(item => item.status === 2) ?? [])
const selectableChunks = computed(() => chunks.value?.records.filter(item => item.sourceType !== 'parent') ?? [])
const canRun = computed(() => !loading.value && !running.value && libraryId.value !== null
  && !!question.value.trim() && markedIds.value.length > 0)
const canSetBaseline = computed(() => !!batch.value && batch.value.totalCases === cases.value.length
  && batch.value.total.recallAtK != null && batch.value.perCase.every(item =>
    item.outcome === 'success' || item.outcome === 'no_evidence'))
const matchingBaseline = computed(() => baseline.value !== null
  && JSON.stringify(baseline.value.cases) === JSON.stringify(cases.value))
const resultLabels: Record<string, string> = {
  success: '完成',
  no_evidence: '证据不足',
  dependency_unavailable: '依赖不可用',
  timeout: '超时',
}

/**
 * 加载当前账号拥有的知识库供评测标注。
 * 由 当前模板的 @click 事件 触发。
 * 下游调用 library 的 listKnowledgeBases、userIdentity 的 currentUserId。
 */
async function loadLibraries() {
  loading.value = true
  dataError.value = ''
  try {
    const bases = await listKnowledgeBases()
    const userId = currentUserId()
    libraries.value = bases.filter(item => String(item.creatorId) === userId)
    loadedLibraries.value = true
  } catch {
    dataError.value = '无法读取知识库，请重试。'
  } finally {
    loading.value = false
  }
}

/**
 * 分页读取所选库的文档。
 * 接收页码。
 * 由 selectLibrary、当前模板的 @click 事件 触发。
 * 下游调用 library 的 listDocuments。
 */
async function loadDocumentPage(page = 1) {
  if (libraryId.value === null || loading.value || running.value) return
  loading.value = true
  dataError.value = ''
  try {
    documents.value = await listDocuments(libraryId.value, page, 30)
    documentId.value = null
    chunks.value = null
  } catch {
    dataError.value = '无法读取文档，请重试。'
  } finally {
    loading.value = false
  }
}

/**
 * 分页读取所选文档的切片候选。
 * 接收页码。
 * 由 selectDocument、当前模板的 @click 事件 触发。
 * 下游调用 library 的 listDocumentChunks。
 */
async function loadChunkPage(page = 1) {
  if (documentId.value === null || loading.value || running.value) return
  loading.value = true
  dataError.value = ''
  try {
    chunks.value = await listDocumentChunks(documentId.value, page)
  } catch {
    dataError.value = '无法读取文档切片，请重试。'
  } finally {
    loading.value = false
  }
}

/** 切换知识库并清除上一个范围的文档和切片标记。由 当前模板的 @change 事件 触发。下游调用 loadDocumentPage。 */
function selectLibrary() {
  documents.value = null
  documentId.value = null
  chunks.value = null
  markedIds.value = []
  dataError.value = ''
  void loadDocumentPage()
}

/** 加载新选文档的切片首页。由 当前模板的 @change 事件 触发。下游调用 loadChunkPage。 */
function selectDocument() {
  chunks.value = null
  dataError.value = ''
  void loadChunkPage()
}

/** 提交当前问题和标注切片，并更新单条检索评分。由 当前模板的 @submit.prevent 事件 触发。下游调用 evaluation 的 evaluateRetrieval。 */
async function runEvaluation() {
  if (!canRun.value || libraryId.value === null) return
  result.value = null
  evaluationError.value = ''
  running.value = true
  const controller = new AbortController()
  evaluationRequest = controller
  try {
    result.value = await evaluateRetrieval(libraryId.value, question.value.trim(), [...markedIds.value], controller.signal)
  } catch (cause) {
    if (!controller.signal.aborted) {
      evaluationError.value = cause instanceof Error ? cause.message : '评测未能完成。'
    }
  } finally {
    evaluationRequest = null
    running.value = false
  }
}

/** 将评测值显示为固定精度数值，缺失值显示占位。接收value，返回文本。 */
function metric(value?: number | null): string {
  return value == null ? '—' : value.toFixed(3)
}

/** 将当前问题和标注切片加入批量样本。由 当前模板的 @click 事件 触发。 */
function addCase() {
  if (!canRun.value || libraryId.value === null || cases.value.length >= MAX_BATCH_CASES) return
  cases.value = [...cases.value, {
    knowledgeBaseId: libraryId.value, question: question.value.trim(),
    relevantChunkIds: [...markedIds.value].sort((a, b) => a - b),
  }]
  question.value = ''
  markedIds.value = []
}

/**
 * 运行当前批次或与基线比较，并更新对应的统计视图。
 * 接收compare。
 * 由 当前模板的 @click 事件 触发。
 * 下游调用 evaluation 的 compareEvaluation、evaluation 的 evaluateBatch。
 */
async function runBatch(compare = false) {
  if (running.value || !cases.value.length) return
  if (compare && (!canSetBaseline.value || !matchingBaseline.value || !baseline.value || !batch.value)) return
  running.value = true
  batchRunning.value = true
  batchError.value = ''
  regression.value = null
  const controller = new AbortController()
  evaluationRequest = controller
  try {
    if (compare && baseline.value && batch.value) {
      regression.value = await compareEvaluation(baseline.value.total, batch.value.total, controller.signal)
    } else {
      batch.value = null
      batch.value = await evaluateBatch(cases.value, controller.signal)
    }
  } catch (cause) {
    if (!controller.signal.aborted) batchError.value = cause instanceof Error ? cause.message : '批量评测操作失败。'
  } finally {
    evaluationRequest = null
    batchRunning.value = false
    running.value = false
  }
}

/** 将当前评测批次保存为页面比较基线。由 当前模板的 @click 事件 触发。 */
function setBaseline() {
  if (!canSetBaseline.value || !batch.value) return
  baseline.value = { cases: [...cases.value], k: 5, total: batch.value.total }
  regression.value = null
}

/** 生成基线 JSON 下载并释放临时对象地址。由 当前模板的 @click 事件 触发。 */
function downloadBaseline() {
  if (!baseline.value) return

  const url = URL.createObjectURL(new Blob([JSON.stringify(baseline.value, null, 2)], { type: 'application/json' }))
  const link = document.createElement('a')
  link.href = url
  link.download = 'ekip-retrieval-baseline.json'
  link.click()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}

/** 判断导入数据的 ID 是否为受支持的正整数。接收value，返回布尔判定。由 importBaseline 触发。 */
function validId(value: number): boolean {
  return Number.isSafeInteger(value) && value > 0
}

/**
 * 读取上传的基线 JSON，校验用例和标注后再替换本地基线。
 * 接收页面事件。
 * 由 当前模板的 @change 事件 触发。
 * 下游调用 validId。
 */
async function importBaseline(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || running.value) return
  batchError.value = ''
  try {
    if (file.size > 1_000_000) throw new Error()
    // 先验证整个基线的样本和统计结构，全部通过后才替换已加载基线。
    const value: Baseline = JSON.parse(await file.text())
    if (value.k !== 5 || !Array.isArray(value.cases) || !value.cases.length || value.cases.length > MAX_BATCH_CASES
      || value.total?.caseCount !== value.cases.length || typeof value.total.recallAtK !== 'number'
      || Object.values(value.total).some(metric => metric !== null && typeof metric !== 'number' && typeof metric !== 'object')
      || value.cases.some(item => !validId(item.knowledgeBaseId) || typeof item.question !== 'string'
        || !item.question.trim() || item.question.length > 4000 || !Array.isArray(item.relevantChunkIds)
        || !item.relevantChunkIds.length || !item.relevantChunkIds.every(validId))) throw new Error()
    if (running.value) return
    baseline.value = { ...value, cases: value.cases.map(({ knowledgeBaseId, question, relevantChunkIds }) => ({
      knowledgeBaseId, question, relevantChunkIds: [...new Set(relevantChunkIds)].sort((a, b) => a - b),
    })) }
    if (!cases.value.length) cases.value = [...baseline.value.cases]
    regression.value = null
  } catch {
    batchError.value = '无法读取基线，请选择从此页面下载的基线 JSON。'
  }
}

/** 将回归门判定转换为面板展示标签。接收verdict，返回文本。 */
function regressionLabel(verdict: string): string {
  return ({ IMPROVED: '改善', REGRESSED: '退化', UNCHANGED: '稳定' } as Record<string, string>)[verdict] ?? verdict
}

watch([libraryId, question, markedIds], () => {
  result.value = null
  evaluationError.value = ''
})
watch(cases, () => { batch.value = null; regression.value = null })
onMounted(() => { void loadLibraries() })
onUnmounted(() => { evaluationRequest?.abort() })
</script>

<template>
  <section class="panel evaluation-panel" aria-labelledby="evaluation-heading">
    <h2 id="evaluation-heading">检索评测</h2>
    <p class="muted">用人工标注的相关切片，评估一个问题的前 5 条检索结果。</p>
    <form class="evaluation-form" @submit.prevent="runEvaluation">
      <div class="evaluation-controls">
        <label>知识库
          <select v-model="libraryId" :disabled="loading || running" @change="selectLibrary">
            <option :value="null">选择自己创建的知识库</option>
            <option v-for="library in libraries" :key="library.id" :value="library.id">{{ library.name }}</option>
          </select>
        </label>
        <label v-if="documents">文档
          <select v-model="documentId" :disabled="loading || running" @change="selectDocument">
            <option :value="null">选择已解析的文档</option>
            <option v-for="document in selectableDocuments" :key="document.id" :value="document.id">
              {{ document.fileName }}
            </option>
          </select>
        </label>
        <div v-if="documents && documents.pages > 1" class="page-controls" aria-label="文档分页">
          <t-button size="small" variant="outline" :disabled="loading || running || documents.current <= 1"
            @click="loadDocumentPage(documents.current - 1)">上一页文档</t-button>
          <span>{{ documents.current }} / {{ documents.pages }}</span>
          <t-button size="small" variant="outline" :disabled="loading || running || documents.current >= documents.pages"
            @click="loadDocumentPage(documents.current + 1)">下一页文档</t-button>
        </div>
      </div>
      <div v-if="dataError" class="page-controls">
        <p class="error" role="alert">{{ dataError }}</p>
        <t-button v-if="!loadedLibraries" size="small" variant="outline" @click="loadLibraries">重试</t-button>
        <t-button v-else-if="!documents" size="small" variant="outline" @click="loadDocumentPage()">重试</t-button>
        <t-button v-else-if="documentId !== null && !chunks" size="small" variant="outline" @click="loadChunkPage()">重试</t-button>
      </div>
      <p v-else-if="loading" role="status" class="muted">正在读取…</p>
      <p v-else-if="loadedLibraries && !libraries.length" class="muted">暂无可以评测的知识库。</p>
      <p v-else-if="documents && !selectableDocuments.length" class="muted">这一页没有已解析的文档。</p>

      <label>评测问题
        <textarea v-model="question" maxlength="4000" rows="3" required :disabled="running"></textarea>
      </label>
      <div v-if="chunks" class="evaluation-chunks">
        <h3>标注预期命中的切片</h3>
        <p v-if="!selectableChunks.length" class="muted">这一页没有可标注的检索切片。</p>
        <ul v-else>
          <li v-for="chunk in selectableChunks" :key="chunk.id">
            <label class="chunk-option">
              <input v-model="markedIds" type="checkbox" :value="chunk.id" :disabled="running || loading">
              <span>
                <strong>{{ chunk.headingPath || '切片 ' + chunk.chunkIndex }}</strong>
                <small class="muted"> ID {{ chunk.id }}</small>
                <span class="chunk-preview">{{ chunk.content }}</span>
              </span>
            </label>
          </li>
        </ul>
        <div v-if="chunks.pages > 1" class="page-controls" aria-label="切片分页">
          <t-button size="small" variant="outline" :disabled="loading || running || chunks.current <= 1"
            @click="loadChunkPage(chunks.current - 1)">上一页切片</t-button>
          <span>{{ chunks.current }} / {{ chunks.pages }}</span>
          <t-button size="small" variant="outline" :disabled="loading || running || chunks.current >= chunks.pages"
            @click="loadChunkPage(chunks.current + 1)">下一页切片</t-button>
        </div>
      </div>
      <div class="page-controls">
        <t-button type="submit" theme="primary" :disabled="!canRun" :loading="running">运行评测</t-button>
        <t-button type="button" variant="outline" :disabled="!canRun || cases.length >= MAX_BATCH_CASES"
          @click="addCase">加入批量用例</t-button>
        <span class="muted">已标注 {{ markedIds.length }} 个切片</span>
        <t-button v-if="markedIds.length" variant="text" size="small" :disabled="running" @click="markedIds = []">
          清空标注
        </t-button>
      </div>
    </form>
    <p v-if="running && !batchRunning" role="status" class="muted">正在运行检索、生成及裁判，请稍候。</p>
    <p v-if="evaluationError" role="alert" class="error">{{ evaluationError }}</p>

    <div v-if="result" class="evaluation-result" aria-labelledby="evaluation-result-heading">
      <h3 id="evaluation-result-heading">评测结果</h3>
      <p v-if="result.outcome || result.version" class="muted">
        {{ resultLabels[result.outcome || ''] || result.outcome }} {{ result.version }}
      </p>
      <template v-if="result.outcome === 'success' || result.outcome === 'no_evidence'">
        <dl>
          <div><dt>Recall@5</dt><dd>{{ metric(result.recallAtK) }}</dd></div>
          <div><dt>Precision@5</dt><dd>{{ metric(result.precisionAtK) }}</dd></div>
          <div><dt>MRR</dt><dd>{{ metric(result.mrr) }}</dd></div>
          <div><dt>检索耗时</dt><dd>{{ result.retrievalLatencyMs == null ? '—' : result.retrievalLatencyMs + ' ms' }}</dd></div>
          <div><dt>检索与生成耗时</dt><dd>{{ result.latencyMs == null ? '—' : result.latencyMs + ' ms' }}</dd></div>
        </dl>
        <p v-if="result.answer" class="evaluated-answer">{{ result.answer }}</p>
      </template>
      <p v-if="safeRagReasonCode(result.reasonCode) || result.requestId" class="muted">
        <code>{{ [safeRagReasonCode(result.reasonCode), result.requestId].filter(Boolean).join(' · ') }}</code>
      </p>
    </div>

    <div class="evaluation-result" aria-labelledby="batch-heading">
      <h3 id="batch-heading">批量检索与回归对比</h3>
      <p class="muted">最多 {{ MAX_BATCH_CASES }} 条用例。回归总判定使用 Recall@5，其余可用指标显示变化明细。</p>
      <ol v-if="cases.length" class="batch-cases">
        <li v-for="(item, index) in cases" :key="index">
          <span>{{ item.question }} · 知识库 #{{ item.knowledgeBaseId }} · {{ item.relevantChunkIds.length }} 个切片</span>
          <t-button size="small" variant="text" :disabled="running"
            @click="cases = cases.filter((_, i) => i !== index)">移除</t-button>
        </li>
      </ol>
      <div class="page-controls">
        <t-button theme="primary" :disabled="running || !cases.length" @click="runBatch()">运行批量检索</t-button>
        <t-button variant="outline" :disabled="running || !canSetBaseline" @click="setBaseline">用本次结果作基线</t-button>
        <t-button v-if="baseline" variant="outline" :disabled="running" @click="downloadBaseline">下载基线</t-button>
        <label>导入基线 <input type="file" accept="application/json,.json" :disabled="running" @change="importBaseline"></label>
        <t-button variant="outline" :disabled="running || !canSetBaseline || !matchingBaseline"
          @click="runBatch(true)">对比基线</t-button>
      </div>
      <p v-if="batchRunning" class="muted" role="status">正在处理批量评测…
        <t-button size="small" variant="text" @click="evaluationRequest?.abort()">结束等待</t-button>
      </p>
      <p v-if="batchError" class="error" role="alert">{{ batchError }}</p>
      <p v-if="baseline" class="muted">
        {{ matchingBaseline ? `基线包含 ${baseline.cases.length} 条匹配用例。` : '当前用例与基线不同。' }}
        <t-button v-if="!matchingBaseline" size="small" variant="text" :disabled="running"
          @click="cases = [...baseline.cases]">使用基线用例</t-button>
      </p>
      <div v-if="batch" class="batch-table">
        <h4>本次批量结果 · {{ batch.totalCases }} 条 · {{ batch.version }}</h4>
        <p v-if="!canSetBaseline" class="muted">评测有用例失败或缺少必需指标。重试完成后可用于回归。</p>
        <table>
          <thead><tr><th scope="col">用例</th><th scope="col">结果</th><th scope="col">Recall@5</th>
            <th scope="col">Precision@5</th><th scope="col">MRR</th></tr></thead>
          <tbody>
            <tr><th scope="row">整体</th><td>—</td><td>{{ metric(batch.total.recallAtK) }}</td>
              <td>{{ metric(batch.total.precisionAtK) }}</td><td>{{ metric(batch.total.mrr) }}</td></tr>
            <tr v-for="(item, index) in batch.perCase" :key="index">
              <th scope="row">{{ item.question || cases[index]?.question }}</th>
              <td>{{ resultLabels[item.outcome ?? ''] || item.outcome }}</td>
              <td>{{ metric(item.recallAtK) }}</td><td>{{ metric(item.precisionAtK) }}</td><td>{{ metric(item.mrr) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="regression" class="batch-table">
        <h4>回归结果 · {{ regression.passed ? regressionLabel(regression.verdict) : '未通过' }}</h4>
        <table>
          <thead><tr><th scope="col">指标</th><th scope="col">基线</th><th scope="col">本次</th>
            <th scope="col">差值</th><th scope="col">变化</th></tr></thead>
          <tbody><tr v-for="delta in regression.deltas.filter(item => item.applicable)" :key="delta.metric">
            <th scope="row">{{ delta.label }}</th><td>{{ metric(delta.baselineValue) }}</td>
            <td>{{ metric(delta.currentValue) }}</td><td>{{ metric(delta.absoluteDelta) }}</td>
            <td>{{ regressionLabel(delta.verdict) }}</td>
          </tr></tbody>
        </table>
      </div>
    </div>
  </section>
</template>

<style scoped>
.evaluation-panel h2 { margin-top: 0; }
.evaluation-form, .evaluation-form label, .evaluation-chunks { display: grid; gap: var(--space-2); }
.evaluation-form { gap: var(--space-3); }
.evaluation-controls, .page-controls { display: flex; align-items: end; flex-wrap: wrap; gap: var(--space-3); }
.page-controls { align-items: center; }
.page-controls p { margin: 0; }
.evaluation-controls label { min-width: 0; }
.evaluation-form select, .evaluation-form textarea { width: 100%; padding: var(--space-2); color: var(--text); background: var(--bg-panel-2); border: 1px solid var(--border); border-radius: var(--radius-sm); font: inherit; }
.evaluation-chunks h3, .evaluation-chunks p { margin: 0; }
.evaluation-chunks ul { display: grid; gap: var(--space-2); list-style: none; margin: 0; padding: var(--space-1); max-height: 320px; overflow: auto; }
.evaluation-chunks .chunk-option { display: flex; align-items: start; padding: var(--space-2); border: 1px solid var(--border); border-radius: var(--radius-sm); }
.chunk-preview { display: block; white-space: pre-wrap; overflow-wrap: anywhere; margin-top: var(--space-1); }
.evaluation-result { margin-top: var(--space-4); border-top: 1px solid var(--border); }
.evaluation-result dl { display: flex; flex-wrap: wrap; gap: var(--space-4); }
.evaluation-result dd { margin: var(--space-1) 0 0; font-weight: bold; }
.evaluated-answer { white-space: pre-wrap; overflow-wrap: anywhere; }
.batch-cases li { overflow-wrap: anywhere; }
.batch-table { overflow-x: auto; }
.batch-table table { width: 100%; border-collapse: collapse; text-align: left; }
.batch-table td, .batch-table th { padding: var(--space-2); border-bottom: 1px solid var(--border); }
.error { color: var(--err); }
</style>
