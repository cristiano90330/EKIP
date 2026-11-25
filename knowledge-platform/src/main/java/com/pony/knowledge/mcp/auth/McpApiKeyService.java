/* 约定 MCP 密钥的签发、可见范围、吊销和认证。 */

package com.pony.knowledge.mcp.auth;

import com.pony.knowledge.dto.McpApiKeyIssueDTO;
import com.pony.knowledge.vo.McpApiKeyIssuedVO;
import com.pony.knowledge.vo.McpApiKeyVO;

import java.util.List;

/**
 * MCP 长期凭证生命周期：签发 / 列表 / 吊销 / 认证。
 *
 * 归属边界与知识库授权一致：用户只能看到并吊销自己的密钥（default-deny）。
 */
public interface McpApiKeyService {

    /**
     * 生成并保存密钥摘要，记录审计后返回明文和公开元数据。
     * 上游为 McpApiKeyController.issue。
     *
     * @param userId 用户 ID
     * @param dto 携带当前操作参数的 McpApiKeyIssueDTO 数据
     * @return 本次得到的 McpApiKeyIssuedVO 实例
     */
    McpApiKeyIssuedVO issue(Long userId, McpApiKeyIssueDTO dto);

    /**
     * 按密钥所有者筛选记录并映射为公开视图。
     * 上游为 McpApiKeyController.list。
     *
     * @param userId 用户 ID
     * @return 与过滤条件匹配的 List&lt;McpApiKeyVO&gt; 数据集合
     */
    List<McpApiKeyVO> list(Long userId);

    /**
     * 检查密钥归属并持久化有效密钥的吊销状态。
     * 上游为 McpApiKeyController.revoke。
     *
     * @param userId 用户 ID
     * @param keyId MCP 密钥记录 ID
     */
    void revoke(Long userId, Long keyId);

    /**
     * 按摘要查找密钥，校验状态和有效期后建立认证结果。
     * 上游为 McpApiKeyAuthFilter.doFilterInternal。
     *
     * @param plainKey 仅用于签发或认证的明文密钥
     * @return 本次得到的 McpAuthResult 实例
     */
    McpAuthResult authenticate(String plainKey);
}
