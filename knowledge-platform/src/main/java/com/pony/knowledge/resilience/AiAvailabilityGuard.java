/* 使用断路器限制不可用 AI 服务的调用，并记录许可的结果。 */

package com.pony.knowledge.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Java → Python availability. Resilience4j owns the window, cooldown and state machine. */
@Component
public class AiAvailabilityGuard {
    private static final Logger log = LoggerFactory.getLogger(AiAvailabilityGuard.class);
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final boolean enabled;
    private final CircuitBreaker breaker;
    private final long cooldownMs;
    private final Counter rejected;

    /**
     * 用该入口的默认选项初始化 AiAvailabilityGuard 实例。
     * 上游为 Spring 组件装配。
     *
     * @param enabled 期望的启用状态
     * @param window 读取切片或保留历史的窗口约束
     * @param minimum 本方法计算或判断使用的 minimum 值
     * @param rate 本次操作所用的 float 数据
     * @param cooldownMs 以毫秒计的时间限制
     * @param probes 本方法计算或判断使用的 probes 值
     * @param registry 本次操作所用的 MeterRegistry 数据
     */
    @Autowired
    public AiAvailabilityGuard(
            @Value("${ai.client.resilience.enabled:true}") boolean enabled,
            @Value("${ai.client.resilience.sliding-window-size:10}") int window,
            @Value("${ai.client.resilience.minimum-number-of-calls:5}") int minimum,
            @Value("${ai.client.resilience.failure-rate-threshold:50}") float rate,
            @Value("${ai.client.resilience.open-cooldown-ms:10000}") long cooldownMs,
            @Value("${ai.client.resilience.half-open-probes:1}") int probes,
            MeterRegistry registry) {
        this(enabled, CircuitBreaker.of("python", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(window).minimumNumberOfCalls(minimum).failureRateThreshold(rate)
                .waitDurationInOpenState(Duration.ofMillis(cooldownMs))
                .permittedNumberOfCallsInHalfOpenState(probes)
                .recordResult(result -> Boolean.FALSE.equals(result)).build()), registry);
    }

    /**
     * 保存使用断路器限制不可用 AI 服务的调用所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param enabled 期望的启用状态
     * @param breaker 本次操作所用的 CircuitBreaker 数据
     * @param registry 本次操作所用的 MeterRegistry 数据
     */
    AiAvailabilityGuard(boolean enabled, CircuitBreaker breaker, MeterRegistry registry) {
        this.enabled = enabled;
        this.breaker = breaker;
        this.cooldownMs = breaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1);
        Counter opened = registry.counter("ai.client.circuit.opened_total");
        this.rejected = registry.counter("ai.client.circuit.rejected_total");
        registry.gauge("ai.client.circuit.state", this, guard -> guard.state().ordinal());
        breaker.getEventPublisher().onStateTransition(event -> {
            var transition = event.getStateTransition();
            if (transition.getToState() == CircuitBreaker.State.OPEN) opened.increment();
            log.info("AI availability: {} -> {}", transition.getFromState(), transition.getToState());
        });
    }

    /**
     * 构建不应用断路策略的 AI 调用保护器。
     *
     * @param registry 本次操作所用的 MeterRegistry 数据
     * @return 本次得到的 AiAvailabilityGuard 实例
     */
    public static AiAvailabilityGuard disabled(MeterRegistry registry) {
        return new AiAvailabilityGuard(false, 10, 5, 50, 10000, 1, registry);
    }

    /**
     * 请求 AI 断路器的执行许可，被拒绝时记录依赖不可用。
     * 上游为 AgentGatewayClient.post、AgentGatewayClient.postSse。
     * 下游为 AiServiceUnavailableException.circuitOpen。
     *
     * @return 本次计算或查询得到的 Call 结果
     */
    public Call acquire() {
        if (enabled && !breaker.tryAcquirePermission()) {
            rejected.increment();
            throw AiServiceUnavailableException.circuitOpen(cooldownMs);
        }
        return new Call(enabled ? breaker : null);
    }

    /**
     * 返回用于可用性观测的当前断路器状态。
     *
     * @return 本次计算或查询得到的 State 结果
     */
    public State state() {
        if (!enabled) return State.CLOSED;
        return switch (breaker.getState()) {
            case OPEN, FORCED_OPEN -> State.OPEN;
            case HALF_OPEN -> State.HALF_OPEN;
            default -> State.CLOSED;
        };
    }

    /**
     * 报告断路器是否被配置为停用。
     *
     * @return 符合本方法描述的状态或完成条件时为 true
     */
    public boolean isDisabled() { return !enabled; }

    /**
     * HTTP clients classify status/connection results; the library receives each result once.
     * Atomic completion also ignores late stream callbacks after cancellation or header accounting.
     */
    public static final class Call implements AutoCloseable {
        private final CircuitBreaker breaker;
        private final AtomicBoolean completed = new AtomicBoolean();
        /**
         * 建立 Call，将调用方提供的初始值写入其状态。
         *
         * @param breaker 本次操作所用的 CircuitBreaker 数据
         */
        private Call(CircuitBreaker breaker) { this.breaker = breaker; }

        /** 将已经取得的调用许可结算为成功。 */
        public void success() {
            if (completed.compareAndSet(false, true) && breaker != null) {
                breaker.onSuccess(0, TimeUnit.NANOSECONDS);
            }
        }

        /** 将已经取得的调用许可结算为 AI 依赖故障。 */
        public void failure() {
            if (completed.compareAndSet(false, true) && breaker != null) {
                breaker.onResult(0, TimeUnit.NANOSECONDS, Boolean.FALSE);
            }
        }

        /**
         * 归还当前调用许可，故障计数保持原值。
         * 上游为 Call.close。
         */
        public void ignore() {
            if (completed.compareAndSet(false, true) && breaker != null) breaker.releasePermission();
        }

        /**
         * 归还尚未显式结算的 AI 调用许可。
         * 下游为 Call.ignore。
         */
        @Override public void close() { ignore(); }
    }
}
