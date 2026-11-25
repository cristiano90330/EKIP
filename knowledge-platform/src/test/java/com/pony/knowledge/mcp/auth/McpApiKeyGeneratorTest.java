/* 核对 MCP Key 生成、格式与哈希表示。 */

package com.pony.knowledge.mcp.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpApiKeyGeneratorTest {

    /** 核对 {@code key.startsWith("ekip_mcp_")} 为真。调用路径经过 {@code McpApiKeyGenerator.newPlainKey}。 */
    @Test
    void plainKey_hasPrefix_andIsAbout52Chars() {
        String key = McpApiKeyGenerator.newPlainKey();

        assertTrue(key.startsWith("ekip_mcp_"));
        // ekip_mcp_(9) + Base64URL(32 bytes, no padding)(43)
        assertEquals(52, key.length());
    }

    /** 覆盖Redis的相关边界。通过 {@code McpApiKeyGenerator.newPlainKey} 执行本例并核对输出或记录。 */
    @Test
    void consecutiveKeys_areDistinct() {
        assertNotEquals(McpApiKeyGenerator.newPlainKey(), McpApiKeyGenerator.newPlainKey());
    }

    /** 核对 {@code hash.length()} 与预期 {@code 64} 一致。调用路径经过 {@code McpApiKeyGenerator.newPlainKey}。 */
    @Test
    void hash_is64CharLowerCaseHex_andStableForSameInput() {
        String plain = McpApiKeyGenerator.newPlainKey();

        String hash = McpApiKeyGenerator.sha256Hex(plain);

        assertEquals(64, hash.length());
        assertTrue(hash.matches("^[0-9a-f]{64}$"), "哈希应为小写十六进制: " + hash);
        assertEquals(hash, McpApiKeyGenerator.sha256Hex(plain));
    }

    /** 通过 {@code McpApiKeyGenerator.sha256Hex} 执行本例并核对输出或记录。 */
    @Test
    void differentPlainKeys_produceDifferentHashes() {
        assertNotEquals(
                McpApiKeyGenerator.sha256Hex(McpApiKeyGenerator.newPlainKey()),
                McpApiKeyGenerator.sha256Hex(McpApiKeyGenerator.newPlainKey()));
    }

    /** 核对 {@code prefix.length()} 与预期 {@code 12} 一致。调用路径经过 {@code McpApiKeyGenerator.newPlainKey}。 */
    @Test
    void displayPrefix_isEnoughToRecognize_butCannotRecoverKey() {
        String key = McpApiKeyGenerator.newPlainKey();

        String prefix = McpApiKeyGenerator.displayPrefix(key);

        assertEquals(12, prefix.length());
        assertEquals(key.substring(0, 12), prefix);
        assertTrue(prefix.startsWith("ekip_mcp_"));
    }
}
