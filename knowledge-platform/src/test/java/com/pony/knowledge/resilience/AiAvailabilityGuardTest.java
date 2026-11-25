/* 检查 AI 可用性防护的异常分类、失败计数、熔断及恢复。 */

package com.pony.knowledge.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class AiAvailabilityGuardTest {
    /** 核对 {@code context.getStartupFailure()} 为空。 */
    @Test void springWiresTheConfiguredConstructor() {
        new ApplicationContextRunner().withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withUserConfiguration(AiAvailabilityGuard.class).run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(AiAvailabilityGuard.State.CLOSED, context.getBean(AiAvailabilityGuard.class).state());
                });
    }

    /**
     * 构造 {@code CircuitBreaker} 供测试使用，使用 {@code CircuitBreaker.of}、{@code CircuitBreakerConfig.custom}。
     * 由 {@code countWindowEvictsOlderSuccesses}、{@code neutralAndCancelledCallsAreExcludedFromTheWindow} 调用。
     * @param rate 本用例传入的{@code rate}
     * 返回 {@code CircuitBreaker} 结果。
     */
    private CircuitBreaker breaker(float rate) {
        return CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .slidingWindowSize(10).minimumNumberOfCalls(5).failureRateThreshold(rate)
                .waitDurationInOpenState(Duration.ofSeconds(10)).permittedNumberOfCallsInHalfOpenState(1)
                .recordResult(result -> Boolean.FALSE.equals(result)).build());
    }

    /**
     * 在测试中处理 {@code outcome} 阶段，使用 {@code guard.acquire}、{@code call.success}。
     * 由 {@code approvedConfigurationRequiresFiveCallsAndUsesFailureRate}、{@code countWindowEvictsOlderSuccesses} 调用。
     * @param guard 本用例传入的{@code guard}
     * @param success 本用例传入的{@code success}
     */
    private void outcome(AiAvailabilityGuard guard, boolean success) {
        try (var call = guard.acquire()) { if (success) call.success(); else call.failure(); }
    }

    /** 核对 {@code guard.state()} 与预期 {@code AiAvailabilityGuard.State.CLOSED} 一致。 */
    @Test void approvedConfigurationRequiresFiveCallsAndUsesFailureRate() {
        AiAvailabilityGuard guard = new AiAvailabilityGuard(true, 10, 5, 50, 10000, 1, new SimpleMeterRegistry());
        for (int i = 0; i < 3; i++) outcome(guard, false);
        outcome(guard, true);
        assertEquals(AiAvailabilityGuard.State.CLOSED, guard.state());
        outcome(guard, true); // 3/5 failures; success does not erase the window.
        assertEquals(AiAvailabilityGuard.State.OPEN, guard.state());
        assertThrows(AiServiceUnavailableException.class, guard::acquire);
    }

    /** 核对 {@code guard.state()} 与预期 {@code AiAvailabilityGuard.State.CLOSED} 一致。 */
    @Test void countWindowEvictsOlderSuccesses() {
        CircuitBreaker breaker = breaker(80);
        AiAvailabilityGuard guard = new AiAvailabilityGuard(true, breaker, new SimpleMeterRegistry());
        for (int i = 0; i < 5; i++) outcome(guard, true);
        for (int i = 0; i < 7; i++) outcome(guard, false);
        assertEquals(AiAvailabilityGuard.State.CLOSED, guard.state());
        outcome(guard, false);
        assertEquals(AiAvailabilityGuard.State.OPEN, guard.state());
        assertEquals(10, breaker.getMetrics().getNumberOfBufferedCalls());
    }

    /** 核对 {@code breaker.getMetrics().getNumberOfBufferedCalls()} 与预期 {@code 3} 一致。调用路径经过 {@code guard.acquire}。 */
    @Test void neutralAndCancelledCallsAreExcludedFromTheWindow() {
        CircuitBreaker breaker = breaker(50);
        AiAvailabilityGuard guard = new AiAvailabilityGuard(true, breaker, new SimpleMeterRegistry());
        for (int i = 0; i < 3; i++) outcome(guard, false);
        for (int i = 0; i < 20; i++) { try (var ignored = guard.acquire()) { } }
        assertEquals(3, breaker.getMetrics().getNumberOfBufferedCalls());
        assertEquals(AiAvailabilityGuard.State.CLOSED, guard.state());
        outcome(guard, true); outcome(guard, true);
        assertEquals(AiAvailabilityGuard.State.OPEN, guard.state());
    }

    /** 断言抛出 {@code AiServiceUnavailableException}。调用路径经过 {@code breaker.transitionToOpenState}。 */
    @Test void halfOpenAllowsOneProbeAndIgnoredProbeReleasesItsPermission() {
        CircuitBreaker breaker = breaker(50);
        AiAvailabilityGuard guard = new AiAvailabilityGuard(true, breaker, new SimpleMeterRegistry());
        breaker.transitionToOpenState(); breaker.transitionToHalfOpenState();
        var first = guard.acquire();
        assertThrows(AiServiceUnavailableException.class, guard::acquire);
        first.close();
        assertEquals(AiAvailabilityGuard.State.HALF_OPEN, guard.state());
        outcome(guard, true);
        assertEquals(AiAvailabilityGuard.State.CLOSED, guard.state());
    }

    /**
     * 核对 {@code guard.state()} 与预期 {@code AiAvailabilityGuard.State.OPEN} 一致。
     * 调用路径经过 {@code breaker.transitionToOpenState}。
     */
    @Test void failedHalfOpenProbeOpensAgain() {
        CircuitBreaker breaker = breaker(50);
        AiAvailabilityGuard guard = new AiAvailabilityGuard(true, breaker, new SimpleMeterRegistry());
        breaker.transitionToOpenState(); breaker.transitionToHalfOpenState();
        outcome(guard, false);
        assertEquals(AiAvailabilityGuard.State.OPEN, guard.state());
    }

    /** 断言抛出 {@code AiServiceUnavailableException}。调用路径经过 {@code guard.acquire}。 */
    @Test void lateStreamCallbacksCannotReleaseAnotherCallsHalfOpenPermit() {
        CircuitBreaker breaker = breaker(50);
        AiAvailabilityGuard guard = new AiAvailabilityGuard(true, breaker, new SimpleMeterRegistry());
        var old = guard.acquire(); old.success();
        breaker.transitionToOpenState(); breaker.transitionToHalfOpenState();
        var probe = guard.acquire();
        old.failure(); old.ignore(); old.close();
        assertThrows(AiServiceUnavailableException.class, guard::acquire);
        probe.success(); probe.close();
        assertEquals(AiAvailabilityGuard.State.CLOSED, guard.state());
    }

    /**
     * 核对 {@code breaker.getMetrics().getNumberOfSuccessfulCalls()} 与预期 {@code 1} 一致。
     * 调用路径经过 {@code guard.acquire}。
     */
    @Test void oneHttpCallIsOnlyRecordedOnce() {
        CircuitBreaker breaker = breaker(50);
        AiAvailabilityGuard guard = new AiAvailabilityGuard(true, breaker, new SimpleMeterRegistry());
        try (var call = guard.acquire()) { call.success(); call.failure(); call.ignore(); }
        assertEquals(1, breaker.getMetrics().getNumberOfSuccessfulCalls());
        assertEquals(0, breaker.getMetrics().getNumberOfFailedCalls());
    }

    /** 核对 {@code guard.isDisabled()} 为真。调用路径经过 {@code AiAvailabilityGuard.disabled}。 */
    @Test void disabledGuardRemainsNoOp() {
        AiAvailabilityGuard guard = AiAvailabilityGuard.disabled(new SimpleMeterRegistry());
        for (int i = 0; i < 20; i++) outcome(guard, false);
        assertTrue(guard.isDisabled());
        assertEquals(AiAvailabilityGuard.State.CLOSED, guard.state());
    }

    /** 核对 {@code registry.get("ai.client.circuit.state").gauge().value()} 与预期 {@code 0} 一致。 */
    @Test void existingMetricNamesAndThreeStateValuesRemainAvailable() {
        CircuitBreaker breaker = breaker(50);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiAvailabilityGuard guard = new AiAvailabilityGuard(true, breaker, registry);
        assertEquals(0, registry.get("ai.client.circuit.state").gauge().value());
        breaker.transitionToOpenState();
        assertThrows(AiServiceUnavailableException.class, guard::acquire);
        assertEquals(1, registry.get("ai.client.circuit.state").gauge().value());
        assertEquals(1, registry.get("ai.client.circuit.opened_total").counter().count());
        assertEquals(1, registry.get("ai.client.circuit.rejected_total").counter().count());
        breaker.transitionToHalfOpenState();
        assertEquals(2, registry.get("ai.client.circuit.state").gauge().value());
    }
}
