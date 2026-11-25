/* 生成和校验访问及刷新 JWT，提取会话标识与版本。 */

package com.pony.knowledge.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * 签发并解析访问令牌与刷新令牌。两类令牌共享签名密钥，但通过 typ 区分用途；
 * 访问令牌供 JWT 过滤器建立身份，刷新令牌携带 jti 和会话 sid，供刷新存储维护轮换与吊销。
 * 本类负责令牌本身的校验，不查询知识库权限，也不在访问令牌解析时查询 Redis。
 */
@Component
public class JwtUtil {

    /** 签名密钥要求的最小原始 UTF-8 字节数。 */
    private static final int MIN_SECRET_BYTES = 32;

    /** 密钥配置错误时返回的启动诊断信息。 */
    private static final String SECRET_HINT =
            "JWT 密钥未配置或强度不足（要求非空且原始 UTF-8 字节数 >= " + MIN_SECRET_BYTES + "）。"
                    + "修复步骤：1) 执行 openssl rand -base64 48 生成密钥；"
                    + "2) 把它作为 JWT_SECRET 写入仓库根 .env 的 JWT_SECRET 一项（或注入运行环境变量）后重启。";

    /** 令牌用途字段，区分访问和刷新凭证。 */
    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    /** 同一次登录及其刷新链共享的会话标识，用于定位刷新凭证的吊销范围。 */
    private static final String CLAIM_SESSION = "sid";
    private static final String CLAIM_SESSION_VERSION = "sv";

    private final SecretKey key;

    private final long accessExpireMillis;

    private final long refreshExpireMillis;

