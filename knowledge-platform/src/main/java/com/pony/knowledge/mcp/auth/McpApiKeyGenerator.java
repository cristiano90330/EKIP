/* 生成 MCP 密钥并计算用于存储和显示的摘要及前缀。 */

package com.pony.knowledge.mcp.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * MCP 明文密钥生成与哈希（纯函数、无状态）。
 *
 * 明文 = ekip_mcp_ + Base64URL(32 字节 SecureRandom) 无填充，约 52 字符。
 * 入库只存 SHA-256 的小写十六进制；明文只在签发响应里出现一次。
 */
public final class McpApiKeyGenerator {

    public static final String PLAIN_PREFIX = "ekip_mcp_";

    /** 列表与日志可安全展示的明文前缀长度（前缀 9 字符 + 3 字符随机体）。 */
    public static final int DISPLAY_PREFIX_LENGTH = 12;

    private static final int RANDOM_BYTES = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 初始化 McpApiKeyGenerator，供本文件定义的构建入口使用。 */
    private McpApiKeyGenerator() {
    }

    /**
     * 使用安全随机字节生成有业务前缀的明文密钥。
     * 上游为 McpApiKeyServiceImpl.issue。
     * 下游为 Base64.getUrlEncoder。
     *
     * @return 可在签发响应中交付的一次性可见明文密钥
     */
    public static String newPlainKey() {
        byte[] random = new byte[RANDOM_BYTES];
        SECURE_RANDOM.nextBytes(random);
        return PLAIN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    /**
     * 计算明文密钥的 SHA-256 十六进制摘要以供持久化。
     * 上游为 McpApiKeyServiceImpl.issue、McpApiKeyServiceImpl.authenticate。
     *
     * @param plainKey 仅用于签发或认证的明文密钥
     * @return 可用于数据库查找的 SHA-256 十六进制摘要
     */
    public static String sha256Hex(String plainKey) {
        MessageDigest digest;

        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
        byte[] hash = digest.digest(plainKey.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hash.length * 2);

        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /**
     * 为密钥列表截取可公开显示的前缀。
     * 上游为 McpApiKeyServiceImpl.issue。
     *
     * @param plainKey 仅用于签发或认证的明文密钥
     * @return 密钥公开列表用于识别凭证的显示前缀
     */
    public static String displayPrefix(String plainKey) {
        return plainKey.substring(0, Math.min(DISPLAY_PREFIX_LENGTH, plainKey.length()));
    }
}
