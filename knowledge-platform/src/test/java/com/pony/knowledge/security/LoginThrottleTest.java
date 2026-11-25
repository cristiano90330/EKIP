/* 核对登录失败计数、锁定窗口和成功后的清理。 */

package com.pony.knowledge.security;

import com.pony.knowledge.config.LoginThrottleProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 登录限流（设计 §4.4 E1）。
 *
 * 除了常规计数行为，本测试钉住两条容易被后人「顺手改好」的取舍：
 * ① TTL 只在首次失败时设置（后续失败不续期），否则攻击者靠持续失败能把锁无限延长；
 * ② Redis 不可用时降级为「不限流」+ log.error，而不是快速失败（可用性优先，设计 §11.4）。
 */
@ExtendWith(MockitoExtension.class)
class LoginThrottleTest {

    private static final String IP = "203.0.113.7";

    private static final String USERNAME = "alice";

    /** 与 LoginThrottle 的 KEY_PATTERN 一致：login:fail:{username}:{ip} */
    private static final String KEY = "login:fail:alice:203.0.113.7";

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private LoginThrottleProperties properties;

    private LoginThrottle throttle;

    /** 每个用例前准备夹具，调用 {@code properties.setEnabled}、{@code properties.setMaxAttempts}。 */
    @BeforeEach
    void setUp() {
        properties = new LoginThrottleProperties();
        properties.setEnabled(true);
        properties.setMaxAttempts(5);
        properties.setLockMinutes(15);
        throttle = new LoginThrottle(redis, properties);
        // 写入/删除路径的测试不读计数，故用 lenient 避免 strict stubs 报 UnnecessaryStubbing
        lenient().when(redis.opsForValue()).thenReturn(valueOperations);
    }

    /** 通过 {@code valueOperations.get} 执行本例并核对输出或记录。 */
    @Test
    void belowLimitIsAllowed() {
        when(valueOperations.get(KEY)).thenReturn("4");

        assertThatCode(() -> throttle.assertAllowed(IP, USERNAME)).doesNotThrowAnyException();
    }

    /** 通过 {@code valueOperations.get} 执行本例并核对输出或记录。 */
    @Test
    void noCounterYetIsAllowed() {
        when(valueOperations.get(KEY)).thenReturn(null);

        assertThatCode(() -> throttle.assertAllowed(IP, USERNAME)).doesNotThrowAnyException();
    }

    /** 覆盖失败的相关边界。通过 {@code valueOperations.get} 执行本例并核对输出或记录。 */
    @Test
    void fifthFailureLocksTheSixthAttempt() {
        when(valueOperations.get(KEY)).thenReturn("5");

        assertThatThrownBy(() -> throttle.assertAllowed(IP, USERNAME))
                .isInstanceOf(LoginLockedException.class)
                // 15 分钟 → Retry-After 900 秒
                .satisfies(e -> assertThat(((LoginLockedException) e).getRetryAfterSeconds()).isEqualTo(900L))
                .satisfies(e -> assertThat(((LoginLockedException) e).getRetryAfterMinutes()).isEqualTo(15L))
                .satisfies(e -> assertThat(((LoginLockedException) e).getUsername()).isEqualTo(USERNAME))
                .satisfies(e -> assertThat(((LoginLockedException) e).getIp()).isEqualTo(IP));
    }

    /** 通过 {@code valueOperations.get} 执行本例并核对输出或记录。 */
    @Test
    void counterAboveLimitStillLocked() {
        // 计数可能已经大于 maxAttempts（例如锁定窗口内又有人失败过）
        when(valueOperations.get(KEY)).thenReturn("9");

        assertThatThrownBy(() -> throttle.assertAllowed(IP, USERNAME))
                .isInstanceOf(LoginLockedException.class);
    }

    /** 覆盖失败的相关边界。通过 {@code valueOperations.increment} 执行本例并核对输出或记录。 */
    @Test
    void recordFailureSetsTtlOnlyOnTheFirstIncrement() {
        when(valueOperations.increment(KEY)).thenReturn(1L);

        throttle.recordFailure(IP, USERNAME);

        verify(redis).expire(KEY, Duration.ofMinutes(15));
    }

