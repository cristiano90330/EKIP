/* 约定知识库共享成员的添加、移除和查询。 */

package com.pony.knowledge.service;

import com.pony.knowledge.dto.KnowledgeBaseMemberDTO;

import java.util.List;

/**
 * 知识库成员管理接口。实现先校验当前操作者的创建者身份，再处理目标成员；
 * 成员关系为知识库读取提供授权，不赋予成员管理权限。
 */
public interface KnowledgeBaseMemberService {
    /**
     * 检查共享权限和目标账号后新增成员关系。
     * 上游为 KnowledgeBaseController.addMember。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param userId 用户 ID
     */
    void add(Long knowledgeBaseId, Long userId);
    /**
     * 检查共享权限后删除指定成员关系。
     * 上游为 KnowledgeBaseController.removeMember。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param userId 用户 ID
     */
    void remove(Long knowledgeBaseId, Long userId);
    /**
     * 读取知识库共享成员和当前用户名列表。
     * 上游为 KnowledgeBaseController.listMembers。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 与过滤条件匹配的 List&lt;KnowledgeBaseMemberDTO&gt; 数据集合
     */
    List<KnowledgeBaseMemberDTO> listMembers(Long knowledgeBaseId);
}
