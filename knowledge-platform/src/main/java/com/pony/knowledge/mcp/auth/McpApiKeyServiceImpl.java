/* 管理 MCP 密钥的存储、所有权、有效期和审计。 */

package com.pony.knowledge.mcp.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.pony.knowledge.audit.AuditAction;
import com.pony.knowledge.audit.AuditLogService;
import com.pony.knowledge.dto.McpApiKeyIssueDTO;
import com.pony.knowledge.exception.BusinessException;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.vo.McpApiKeyIssuedVO;
import com.pony.knowledge.vo.McpApiKeyVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class McpApiKeyServiceImpl implements McpApiKeyService {

    private final McpApiKeyMapper mcpApiKeyMapper;

    private final AuditLogService auditLogService;

    /**
     * 生成并保存密钥摘要，记录审计后返回明文和公开元数据。
     * 上游为 McpApiKeyController.issue。
     * 下游为 McpApiKeyGenerator.newPlainKey、McpApiKeyGenerator.sha256Hex。
     *
     * @param userId 用户 ID
     * @param dto 携带当前操作参数的 McpApiKeyIssueDTO 数据
     * @return 本次得到的 McpApiKeyIssuedVO 实例
     */
    @Override
    public McpApiKeyIssuedVO issue(Long userId, McpApiKeyIssueDTO dto) {
        try {
            String plainKey = McpApiKeyGenerator.newPlainKey();
            McpApiKey entity = new McpApiKey();
            entity.setUserId(userId);
            entity.setKeyHash(McpApiKeyGenerator.sha256Hex(plainKey));
            entity.setKeyPrefix(McpApiKeyGenerator.displayPrefix(plainKey));
            entity.setName(dto.getName());
            entity.setStatus(McpApiKeyStatus.ACTIVE.getCode());
            mcpApiKeyMapper.insert(entity);

            // 明文 Key 只在签发响应里出现一次，审计只记公开元数据。
            auditLogService.record(AuditAction.API_KEY_CREATED, "mcp_api_key", entity.getId(),
                    AuditLogService.details("name", entity.getName(), "key_prefix", entity.getKeyPrefix()));

            return McpApiKeyIssuedVO.builder()
                    .id(entity.getId()).name(entity.getName())
                    .keyPrefix(McpApiKeyGenerator.displayPrefix(plainKey)).apiKey(plainKey).build();
        } catch (RuntimeException failure) {
            auditLogService.recordFailure(AuditAction.API_KEY_CREATED, "mcp_api_key", null, failure, null);
            throw failure;
        }
    }

    /**
     * 按密钥所有者筛选记录并映射为公开视图。
     * 上游为 McpApiKeyController.list。
     * 下游为 McpApiKeyMapper.selectList、McpApiKeyVO.builder。
     *
     * @param userId 用户 ID
     * @return 与过滤条件匹配的 List&lt;McpApiKeyVO&gt; 数据集合
     */
    @Override
    public List<McpApiKeyVO> list(Long userId) {
        return mcpApiKeyMapper.selectList(new QueryWrapper<McpApiKey>()
                        .eq("user_id", userId)
                        .orderByDesc("id"))
                .stream()
                .map(key -> McpApiKeyVO.builder()
                        .id(key.getId())
                        .name(key.getName())
                        .keyPrefix(key.getKeyPrefix())
                        .status(statusName(key.getStatus()))
                        .expiresAt(key.getExpiresAt())
                        .lastUsedTime(key.getLastUsedTime())
                        .createTime(key.getCreateTime())
                        .build())
                .toList();
    }

    /**
     * 检查密钥归属并持久化有效密钥的吊销状态。
     * 上游为 McpApiKeyController.revoke。
     * 下游为 McpApiKeyMapper.selectById、McpApiKeyMapper.updateById。
     *
     * @param userId 用户 ID
     * @param keyId MCP 密钥记录 ID
     */
    @Override
    public void revoke(Long userId, Long keyId) {
        try {
            McpApiKey key = mcpApiKeyMapper.selectById(keyId);
            if (key == null) throw new BusinessException("密钥不存在");
            if (!key.getUserId().equals(userId)) throw new ForbiddenException("无权吊销该密钥");
            key.setStatus(McpApiKeyStatus.REVOKED.getCode());
            key.setUpdateTime(LocalDateTime.now());
            mcpApiKeyMapper.updateById(key);

            auditLogService.record(AuditAction.API_KEY_REVOKED, "mcp_api_key", key.getId(),
                    AuditLogService.details("name", key.getName(), "key_prefix", key.getKeyPrefix()));
        } catch (RuntimeException failure) {
            auditLogService.recordFailure(AuditAction.API_KEY_REVOKED, "mcp_api_key", keyId, failure, null);
            throw failure;
        }
    }

    /**
     * 按摘要查找密钥，校验状态和有效期后建立认证结果。
     * 上游为 McpApiKeyAuthFilter.doFilterInternal。
     * 下游为 McpApiKeyMapper.selectByKeyHash、McpApiKeyGenerator.sha256Hex。
     *
     * @param plainKey 仅用于签发或认证的明文密钥
     * @return 本次得到的 McpAuthResult 实例
     */
    @Override
    public McpAuthResult authenticate(String plainKey) {
        McpApiKey key = mcpApiKeyMapper.selectByKeyHash(McpApiKeyGenerator.sha256Hex(plainKey));
        if (key == null) {
            return McpAuthResult.denied("凭证无效");
        }
        if (Integer.valueOf(McpApiKeyStatus.REVOKED.getCode()).equals(key.getStatus())) {
            return McpAuthResult.denied("凭证已吊销");
        }
        LocalDateTime now = LocalDateTime.now();
        if (key.getExpiresAt() != null && key.getExpiresAt().isBefore(now)) {
            return McpAuthResult.denied("凭证已过期");
        }
        mcpApiKeyMapper.touchLastUsed(key.getId(), now);
        return McpAuthResult.granted(key);
    }

    /**
     * 把密钥持久化状态转换为列表界面使用的名称。
     * 上游为 McpApiKeyServiceImpl.list。
     *
     * @param status 要读取或写入的状态
     * @return 前端使用的 REVOKED 或 ACTIVE
     */
    private String statusName(Integer status) {
        return Integer.valueOf(McpApiKeyStatus.REVOKED.getCode()).equals(status)
                ? McpApiKeyStatus.REVOKED.name()
                : McpApiKeyStatus.ACTIVE.name();
    }
}
