/** 读取 Java 和 AI 服务的健康检查与 Java Actuator 指标。 */

import { USE_MOCK } from '../config'
import { raw } from './http'
import { mockGetHealth, mockGetMetrics, mockGetMetric } from './mock'
import type { HealthDTO, MetricsListDTO, MetricDetailDTO } from '../types'

/**
 * 读取 Java 服务的当前健康探针。
 * 返回服务健康状态。
 * 由 StatusView.vue 的 refreshHealth 触发。
 * 下游调用 Java Actuator 与 AI 健康路由、mock 的 mockGetHealth。
 */
export const getHealth = (): Promise<HealthDTO> =>
  USE_MOCK ? mockGetHealth() : raw.get<HealthDTO>('/actuator/health').then((r) => r.data)

/**
 * 读取 Actuator 当前公开的指标名称。
 * 返回 MetricsListDTO。
 * 由 MetricsView.vue 的 refresh、OverviewView.vue 的 loadMetrics 触发。
 * 下游调用 Java Actuator 与 AI 健康路由、mock 的 mockGetMetrics。
 */
export const getMetrics = (): Promise<MetricsListDTO> =>
  USE_MOCK ? mockGetMetrics() : raw.get<MetricsListDTO>('/actuator/metrics').then((r) => r.data)

/**
 * 按名字读取一个指标系列的采样统计。
 * 接收名称，返回 MetricDetailDTO。
 * 由 MetricsView.vue 的 refresh、OverviewView.vue 的 loadMetrics 触发。
 * 下游调用 Java Actuator 与 AI 健康路由、mock 的 mockGetMetric。
 */
export const getMetric = (name: string): Promise<MetricDetailDTO> =>
  USE_MOCK ? mockGetMetric(name) : raw.get<MetricDetailDTO>(`/actuator/metrics/${name}`).then((r) => r.data)

/**
 * 通过开发代理读取 AI 服务健康探针。
 * 返回服务健康状态。
 * 由 StatusView.vue 的 refreshHealth 触发。
 * 下游调用 mock 的 mockGetHealth。
 */
export const getAiHealth = async (): Promise<HealthDTO> => {
  if (USE_MOCK) return mockGetHealth()

  const r = await fetch('/ai/health')
  if (!r.ok) throw new Error(`AI /health 返回 ${r.status}`)
  return r.json()
}
