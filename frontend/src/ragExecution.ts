/** 为 Trace 和指标界面提供检索策略、编排引擎、证据模式和执行结果的显示文本。 */

/** 为缺失的追踪字段提供统一的未记录显示值。接收value，返回文本。由 pipelineEngineLabel、retrievalVersionLabel 触发。 */
export function recordedValue(value: string | null | undefined): string {
  return value?.trim() || '未记录'
}

/** 把运行记录中的编排引擎值转换为页面标签。接收value，返回文本。下游调用 recordedValue。 */
export function pipelineEngineLabel(value: string | null | undefined): string {
  if (value === 'fixed') return '固定流程'
  if (value === 'langchain') return 'LangChain'
  return recordedValue(value)
}

/** 把实际检索版本转换为短标签。接收value，返回文本。下游调用 recordedValue。 */
export function retrievalVersionLabel(value: string | null | undefined): string {
  switch (value) {
    case 'baseline': return '向量检索'
    case 'hybrid': return '混合检索'
    case 'hybrid-rerank': return '混合检索 + 重排'
    case 'hybrid-rerank-rewrite': return '混合检索 + 重排 + 改写'
    default: return recordedValue(value)
  }
}

/** 把模型或检测记录的提供方代码转换为页面标签。接收value，返回文本。下游调用 recordedValue。 */
export function providerLabel(value: string | null | undefined): string {
  if (value === 'ollama') return 'Ollama（本地）'
  if (value === 'deepseek') return 'DeepSeek（云端）'
  if (value === 'none') return '未调用'
  if (value === 'mock') return '开发模拟数据'
  return recordedValue(value)
}

/** 把证据门禁模式转换为展示标签。接收value，返回文本。 */
export function evidenceModeLabel(value: string | null | undefined): string {
  if (value === 'support') return '正文审查'
  if (value === 'score') return '分数阈值'
  return '未记录'
}

/** 显示执行记录中实际使用的证据评分器。接收value，返回文本。下游调用 recordedValue。 */
export function evidenceScorerLabel(value: string | null | undefined): string {
  if (value === 'model') return '重排模型'
  if (value === 'embed_sim') return '向量相似度降级'
  if (value === 'none') return '未评分'
  if (value === 'mixed') return '多种评分方式'
  if (value === 'mock') return '开发模拟评分'
  return recordedValue(value)
}

/** 把 Trace 的业务终态转换为可阅读的标签。接收value，返回文本。下游调用 recordedValue。 */
export function traceOutcomeLabel(value: string | null | undefined): string {
  switch (value) {
    case 'success': return '完成'
    case 'no_evidence': return '证据不足，未生成'
    case 'dependency_unavailable': return '依赖不可用'
    case 'timeout': return '等待超时'
    default: return recordedValue(value)
  }
}

/** 把 Trace 中的稳定原因码转换为页面文案。接收value，返回文本。下游调用 recordedValue。 */
export function traceReasonLabel(value: string | null | undefined): string {
  switch (value) {
    case 'none': return '无'
    case 'partial_retrieval': return '部分检索不可用'
    case 'threshold_not_configured': return '证据门槛未配置'
    case 'scorer_unavailable': return '证据评分不可用'
    case 'invalid_score_mapping': return '评分与来源不对应'
    case 'invalid_score': return '证据评分无效'
    case 'below_threshold': return '证据未达到门槛'
    case 'retrieval_unavailable': return '检索服务不可用'
    case 'query_deadline_exceeded': return '请求超过等待时间'
    case 'ai_contract_mismatch': return 'AI 返回的执行版本不符合请求'
    default: return recordedValue(value)
  }
}
