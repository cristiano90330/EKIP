/** 封装请求追踪、聚合指标和能力就绪信息的读取接口。 */

import { USE_MOCK } from '../config'
import { http } from './http'
import { mockListTraces, mockGetTrace, mockGetStatus, mockGetCapabilities } from './mock'
import type { TraceSummaryDTO, TraceDTO, ObservabilityStatusDTO, CapabilityReadinessResponseDTO } from '../types'

/**
 * 按入口和条数读取请求追踪摘要。
 * 接收limit、entrypoint，返回 TraceSummaryDTO[]。
 * 由 OverviewView.vue 的 load、TracesView.vue 的 refreshList 触发。
 * 下游调用 ObservabilityController、mock 的 mockListTraces。
 */
export const listTraces = (limit = 50, entrypoint?: string): Promise<TraceSummaryDTO[]> =>
  USE_MOCK
    ? mockListTraces(limit, entrypoint)
    : http.get<TraceSummaryDTO[], TraceSummaryDTO[]>('/observability/trace', {
        params: { limit, ...(entrypoint ? { entrypoint } : {}) },
      })

/**
 * 以追踪 ID 读取完整请求阶段记录。
 * 接收追踪 ID，返回带阶段数据的追踪详情。
 * 由 TracesView.vue 的 select 触发。
 * 下游调用 ObservabilityController、mock 的 mockGetTrace。
 */
export const getTrace = (id: string): Promise<TraceDTO> =>
  USE_MOCK ? mockGetTrace(id) : http.get<TraceDTO, TraceDTO>(`/observability/trace/${id}`)

/**
 * 读取观测接口汇总的依赖状态。
 * 返回 ObservabilityStatusDTO。
 * 由 StatusView.vue 的 loadStatus 触发。
 * 下游调用 ObservabilityController、mock 的 mockGetStatus。
 */
export const getStatus = (): Promise<ObservabilityStatusDTO> =>
  USE_MOCK ? mockGetStatus() : http.get<ObservabilityStatusDTO, ObservabilityStatusDTO>('/observability/status')

/**
 * 读取问答及模型相关能力的就绪快照。
 * 返回 CapabilityReadinessResponseDTO。
 * 由 OverviewView.vue 的 load、StatusView.vue 的 refreshReadiness 触发。
 * 下游调用 ObservabilityController、mock 的 mockGetCapabilities。
 */
export const getCapabilities = (): Promise<CapabilityReadinessResponseDTO> =>
  USE_MOCK
    ? mockGetCapabilities()
    : http.get<CapabilityReadinessResponseDTO, CapabilityReadinessResponseDTO>('/observability/capabilities')
