/* 约定检索或生成结果向观测层提供的降级原因和解析失败数。 */

package com.pony.knowledge.observability;

/**
 * 「这一步的结果来自兜底」的最小契约。
 *
 * <p>RAG 阶段结果通过该接口声明降级状态与原因，由 {@link RagStageTracer#observe} 统一记录，
 * 使观测层无需依赖各阶段的具体结果类型。
 *
 * <p>Lombok {@code @Data} 为 {@code boolean degraded} 生成的正是 {@code isDegraded()}、
 * 为 {@code String degradeReason} 生成的正是 {@code getDegradeReason()}，所以实现方只加字段。
 */
public interface DegradationSignal {

    /**
     * 声明操作结果是否含降级信号，供阶段观测器读取。
     *
     * @return 符合本方法描述的状态或完成条件时为 true
     */
    boolean isDegraded();

    /** 返回执行降级的业务原因。 */
    String getDegradeReason();

    /** 返回执行期间的解析失败数量。 */
    default int getParseFailures() {
        return 0;
    }
}
