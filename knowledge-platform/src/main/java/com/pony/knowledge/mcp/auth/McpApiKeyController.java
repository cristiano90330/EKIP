/* 提供当前账号的 MCP 密钥签发、列表和吊销操作。 */

package com.pony.knowledge.mcp.auth;

import com.pony.knowledge.common.Result;
import com.pony.knowledge.dto.McpApiKeyIssueDTO;
import com.pony.knowledge.utils.CurrentUserUtil;
import com.pony.knowledge.vo.McpApiKeyIssuedVO;
import com.pony.knowledge.vo.McpApiKeyVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * MCP 密钥管理接口。走现有 JWT 认证（SecurityConfig 只对 /mcp 精确路径放行），
 * 身份从 CurrentUserUtil 取，不接受前端传 userId。
 */
@RestController
@RequestMapping("/mcp/keys")
@RequiredArgsConstructor
public class McpApiKeyController {

    private final McpApiKeyService mcpApiKeyService;

    private final CurrentUserUtil currentUserUtil;

    /**
     * 为当前账号签发新 MCP 密钥并返回首次可见的明文。
     * 上游为 HTTP POST /mcp/keys。
     * 下游为 Result.success、McpApiKeyService.issue。
     *
     * @param dto 携带当前操作参数的 McpApiKeyIssueDTO 数据
     * @return 包含 McpApiKeyIssuedVO 数据的业务响应
     */
    @PostMapping
    public Result<McpApiKeyIssuedVO> issue(@Valid @RequestBody McpApiKeyIssueDTO dto) {
        return Result.success(mcpApiKeyService.issue(currentUserUtil.getUserId(), dto));
    }

    /**
     * 读取当前账号拥有的 MCP 密钥元数据。
     * 上游为 HTTP GET /mcp/keys。
     * 下游为 Result.success、McpApiKeyService.list。
     *
     * @return 包含 List&lt;McpApiKeyVO&gt; 数据的业务响应
     */
    @GetMapping
    public Result<List<McpApiKeyVO>> list() {
        return Result.success(mcpApiKeyService.list(currentUserUtil.getUserId()));
    }

    /**
     * 请求吊销当前账号拥有的指定密钥。
     * 上游为 HTTP DELETE /mcp/keys/{keyId}。
     * 下游为 McpApiKeyService.revoke、Result.success。
     *
     * @param keyId MCP 密钥记录 ID
     * @return 包含 String 数据的业务响应
     */
    @DeleteMapping("/{keyId}")
    public Result<String> revoke(@PathVariable Long keyId) {
        mcpApiKeyService.revoke(currentUserUtil.getUserId(), keyId);
        return Result.success("密钥已吊销");
    }
}
