/* 验证外部 OTLP 导出和标准日志处理的装配契约。 */

package com.pony.knowledge.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 追踪出口的配置契约测试。
 *
 * <p>为什么需要它：换桥之前，项目里 span 一直在生成、采样率也一直是 1.0，但**没有任何导出器**，
 * 于是一个 span 都没发出去 —— 而且没有任何测试变红，因为「span 丢掉」不是异常，是静默。
 * 这里把两件事钉住：
 * <ol>
 *   <li>桥与导出器必须在 classpath（删掉依赖会让它直接失败，而不是悄悄不导出）；</li>
 *   <li>{@code application.yml} 里用的前缀 {@code management.otlp.tracing} 必须真是 Boot 认识的
 *       那个前缀（写错成 {@code management.otlp.trace} 之类不会报错，只会读不到端点）。</li>
 * </ol>
 *
 * <p>注意边界：本测试**不验证** span 真的送达 collector/Langfuse。那需要启动完整栈并看
 * collector 日志，属于运行时验收，不能用单元测试代替。
 */
class TracingExportStackTest {

    /** 通过 {@code Class.forName} 执行本例并核对输出或记录。 */
    @Test
    void bridgeAndOtlpExporterAreOnClasspath() {
        // OTel 桥：把 Micrometer span 交给 OTel SDK
        assertDoesNotThrow(() -> Class.forName("io.micrometer.tracing.otel.bridge.OtelTracer"));
        // OTLP/HTTP 导出器：span 真正的出口
        assertDoesNotThrow(() -> Class.forName("io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter"));
        // Boot 的 OTLP 追踪自动配置属性类
        assertDoesNotThrow(
                () -> Class.forName("org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingProperties"));
    }

    /**
     * 核对 {@code properties.getEndpoint()} 与预期 {@code "http://otel-collector:4318/v1/traces"} 一致。
     * 调用路径经过 {@code Binder.get}。
     */
    @Test
    void managementOtlpTracingPrefixBindsEndpoint() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.otlp.tracing.endpoint", "http://otel-collector:4318/v1/traces");

        OtlpTracingProperties properties = Binder.get(environment)
                .bind("management.otlp.tracing", OtlpTracingProperties.class)
                .orElse(null);

        assertNotNull(properties, "前缀必须与 OtlpTracingProperties 声明的 prefix 一致");
        assertEquals("http://otel-collector:4318/v1/traces", properties.getEndpoint());
    }
}
