/* 验证 JWT 的签发、期限、声明与拒绝无效令牌的行为。 */

package com.pony.knowledge.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 密钥外置后的构造期 fail-fast（设计 §4.2 D2 / §3.5）。
 *
 * 这是纯单元测试，不起 Spring：故意直接 new，才能断言「密钥不合规时构造器自己就拒绝」，
 * 而不是等 Spring 的占位符解析或 jjwt 的 WeakKeyException 报一句没人看得懂的错。
 */
class JwtUtilTest {

    /** 恰好 32 字节：HS256 的下限 */
    private static final String MIN_VALID_SECRET = "01234567890123456789012345678901";

    /** openssl rand -base64 48 的形状：64 个字符 = 64 字节 */
    private static final String PRODUCED_BY_OPENSSL =
            "Zm9vYmFyLWJhZm9vLWJhci1mb28tYmFyLWJhZm9vLWJhci1mb28tYmFyLWJhZm9vYmE=";

    /** 覆盖空值的相关边界。 */
    @Test
    void emptySecretFailsFastWithActionableMessage() {
        // application.yml 写的是 ${JWT_SECRET:}（空默认值），占位符能解析成功 →
        // Spring 不会报错，必须由 JwtUtil 自己拦下来
        assertThatThrownBy(() -> new JwtUtil("", 15, 7))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openssl rand -base64 48")
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining(".env");
    }

    /** 检验 {@code blank Secret Fails Fast} 场景。 */
    @Test
    void blankSecretFailsFast() {
        assertThatThrownBy(() -> new JwtUtil("   ", 15, 7))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openssl rand -base64 48");
    }

    /** 覆盖空值的相关边界。 */
    @Test
    void nullSecretFailsFast() {
        assertThatThrownBy(() -> new JwtUtil(null, 15, 7))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openssl rand -base64 48");
    }

    /** 检验 {@code secret Shorter Than32 Bytes Fails Fast} 场景。 */
    @Test
    void secretShorterThan32BytesFailsFast() {
        // 改造前 jjwt 会抛 WeakKeyException，消息只说密钥太弱、不说怎么办
        assertThatThrownBy(() -> new JwtUtil("too-short-secret", 15, 7))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openssl rand -base64 48")
                .hasMessageContaining("16 字节");
    }

    /** 通过 {@code jwtUtil.createAccessToken} 执行本例并核对输出或记录。 */
    @Test
    void exactly32ByteSecretIsAcceptedAndRoundTrips() {
        JwtUtil jwtUtil = new JwtUtil(MIN_VALID_SECRET, 15, 7);

        String token = jwtUtil.createAccessToken(42L);

        assertThat(jwtUtil.getUserId(token)).isEqualTo(42L);
    }

    /** 通过 {@code jwtUtil.createAccessToken} 执行本例并核对输出或记录。 */
    @Test
    void opensslProducedSecretIsAcceptedAndRoundTrips() {
        JwtUtil jwtUtil = new JwtUtil(PRODUCED_BY_OPENSSL, 15, 7);

        String token = jwtUtil.createAccessToken(9_001L);

        assertThat(jwtUtil.getUserId(token)).isEqualTo(9_001L);
    }

