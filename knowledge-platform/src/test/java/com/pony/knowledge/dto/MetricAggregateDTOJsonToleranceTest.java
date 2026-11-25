/* 验证指标聚合 DTO 和历史基线对缺失字段及新生产者字段的兼容。 */

package com.pony.knowledge.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.pony.knowledge.evaluation.regression.BaselineStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 钉住「跨语言交换格式的向前兼容」：`MetricAggregateDTO` 必须容忍未知字段。
 *
 * <p>为什么必须有这条测试：这份 JSON 由 `run_eval.py` 写、由 `BaselineStore` 用
 * **裸 `new ObjectMapper()`**（FAIL_ON_UNKNOWN_PROPERTIES 默认 true）读，中间还夹着
 * `EvaluationServiceImpl` 的 `catch (Exception ignore)`。生产方多加一个字段（例如 run_eval 的
 * `vectorStore` 引擎标识）会让读取端抛 UnrecognizedPropertyException，异常被吞掉后变成
 * 「未提供基线」——回归比对静默失效，没有任何日志。所以容忍度必须由测试固定住，
 * 否则下一次加字段会再次静默回归。</p>
 *
 * <p>两个用例分别钉两层：① DTO 自身的容忍度（显式开严格开关的 mapper）；
 * ② 真实消费路径（BaselineStore.load 读一个带额外字段的 baseline 文件）。</p>
 */
class MetricAggregateDTOJsonToleranceTest {

    /**
     * 与 `BaselineStore` 的裸 `new ObjectMapper()` 等价（Jackson 默认即开启
     * FAIL_ON_UNKNOWN_PROPERTIES，这里显式开一遍，避免测试被全局配置悄悄放宽）。
     */
    /**
     * 构造会拒绝未知字段的 JSON 映射器。
     * 由 {@code unknownProducerField_isIgnored_knownMetricsStillBind}、{@code oldBaselineWithoutPrecisionAtN_bindsNull} 调用。
     * 返回 {@code new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)}。
     */
    private static ObjectMapper strictMapper() {
        return new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** 核对 {@code dto.getCaseCount()} 与预期 {@code 16} 一致。 */
    @Test
    void unknownProducerField_isIgnored_knownMetricsStillBind() throws IOException {
        String payload = """
                {"caseCount":16,"recallAtK":0.625,"precisionAtN":0.71,"mrr":0.4896,"ndcgAtK":0.5245,
                 "vectorStore":"pgvector","someFutureFieldOfRunEval":123}
                """;
        MetricAggregateDTO dto = strictMapper().readValue(payload, MetricAggregateDTO.class);
        assertEquals(16, dto.getCaseCount());
        assertEquals(0.625, dto.getRecallAtK());
        assertEquals(0.4896, dto.getMrr());
        assertEquals(0.5245, dto.getNdcgAtK());
        assertEquals(0.71, dto.getPrecisionAtN());
    }

    /**
     * 「老基线」场景：文件产生于新增 precisionAtN 之前 → 反序列化后该字段为 null。
     * 这是回归比对「不可比对≠退化」的前提，必须钉住（否则老基线会被误判 REGRESSED）。
     */
    /** 核对 {@code dto.getRecallAtK()} 与预期 {@code 0.625} 一致。 */
    @Test
    void oldBaselineWithoutPrecisionAtN_bindsNull() throws IOException {
        MetricAggregateDTO dto = strictMapper().readValue(
                "{\"caseCount\":16,\"recallAtK\":0.625,\"mrr\":0.4896,\"hitAtK\":0.625}",
                MetricAggregateDTO.class);
        assertEquals(0.625, dto.getRecallAtK());
        assertNull(dto.getPrecisionAtN(), "老基线没有该字段时必须落成 null（不可比对），不能是 0");
    }

    /** 核对 {@code loaded.getCaseCount()} 与预期 {@code 16} 一致。调用路径经过 {@code Files.writeString}。接收{@code dir}。 */
    @Test
    void baselineStore_loadsFileCarryingExtraEngineField(@TempDir Path dir) throws IOException {
        // 复现真实故障现场：run_eval.py 写出的 baseline 文件多带一个 vectorStore 字段
        String file = """
                {"caseCount":16,"recallAtK":0.625,"ndcgAtK":0.5245,"mrr":0.4896,
                 "hitAtK":0.625,"avgLatencyMs":48.0,"costEstimateUsd":0.0,
                 "vectorStore":"pgvector"}
                """;
        Files.writeString(dir.resolve("baseline_hybrid-rerank-rewrite.json"), file);

        MetricAggregateDTO loaded = new BaselineStore(strictMapper(), dir)
                .load("hybrid-rerank-rewrite");

        assertNotNull(loaded, "带引擎标识字段的 baseline 必须仍能读回（否则回归比对静默失效）");
        assertEquals(16, loaded.getCaseCount());
        assertEquals(0.625, loaded.getRecallAtK());
        assertEquals(0.4896, loaded.getMrr());
        // 该 baseline 落于 precisionAtN 之前：字段为 null → RegressionEvaluator 按「不可比对」跳过
        assertNull(loaded.getPrecisionAtN());
    }

    /**
     * 反证：同一个 mapper 对一个**没有** `@JsonIgnoreProperties` 的类确实会抛
     * UnrecognizedPropertyException —— 证明上面两条不是空测试（容忍度真来自那条注解，
     * 而不是来自 mapper 被谁放宽了）。
     */
    /** 断言抛出 {@code UnrecognizedPropertyException}。 */
    @Test
    void strictMapper_isActuallyStrict_withoutTheAnnotation() {
        assertThrows(UnrecognizedPropertyException.class,
                () -> strictMapper().readValue("{\"caseCount\":1,\"vectorStore\":\"pgvector\"}",
                        AggregateWithoutTolerance.class));
    }

    /** 刻意不带注解的对照类（Jackson 认 public getter/setter，无参构造即可）。 */
    static class AggregateWithoutTolerance {
        private int caseCount;

        /** 读取夹具字段。返回 {@code caseCount}。 */
        public int getCaseCount() {
            return caseCount;
        }

        /** 更新捕获的夹具字段。接收{@code caseCount}。 */
        public void setCaseCount(int caseCount) {
            this.caseCount = caseCount;
        }
    }
}
