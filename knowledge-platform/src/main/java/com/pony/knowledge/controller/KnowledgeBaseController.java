/* 提供知识库创建、更新、删除和共享成员管理接口。 */

package com.pony.knowledge.controller;

import com.pony.knowledge.common.Result;
import com.pony.knowledge.dto.KnowledgeBaseCreateDTO;
import com.pony.knowledge.dto.KnowledgeBaseMemberDTO;
import com.pony.knowledge.dto.MemberAddDTO;
import com.pony.knowledge.entity.KnowledgeBase;
import com.pony.knowledge.service.KnowledgeBaseMemberService;
import com.pony.knowledge.service.KnowledgeBaseRemovalService;
import com.pony.knowledge.service.KnowledgeBaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 知识库与成员管理入口：创建、可见列表、添加成员、移除成员和成员列表。
 * 知识库归属由 Service 依据当前用户确定；成员参数 userId 表示被操作的目标账号。
 */
@RestController
@RequestMapping("/knowledge-base")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    private final KnowledgeBaseMemberService knowledgeBaseMemberService;
    private final KnowledgeBaseRemovalService removalService;

    /**
     * 为当前账号建立新的知识库。
     * 上游为 HTTP POST /knowledge-base。
     * 下游为 KnowledgeBaseService.create、Result.success。
     *
     * @param dto 本次操作使用的 KnowledgeBaseCreateDTO 组件或记录
     * @return 包含 String 数据的业务响应
     */
    @PostMapping
    public Result<String> create(@Valid @RequestBody KnowledgeBaseCreateDTO dto){
        knowledgeBaseService.create(dto);
        return Result.success("知识库创建成功");
    }

    /**
     * 返回当前账号拥有和获共享的知识库。
     * 上游为 HTTP GET /knowledge-base/list。
     * 下游为 Result.success、KnowledgeBaseService.list。
     *
     * @return 包含 List&lt;KnowledgeBase&gt; 数据的业务响应
     */
    @GetMapping("/list")
    public Result<List<KnowledgeBase>> list() {
        return Result.success(knowledgeBaseService.list());
    }

    /**
     * 更新当前账号所拥有知识库的名称和说明。
     * 上游为 HTTP PUT /knowledge-base/{knowledgeBaseId}。
     * 下游为 KnowledgeBaseService.updateDetails、Result.success。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param dto 本次操作使用的 KnowledgeBaseCreateDTO 组件或记录
     * @return 包含 String 数据的业务响应
     */
    @PutMapping("/{knowledgeBaseId}")
    public Result<String> update(@PathVariable Long knowledgeBaseId,
                                 @Valid @RequestBody KnowledgeBaseCreateDTO dto) {
        knowledgeBaseService.updateDetails(knowledgeBaseId, dto);
        return Result.success("知识库已更新");
    }

    /**
     * 删除当前账号所拥有的知识库及其内容。
     * 上游为 HTTP DELETE /knowledge-base/{knowledgeBaseId}。
     * 下游为 Result.success、KnowledgeBaseRemovalService.remove。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 包含 KnowledgeBaseRemovalService.Removal 数据的业务响应
     */
    @DeleteMapping("/{knowledgeBaseId}")
    public Result<KnowledgeBaseRemovalService.Removal> delete(@PathVariable Long knowledgeBaseId) {
        return Result.success(removalService.remove(knowledgeBaseId));
    }

    /**
     * 在校验知识库管理权限后登记共享账号。
     * 上游为 HTTP POST /knowledge-base/{knowledgeBaseId}/members。
     * 下游为 KnowledgeBaseMemberService.add、Result.success。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param dto 携带当前操作参数的 MemberAddDTO 数据
     * @return 包含 String 数据的业务响应
     */
    @PostMapping("/{knowledgeBaseId}/members")
    public Result<String> addMember(@PathVariable Long knowledgeBaseId,
            @Valid @RequestBody MemberAddDTO dto) {
        knowledgeBaseMemberService.add(knowledgeBaseId, dto.getUserId());
        return Result.success("成员添加成功");
    }

    /**
     * 取消指定账号的知识库成员关系。
     * 上游为 HTTP DELETE /knowledge-base/{knowledgeBaseId}/members/{userId}。
     * 下游为 KnowledgeBaseMemberService.remove、Result.success。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param userId 用户 ID
     * @return 包含 String 数据的业务响应
     */
    @DeleteMapping("/{knowledgeBaseId}/members/{userId}")
    public Result<String> removeMember(@PathVariable Long knowledgeBaseId, @PathVariable Long userId) {
        knowledgeBaseMemberService.remove(knowledgeBaseId, userId);
        return Result.success("成员移除成功");
    }

    /**
     * 返回当前账号有权管理的知识库的共享成员。
     * 上游为 HTTP GET /knowledge-base/{knowledgeBaseId}/members。
     * 下游为 Result.success、KnowledgeBaseMemberService.listMembers。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 包含 List&lt;KnowledgeBaseMemberDTO&gt; 数据的业务响应
     */
    @GetMapping("/{knowledgeBaseId}/members")
    public Result<List<KnowledgeBaseMemberDTO>> listMembers(@PathVariable Long knowledgeBaseId) {
        return Result.success(knowledgeBaseMemberService.listMembers(knowledgeBaseId));
    }
}