    /** 覆盖失败的相关边界。通过 {@code valueOperations.increment} 执行本例并核对输出或记录。 */
    @Test
    void recordFailureDoesNotExtendTtlOnLaterAttempts() {
        when(valueOperations.increment(KEY)).thenReturn(2L);

        throttle.recordFailure(IP, USERNAME);

        // 关键取舍：续期会让攻击者靠持续失败把锁延长到永久
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    /** 通过 {@code throttle.recordSuccess} 执行本例并核对输出或记录。 */
    @Test
    void recordSuccessDeletesTheCounter() {
        throttle.recordSuccess(IP, USERNAME);

        verify(redis).delete(KEY);
    }

    /** 覆盖失败的相关边界。通过 {@code valueOperations.get} 执行本例并核对输出或记录。 */
    @Test
    void mixedCaseUsernamesShareTheSameCounterForReadFailureAndSuccess() {
        when(valueOperations.get(KEY)).thenReturn("4");
        when(valueOperations.increment(KEY)).thenReturn(5L);

        assertThatCode(() -> throttle.assertAllowed(IP, "Alice")).doesNotThrowAnyException();
        throttle.recordFailure(IP, "ALICE");
        throttle.recordSuccess(IP, "aLiCe");

        verify(valueOperations).get(KEY);
        verify(valueOperations).increment(KEY);
        verify(redis).delete(KEY);
    }

    /** 覆盖停用的相关边界。通过 {@code properties.setEnabled} 执行本例并核对输出或记录。 */
    @Test
    void disabledMakesAllThreeMethodsNoOp() {
        properties.setEnabled(false);
        // 用一个从未被 setUp 打过桩的独立 mock，才能干净地断言「零交互」
        StringRedisTemplate untouched = mock(StringRedisTemplate.class);
        LoginThrottle disabledThrottle = new LoginThrottle(untouched, properties);

        assertThatCode(() -> {
            disabledThrottle.assertAllowed(IP, USERNAME);
            disabledThrottle.recordFailure(IP, USERNAME);
            disabledThrottle.recordSuccess(IP, USERNAME);
        }).doesNotThrowAnyException();

        // 登录相关的集成测试靠这个开关避免被自己上一轮的失败计数锁住
        verifyNoInteractions(untouched);
    }

    /** 覆盖失败、Redis的相关边界。通过 {@code valueOperations.get} 执行本例并核对输出或记录。 */
    @Test
    void redisReadFailureDegradesToNoThrottlingInsteadOfFailingLogin() {
        // 可用性优先（设计 §11.4）：Redis 挂掉时登录必须还能用，代价是这段时间不限流。
        // 若改成快速失败，Redis 一挂就是全站登录不可用。
        when(valueOperations.get(KEY)).thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThatCode(() -> throttle.assertAllowed(IP, USERNAME)).doesNotThrowAnyException();
    }

    /** 覆盖失败、Redis的相关边界。通过 {@code valueOperations.increment} 执行本例并核对输出或记录。 */
    @Test
    void redisWriteFailureIsSwallowedSoLoginStillSucceeds() {
        when(valueOperations.increment(KEY)).thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThatCode(() -> throttle.recordFailure(IP, USERNAME)).doesNotThrowAnyException();
    }

    /** 覆盖失败、Redis的相关边界。通过 {@code redis.delete} 执行本例并核对输出或记录。 */
    @Test
    void redisDeleteFailureIsSwallowed() {
        when(redis.delete(KEY)).thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThatCode(() -> throttle.recordSuccess(IP, USERNAME)).doesNotThrowAnyException();
    }

    /** 通过 {@code valueOperations.get} 执行本例并核对输出或记录。 */
    @Test
    void corruptedCounterIsTreatedAsNoThrottling() {
        // 键被人为写成非数字时不该把登录接口整个打死
        when(valueOperations.get(KEY)).thenReturn("not-a-number");

        assertThatCode(() -> throttle.assertAllowed(IP, USERNAME)).doesNotThrowAnyException();
    }
}