    /**
     * 保存生成和校验访问及刷新 JWT所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param secret 从配置或调用凭据提供的密钥内容
     * @param accessExpireMinutes 本方法计算或判断使用的 accessExpireMinutes 值
     * @param refreshExpireDays 本方法计算或判断使用的 refreshExpireDays 值
     */
    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.access-expire-minutes:15}") long accessExpireMinutes,
                   @Value("${jwt.refresh-expire-days:7}") long refreshExpireDays) {

        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(SECRET_HINT);
        }

        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    SECRET_HINT + " 当前长度只有 " + secretBytes.length + " 字节。");
        }

        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.accessExpireMillis = accessExpireMinutes * 60L * 1000L;
        this.refreshExpireMillis = refreshExpireDays * 24L * 60L * 60L * 1000L;
    }

    /**
     * 生成新账号会话使用的标识。
     * 上游为 UserServiceImpl.login。
     *
     * @return 供后续令牌和 Redis 会话记录关联的新 ID
     */
    public static String newSessionId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 按账号和会话版本签发访问 JWT。
     * 上游为 UserServiceImpl.issueTokenPair。
     *
     * @param userId 用户 ID
     * @return 签名后的访问 JWT
     */
    public String createAccessToken(Long userId) {
        return createAccessToken(userId, 0);
    }

    /**
     * 按账号和会话版本签发访问 JWT。
     * 上游为 UserServiceImpl.issueTokenPair。
     * 下游为 JwtUtil.createToken。
     *
     * @param userId 用户 ID
     * @param sessionVersion 账号会话版本
     * @return 签名后的访问 JWT
     */
    public String createAccessToken(Long userId, long sessionVersion) {
        return createToken(userId, TYPE_ACCESS, accessExpireMillis, null, sessionVersion);
    }

    /**
     * 按账号和会话版本签发刷新 JWT。
     * 上游为 UserServiceImpl.issueTokenPair。
     *
     * @param userId 用户 ID
     * @param sessionId 会话标识
     * @return 签名后的刷新 JWT
     */
    public String createRefreshToken(Long userId, String sessionId) {
        return createRefreshToken(userId, sessionId, 0);
    }

    /**
     * 按账号和会话版本签发刷新 JWT。
     * 上游为 UserServiceImpl.issueTokenPair。
     * 下游为 JwtUtil.createToken。
     *
     * @param userId 用户 ID
     * @param sessionId 会话标识
     * @param sessionVersion 账号会话版本
     * @return 签名后的刷新 JWT
     */
    public String createRefreshToken(Long userId, String sessionId, long sessionVersion) {
        return createToken(userId, TYPE_REFRESH, refreshExpireMillis, sessionId, sessionVersion);
    }

    /**
     * 组装 JWT 的主体、用途、有效期、会话标识与版本并签名。
     * 上游为 JwtUtil.createAccessToken、JwtUtil.createRefreshToken。
     * 下游为 Jwts.builder。
     *
     * @param userId 用户 ID
     * @param type 本方法计算或判断使用的 type 值
     * @param ttlMillis 本方法计算或判断使用的 ttlMillis 值
     * @param sessionId 会话标识
     * @param sessionVersion 账号会话版本
     * @return 含登录身份、用途和有效期声明的已签名 JWT
     */
    private String createToken(Long userId, String type, long ttlMillis, String sessionId, long sessionVersion) {
        JwtBuilder builder = Jwts.builder()
                .setId(UUID.randomUUID().toString())
                .setSubject(userId.toString())
                .claim(CLAIM_TYPE, type)
                .claim(CLAIM_SESSION_VERSION, sessionVersion)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + ttlMillis));
        if (sessionId != null) {
            builder.claim(CLAIM_SESSION, sessionId);
        }
        return builder.signWith(key).compact();
    }

    /**
     * 调用 JwtUtil.parseAccessToken 取得当前操作所需的 Long 结果。
     * 下游为 JwtUtil.parseAccessToken。
     *
     * @param token 待验证或轮换的令牌
     * @return 当前已认证的用户 ID
     */
    public Long getUserId(String token) {
        return parseAccessToken(token).userId();
    }

    /**
     * 验证 JWT 签名、时效和访问令牌用途后读取载荷。
     * 上游为 JwtAuthenticationFilter.doFilterInternal。
     * 下游为 JwtUtil.parse、JwtUtil.sessionVersion。
     *
     * @param token 待验证或轮换的令牌
     * @return 校验通过的访问令牌所携带的身份及会话信息
     */
    public AccessTokenInfo parseAccessToken(String token) {
        Claims claims = parse(token);
        if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new JwtException("not an access token");
        }
        return new AccessTokenInfo(Long.valueOf(claims.getSubject()), sessionVersion(claims));
    }

    /**
     * 验证 JWT 签名、时效和刷新令牌用途后读取载荷。
     * 上游为 UserServiceImpl.refresh、UserServiceImpl.logout。
     * 下游为 JwtUtil.parse、JwtUtil.sessionVersion。
     *
     * @param token 待验证或轮换的令牌
     * @return 校验通过的刷新令牌所携带的身份及会话信息
     */
    public RefreshTokenInfo parseRefreshToken(String token) {
        Claims claims = parse(token);

        if (!TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new JwtException("not a refresh token");
        }
        String sessionId = claims.get(CLAIM_SESSION, String.class);
        if (sessionId == null || sessionId.isBlank()) {
            throw new JwtException("refresh token without session id");
        }
        return new RefreshTokenInfo(Long.valueOf(claims.getSubject()), claims.getId(), sessionId, sessionVersion(claims));
    }

    /**
     * 从载荷读取账号会话版本并处理缺省版本值。
     * 上游为 JwtUtil.parseAccessToken、JwtUtil.parseRefreshToken。
     *
     * @param claims 本次操作所用的 Claims 数据
     * @return 令牌声明中持有的账号会话版本
     */
    private static long sessionVersion(Claims claims) {
        Long version = claims.get(CLAIM_SESSION_VERSION, Long.class);
        // Tokens issued before the session-version migration belong to version zero.
        return version == null ? 0 : version;
    }

    /**
     * 返回登录响应公布的访问令牌有效期秒数。
     * 上游为 UserServiceImpl.issueTokenPair。
     *
     * @return 访问令牌的有效时长（秒）
     */
    public long accessExpireSeconds() {
        return accessExpireMillis / 1000L;
    }

    /**
     * 验证 JWT 并要求满足调用入口预期的令牌用途。
     * 上游为 JwtUtil.parseAccessToken、JwtUtil.parseRefreshToken。
     * 下游为 Jwts.parserBuilder。
     *
     * @param token 待验证或轮换的令牌
     * @return 本次计算或查询得到的 Claims 结果
     */
    private Claims parse(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public record AccessTokenInfo(Long userId, long sessionVersion) { }

    public record RefreshTokenInfo(Long userId, String tokenId, String sessionId, long sessionVersion) { }
}