    /** 覆盖SSE的相关边界。通过 {@code alreadyExpired.createAccessToken} 执行本例并核对输出或记录。 */
    @Test
    void accessExpireMinutesIsActuallyWired() {
        // 访问令牌有效期来自配置；负数 → 签出即过期。
        JwtUtil alreadyExpired = new JwtUtil(MIN_VALID_SECRET, -1, 7);

        String token = alreadyExpired.createAccessToken(42L);

        assertThatThrownBy(() -> alreadyExpired.getUserId(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    /** 覆盖令牌、刷新的相关边界。通过 {@code jwtUtil.createRefreshToken} 执行本例并核对输出或记录。 */
    @Test
    void refreshTokenIsRejectedAsBearerCredential() {
        // 3.1 P2-16：两张票用 typ 区分，刷新票据冒充 Bearer 访问凭证必须被 getUserId 拒
        JwtUtil jwtUtil = new JwtUtil(MIN_VALID_SECRET, 15, 7);

        String refreshToken = jwtUtil.createRefreshToken(42L, "sid-1");

        assertThatThrownBy(() -> jwtUtil.getUserId(refreshToken))
                .isInstanceOf(JwtException.class);
    }

    /** 覆盖令牌、刷新的相关边界。通过 {@code jwtUtil.createRefreshToken} 执行本例并核对输出或记录。 */
    @Test
    void refreshTokenRoundTripsThroughParseRefreshToken() {
        JwtUtil jwtUtil = new JwtUtil(MIN_VALID_SECRET, 15, 7);

        String refreshToken = jwtUtil.createRefreshToken(42L, "sid-1");
        JwtUtil.RefreshTokenInfo info = jwtUtil.parseRefreshToken(refreshToken);

        assertThat(info.userId()).isEqualTo(42L);
        assertThat(info.tokenId()).isNotBlank();
        // 读回来的必须就是签发时那个 sid——它是整条刷新链的族锚点，不能每次解析都变
        assertThat(info.sessionId()).isEqualTo("sid-1");
        // jti 必须每票唯一，否则 RefreshTokenStore 的按 jti 吊销会误伤
        assertThat(info.tokenId()).isNotEqualTo(jwtUtil.parseRefreshToken(jwtUtil.createRefreshToken(42L, "sid-1")).tokenId());
    }

    /** 覆盖会话、令牌、刷新的相关边界。通过 {@code jwtUtil.createRefreshToken} 执行本例并核对输出或记录。 */
    @Test
    void refreshTokenCarriesTheSessionIdAndRoundTrips() {
        JwtUtil jwtUtil = new JwtUtil(MIN_VALID_SECRET, 15, 7);

        String refreshToken = jwtUtil.createRefreshToken(42L, "sid-abc");
        JwtUtil.RefreshTokenInfo info = jwtUtil.parseRefreshToken(refreshToken);

        assertThat(info.userId()).isEqualTo(42L);
        assertThat(info.tokenId()).isNotBlank();
        // 家族级联吊销的全部前提就是这一个 claim：被重放的票要能自证属于哪条链
        assertThat(info.sessionId()).isEqualTo("sid-abc");
    }

    /** 覆盖会话、令牌的相关边界。通过 {@code Jwts.parserBuilder} 执行本例并核对输出或记录。 */
    @Test
    void accessTokenDoesNotCarrySessionId() {
        // 访问令牌不进 Redis、不参与吊销，给它加 sid 只会让人误以为它也可吊销
        JwtUtil jwtUtil = new JwtUtil(MIN_VALID_SECRET, 15, 7);

        Claims claims = Jwts.parserBuilder()
                .setSigningKey(Keys.hmacShaKeyFor(MIN_VALID_SECRET.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseClaimsJws(jwtUtil.createAccessToken(42L))
                .getBody();

        assertThat(claims.get("sid")).isNull();
        assertThat(claims.get("typ", String.class)).isEqualTo("access");
    }

    /** 覆盖会话、令牌、刷新的相关边界。通过 {@code Keys.hmacShaKeyFor} 执行本例并核对输出或记录。 */
    @Test
    void refreshTokenWithoutSessionIdIsRejected() {
        // 手工签一张「签名正确、typ=refresh、但没有 sid」的票 = 本功能上线前签发的存量票。
        // 它无法自证属于哪个族，放行就等于「这张票可以无限续期却不参与吊销」。
        JwtUtil jwtUtil = new JwtUtil(MIN_VALID_SECRET, 15, 7);
        SecretKey key = Keys.hmacShaKeyFor(MIN_VALID_SECRET.getBytes(StandardCharsets.UTF_8));
        String legacy = Jwts.builder()
                .setId("legacy-jti")
                .setSubject("42")
                .claim("typ", "refresh")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60_000L))
                .signWith(key)
                .compact();

        assertThatThrownBy(() -> jwtUtil.parseRefreshToken(legacy))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("session");
    }

    /** 覆盖会话的相关边界。通过 {@code JwtUtil.newSessionId} 执行本例并核对输出或记录。 */
    @Test
    void newSessionIdIsUniquePerCall() {
        assertThat(JwtUtil.newSessionId()).isNotEqualTo(JwtUtil.newSessionId());
    }

    /** 覆盖刷新的相关边界。通过 {@code jwtUtil.createAccessToken} 执行本例并核对输出或记录。 */
    @Test
    void accessCannotBeParsedAsRefresh() {
        JwtUtil jwtUtil = new JwtUtil(MIN_VALID_SECRET, 15, 7);

        String accessToken = jwtUtil.createAccessToken(42L);

        assertThatThrownBy(() -> jwtUtil.parseRefreshToken(accessToken))
                .isInstanceOf(JwtException.class);
    }

    /** 覆盖令牌的相关边界。通过 {@code signer.createAccessToken} 执行本例并核对输出或记录。 */
    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        JwtUtil signer = new JwtUtil(MIN_VALID_SECRET, 15, 7);
        JwtUtil verifier = new JwtUtil(PRODUCED_BY_OPENSSL, 15, 7);

        String token = signer.createAccessToken(42L);

        // 这条就是「密钥泄露 = 可伪造任意 userId 的 token」的反面：换钥后旧 token 全部失效
        assertThatThrownBy(() -> verifier.getUserId(token))
                .isInstanceOf(JwtException.class);
    }

    /**
     * 「运维没设 JWT_SECRET」这条真实路径的验证（设计 §3.5 / 验收标准 7）。
     *
     * 直接加载 classpath 上的真 application.yml，并把 systemProperties / systemEnvironment 两个
     * property source 摘掉，模拟一个没有 JWT_SECRET 的环境。要钉住的是：
     * ${JWT_SECRET:} 的**空默认值**让占位符解析成功 → Spring 不会报
     * "Could not resolve placeholder 'JWT_SECRET'"，于是「没配密钥」这件事只能由 JwtUtil
     * 构造器自己拦下来，并且报出来的消息必须告诉运维该怎么办。
     */
    /** 覆盖缺失的相关边界。通过 {@code environment.getPropertySources} 执行本例并核对输出或记录。 */
    @Test
    void missingJwtSecretEnvironmentVariableIsCaughtByTheConstructorNotBySpring() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }

        // 占位符解析成功且得到空串（不是抛 IllegalArgumentException）
        String resolvedSecret = environment.getProperty("jwt.secret");
        assertThat(resolvedSecret).isEmpty();
        assertThat(environment.getProperty("jwt.access-expire-minutes")).isEqualTo("15");
        assertThat(environment.getProperty("jwt.refresh-expire-days")).isEqualTo("7");

        // 所以启动失败发生在 JwtUtil 构造期，消息里带着可执行指令
        assertThatThrownBy(() -> new JwtUtil(resolvedSecret, 15, 7))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openssl rand -base64 48")
                .hasMessageContaining("JWT_SECRET");
    }
}
