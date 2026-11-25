/* 用 Redis 交互替身验证刷新令牌登记、轮换、复用和吊销。 */

package com.pony.knowledge.security;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 刷新票据登记表与「会话死亡标记」（设计 §3.1 / §5.2）。
 *
 * 本测试钉住三条容易被后人「顺手改回简洁」的取舍：
 * ① redeem 是三态而不是可空 Long —— ERROR（Redis 故障）绝不与 MISSING 混同，
 *    否则一次抖动就把用户整条登录链判成被盗（设计 §5.1，本轮唯一真正的危险点）；
 * ② isSessionDead 读不到时返回 true（fail-closed：核验不了就不发新票）；
 * ③ save 仍 fail-open（Redis 抖动不阻断登录）。
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenStoreTest {

    private static final String JTI = "0f1e2d3c-0000-0000-0000-000000000001";
    private static final String LIVE_KEY = "auth:refresh:" + JTI;
    private static final String SID = "sess-42";
    private static final String DEAD_KEY = "auth:family:dead:" + SID;
    private static final Duration TTL = Duration.ofDays(7);

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RefreshTokenStore store;

    /** 每个用例前准备夹具，调用 {@code redis.opsForValue}、{@code lenient}。 */
    @BeforeEach
    void setUp() {
        store = new RefreshTokenStore(redis, 7L);
        // 只有走登记的用例才读 opsForValue，其余用 lenient 避免 strict stubs 报 UnnecessaryStubbing
        lenient().when(redis.opsForValue()).thenReturn(valueOperations);
    }

    /** 覆盖会话、刷新的相关边界。通过 {@code store.save} 执行本例并核对输出或记录。 */
    @Test
    void saveWritesUserIdAndSessionIdUnderRefreshTtl() {
        store.save(JTI, 7L, SID);

        verify(valueOperations).set(LIVE_KEY, "7|sess-42", TTL);
    }

    /** 覆盖失败的相关边界。通过 {@code store.save} 执行本例并核对输出或记录。 */
    @Test
    void saveFailureIsFailOpenAndDoesNotThrow() {
        // 登录不该被 Redis 抖动挡下：登记失败只是这个会话下次刷新需重新登录
        doThrow(new RedisConnectionFailureException("down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> store.save(JTI, 7L, SID)).doesNotThrowAnyException();
    }

    /** 通过 {@code valueOperations.getAndDelete} 执行本例并核对输出或记录。 */
    @Test
    void redeemReturnsConsumedWithBothFieldsWhenRecordExists() {
        when(valueOperations.getAndDelete(LIVE_KEY)).thenReturn("7|sess-42");

        RefreshTokenStore.Redeemed result = store.redeem(JTI);

        assertThat(result.status()).isEqualTo(RefreshTokenStore.RedeemStatus.CONSUMED);
        assertThat(result.userId()).isEqualTo(7L);
        assertThat(result.sessionId()).isEqualTo(SID);
    }

    /** 覆盖缺失的相关边界。通过 {@code valueOperations.getAndDelete} 执行本例并核对输出或记录。 */
    @Test
    void redeemReturnsMissingWhenRecordAbsent() {
        when(valueOperations.getAndDelete(LIVE_KEY)).thenReturn(null);

        assertThat(store.redeem(JTI).status()).isEqualTo(RefreshTokenStore.RedeemStatus.MISSING);
    }

    /** 覆盖缺失、Redis的相关边界。通过 {@code valueOperations.getAndDelete} 执行本例并核对输出或记录。 */
    @Test
    void redeemReturnsErrorNotMissingWhenRedisIsDown() {
        // 整个设计最要紧的一条断言：ERROR 与 MISSING 必须可区分。
        // 若这里退化成 MISSING，Service 会把它当重放 → 一次 Redis 抖动 = 用户全端被踢。
        when(valueOperations.getAndDelete(LIVE_KEY))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThat(store.redeem(JTI).status()).isEqualTo(RefreshTokenStore.RedeemStatus.ERROR);
    }

    /** 覆盖缺失的相关边界。通过 {@code valueOperations.getAndDelete} 执行本例并核对输出或记录。 */
    @Test
    void redeemReturnsErrorWhenRecordIsCorruptedNotMissing() {
        // 登记值本身坏了（缺分隔符）同样不能升级成重放：那是数据问题不是安全问题，
        // 灭族会掩盖真实故障原因
        when(valueOperations.getAndDelete(LIVE_KEY)).thenReturn("garbage-without-separator");

        assertThat(store.redeem(JTI).status()).isEqualTo(RefreshTokenStore.RedeemStatus.ERROR);
    }

    /** 覆盖会话、SSE的相关边界。通过 {@code redis.hasKey} 执行本例并核对输出或记录。 */
    @Test
    void isSessionDeadTrueWhenMarkerExists() {
        when(redis.hasKey(DEAD_KEY)).thenReturn(true);

        assertThat(store.isSessionDead(SID)).isTrue();
    }

    /** 覆盖会话、SSE的相关边界。通过 {@code redis.hasKey} 执行本例并核对输出或记录。 */
    @Test
    void isSessionDeadFalseWhenMarkerAbsent() {
        when(redis.hasKey(DEAD_KEY)).thenReturn(false);

        assertThat(store.isSessionDead(SID)).isFalse();
    }

    /** 覆盖会话、Redis、SSE的相关边界。通过 {@code redis.hasKey} 执行本例并核对输出或记录。 */
    @Test
    void isSessionDeadFailsClosedWhenRedisIsDown() {
        when(redis.hasKey(DEAD_KEY)).thenThrow(new RedisConnectionFailureException("down"));

        // 宁可不发新票让用户重登，也不在无法核验吊销状态时放行
        assertThat(store.isSessionDead(SID)).isTrue();
    }

    /** 覆盖会话、刷新的相关边界。通过 {@code store.killSession} 执行本例并核对输出或记录。 */
    @Test
    void killSessionWritesMarkerUnderRefreshTtl() {
        store.killSession(SID);

        verify(valueOperations).set(eq(DEAD_KEY), anyString(), eq(TTL));
    }

    /** 覆盖会话、失败的相关边界。通过 {@code store.killSession} 执行本例并核对输出或记录。 */
    @Test
    void killSessionWriteFailureIsFailOpenAndDoesNotThrow() {
        // 这次清算没落盘，但重放者仍拿不到新票（redeem 已消费该 jti），下次重放会再判一次
        doThrow(new RedisConnectionFailureException("down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> store.killSession(SID)).doesNotThrowAnyException();
    }

    /** 通过 {@code store.revoke} 执行本例并核对输出或记录。 */
    @Test
    void revokeDeletesOnlyTheLiveRecord() {
        store.revoke(JTI);

        verify(redis).delete(LIVE_KEY);
        verify(redis, never()).hasKey(anyString());
    }
}
