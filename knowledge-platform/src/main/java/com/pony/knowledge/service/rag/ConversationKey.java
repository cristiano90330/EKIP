/* 为传统问答历史生成按用户、知识库和会话隔离的存储键。 */

package com.pony.knowledge.service.rag;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 问答历史的隔离键，由认证用户、已授权知识库和请求中的 conversationId 共同组成。
 * RagService 完成授权后创建此键；只凭相同 conversationId 不能读到其他用户或知识库的历史。
 */
public record ConversationKey(long userId, long knowledgeBaseId, String conversationId) {

    private static final int MAX_CONVERSATION_ID_LENGTH = 128;

    /**
     * 建立 ConversationKey 数据，并在构造入口应用该记录的值约束。
     *
     * @param userId 用户 ID
     * @param knowledgeBaseId 知识库 ID
     * @param conversationId 传统问答会话的外部标识
     */
    public ConversationKey {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (knowledgeBaseId <= 0) {
            throw new IllegalArgumentException("knowledgeBaseId must be positive");
        }
        Objects.requireNonNull(conversationId, "conversationId");
        if (conversationId.isBlank() || conversationId.length() > MAX_CONVERSATION_ID_LENGTH
                || !conversationId.matches("[\\p{L}\\p{N}._:-]+")) {
            throw new IllegalArgumentException("conversationId must contain 1-128 letters, digits, '.', '_', ':' or '-'");
        }
    }

    /**
     * 生成按用户身份、知识库和会话维度区分的历史键。
     * 上游为 RedisConversationStore.loadHistory、RedisConversationStore.append。
     *
     * @return 本次计算或查询得到的 String 结果
     */
    public String storageId() {
        byte[] conversationBytes = conversationId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer input = ByteBuffer.allocate(Long.BYTES * 2 + Integer.BYTES + conversationBytes.length);
        input.putLong(userId).putLong(knowledgeBaseId).putInt(conversationBytes.length).put(conversationBytes);

        try {
            return "v2:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.array()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
