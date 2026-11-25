/* 核对观测导出的启用条件与默认装配。 */

package com.pony.knowledge.observability;

import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class TracingExportConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpenTelemetryAutoConfiguration.class,
                    OpenTelemetryTracingAutoConfiguration.class, OtlpTracingAutoConfiguration.class))
            .withInitializer(context -> {
                // Read the production YAML while keeping host environment/VM flags out of defaults.
                context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                try {
                    new YamlPropertySourceLoader().load("production", new ClassPathResource("application.yml"))
                            .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                } catch (java.io.IOException error) {
                    throw new IllegalStateException(error);
                }
            });

    /** 覆盖追踪的相关边界。通过 {@code runner.run} 执行本例并核对输出或记录。 */
    @Test void defaultKeepsTracerWithoutCreatingExternalExporter() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(Tracer.class).doesNotHaveBean(SpanExporter.class);
            Tracer tracer = context.getBean(Tracer.class);
            var span = tracer.nextSpan().name("business-trace-contract").start();
            try {
                assertThat(span.context().traceId()).isNotBlank();
            } finally {
                span.end();
            }
        });
    }

    /** 覆盖追踪的相关边界。通过 {@code runner.withPropertyValues} 执行本例并核对输出或记录。 */
    @Test void explicitOptInCreatesExporterWithoutChangingTracerInterface() {
        runner.withPropertyValues("OTEL_ENABLED=true").run(context -> {
            assertThat(context).hasSingleBean(Tracer.class).hasSingleBean(SpanExporter.class);
        });
    }
}
