/* 验证异步线程收到请求 ID 后会在任务完成时恢复原上下文。 */

package com.pony.knowledge.observability;

import com.pony.knowledge.request.RequestIdContext;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.*;

class RequestIdTaskDecoratorTest {
    /**
     * 核对 {@code RequestIdContext.current()} 与预期 {@code "http-request"} 一致。
     * 调用路径经过 {@code ObservationRegistry.create}。
     */
    @Test
    void requestIdAndExistingObservationAreBothPropagatedAndRestored() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            /** 构造 {@code boolean} 供测试使用。接收{@code context}。返回 {@code true}。 */
            @Override public boolean supportsContext(Observation.Context context) { return true; }
        });
        var decorator = new TracePropagatingTaskDecorator().observationTaskDecorator(registry);
        Observation parent = Observation.start("http", registry);
        Runnable captured;
        try (Observation.Scope observationScope = parent.openScope();
             RequestIdContext.Scope requestScope = RequestIdContext.open("http-request")) {
            captured = decorator.decorate(() -> {
                assertSame(parent, registry.getCurrentObservation());
                assertEquals("http-request", RequestIdContext.current());
            });
        }
        try {
            assertNull(registry.getCurrentObservation());
            captured.run();
            assertNull(registry.getCurrentObservation());
            assertNull(RequestIdContext.current());
            assertNull(MDC.get("requestId"));
        } finally {
            parent.stop();
        }
    }

    /**
     * 核对 {@code RequestIdContext.current()} 与预期 {@code "http-request"} 一致。
     * 调用路径经过 {@code ObservationRegistry.create}。
     */
    @Test
    void requestIdPropagatesWithoutObservationAndRestoresWorkerOnFailure() {
        var decorator = new TracePropagatingTaskDecorator().observationTaskDecorator(ObservationRegistry.create());
        Runnable captured;
        try (RequestIdContext.Scope ignored = RequestIdContext.open("http-request")) {
            captured = decorator.decorate(() -> {
                assertEquals("http-request", RequestIdContext.current());
                assertEquals("http-request", MDC.get("requestId"));
                throw new IllegalStateException("task failed");
            });
        }
        try (RequestIdContext.Scope ignored = RequestIdContext.open("worker-previous")) {
            assertThrows(IllegalStateException.class, captured::run);
            assertEquals("worker-previous", RequestIdContext.current());
            assertEquals("worker-previous", MDC.get("requestId"));
        }
        assertNull(RequestIdContext.current());
    }
}
