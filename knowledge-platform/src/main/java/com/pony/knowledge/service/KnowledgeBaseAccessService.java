/* 约定知识库可读范围和创建者操作的权限判定。 */

package com.pony.knowledge.service;

import java.util.Set;

/**
 * 知识库资源授权的公共入口。调用方传入可信的已认证用户 ID；读取与维护权限分别表达，
 * 当前读取允许创建者或成员，文档维护、成员管理、评测和库管理均只允许创建者。
 * 文档删除与重试还在 SQL 中绑定所属知识库创建者，不能用读取授权替代该条件。
 */
public interface KnowledgeBaseAccessService {

    /**
     * 合并用户创建的知识库和成员授权范围。
     * 上游为 AgentService.runTurn、AgentSessionService.currentPolicy。
     *
     * @param userId 用户 ID
     * @return 与过滤条件匹配的 Set&lt;Long&gt; 数据集合
     */
    Set<Long> resolveReadableIds(Long userId);

    /**
     * 允许创建者与已有授权关系的账号读取知识库。
     * 上游为 AgentProfileService.validate、KnowledgeIndexService.requireRead。
     *
     * @param userId 用户 ID
     * @param knowledgeBaseId 知识库 ID
     * @return 符合本方法描述的状态或完成条件时为 true
     */
    boolean canRead(Long userId, Long knowledgeBaseId);

    /**
     * 以创建者身份约束知识库文档的增删改。
     * 上游为 DocumentServiceImpl.requireKnowledgeBaseOwner。
     *
     * @param userId 用户 ID
     * @param knowledgeBaseId 知识库 ID
     * @return 符合本方法描述的状态或完成条件时为 true
     */
    boolean canMaintainDocuments(Long userId, Long knowledgeBaseId);

    /**
     * 以创建者身份约束知识库共享成员管理。
     * 上游为 KnowledgeBaseMemberServiceImpl.requireMemberManagement。
     *
     * @param userId 用户 ID
     * @param knowledgeBaseId 知识库 ID
     * @return 符合本方法描述的状态或完成条件时为 true
     */
    boolean canManageMembers(Long userId, Long knowledgeBaseId);

    /**
     * 检查评测调用方是否拥有对应知识库。
     * 上游为 EvaluationServiceImpl.requireEvaluationAccess。
     *
     * @param userId 用户 ID
     * @param knowledgeBaseId 知识库 ID
     * @return 条件是否成立（检查评测调用方是否拥有对应知识库）
     */
    boolean canEvaluate(Long userId, Long knowledgeBaseId);

    /**
     * 检查是否允许修改或删除对应知识库。
     * 上游为 KnowledgeIndexService.requireOwner、KnowledgeBaseServiceImpl.checkOwnership。
     *
     * @param userId 用户 ID
     * @param knowledgeBaseId 知识库 ID
     * @return 条件是否成立（检查是否允许修改或删除对应知识库）
     */
    boolean canManageKnowledgeBase(Long userId, Long knowledgeBaseId);

}
