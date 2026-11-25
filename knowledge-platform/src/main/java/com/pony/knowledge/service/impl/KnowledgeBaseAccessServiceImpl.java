/* 根据创建者与成员关系计算知识库读写、成员管理和评测权限。 */

package com.pony.knowledge.service.impl;

import com.pony.knowledge.entity.KnowledgeBase;
import com.pony.knowledge.mapper.KnowledgeBaseMapper;
import com.pony.knowledge.mapper.KnowledgeBaseMemberMapper;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库资源授权规则：创建者或成员可以读取，维护、成员管理与评测只允许创建者。
 * 所有写侧操作共用创建者判断；文档删除和重试的 SQL 继续按相同归属条件限制目标。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseAccessServiceImpl implements KnowledgeBaseAccessService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseMemberMapper knowledgeBaseMemberMapper;

    /**
     * 合并用户创建的知识库和成员授权范围。
     * 上游为 AgentService.runTurn、AgentSessionService.currentPolicy。
     * 下游为 KnowledgeBaseMapper.selectByCreatorId、KnowledgeBaseMemberMapper.selectMemberKbIds。
     *
     * @param userId 用户 ID
     * @return 与过滤条件匹配的 Set&lt;Long&gt; 数据集合
     */
    @Override
    public Set<Long> resolveReadableIds(Long userId) {
        if (userId == null || userId <= 0) {
            return Set.of();
        }
        Set<Long> ids = knowledgeBaseMapper.selectByCreatorId(userId).stream()
                .map(KnowledgeBase::getId)
                .collect(Collectors.toSet());
        ids.addAll(knowledgeBaseMemberMapper.selectMemberKbIds(userId));
        return ids;
    }

    /**
     * 允许创建者与已有授权关系的账号读取知识库。
     * 上游为 AgentProfileService.validate、KnowledgeIndexService.requireRead。
     * 下游为 KnowledgeBaseMemberMapper.existsMember。
     *
     * @param userId 用户 ID
     * @param knowledgeBaseId 知识库 ID
     * @return 符合本方法描述的状态或完成条件时为 true
     */
    @Override
    public boolean canRead(Long userId, Long knowledgeBaseId) {
        if (!validIdentityAndResource(userId, knowledgeBaseId)) {
            return false;
        }
        return isCreator(userId, knowledgeBaseId)
                || knowledgeBaseMemberMapper.existsMember(knowledgeBaseId, userId);
    }

    /**
     * 以创建者身份约束知识库文档的增删改。
     * 上游为 DocumentServiceImpl.requireKnowledgeBaseOwner。
     * 下游为 KnowledgeBaseAccessServiceImpl.isCreator。
     *
     * @param userId 用户 ID
     * @param knowledgeBaseId 知识库 ID
     * @return 符合本方法描述的状态或完成条件时为 true
     */
    @Override
    public boolean canMaintainDocuments(Long userId, Long knowledgeBaseId) {
        return isCreator(userId, knowledgeBaseId);
    }

    /**
     * 以创建者身份约束知识库共享成员管理。
     * 上游为 KnowledgeBaseMemberServiceImpl.requireMemberManagement。
     * 下游为 KnowledgeBaseAccessServiceImpl.isCreator。
     *
     * @param userId 用户 ID
     * @param knowledgeBaseId 知识库 ID
     * @return 符合本方法描述的状态或完成条件时为 true
     */
    @Override
    public boolean canManageMembers(Long userId, Long knowledgeBaseId) {
        return isCreator(userId, knowledgeBaseId);
    }

    /**
     * 检查评测调用方是否拥有对应知识库。
     * 上游为 EvaluationServiceImpl.requireEvaluationAccess。
     * 下游为 KnowledgeBaseAccessServiceImpl.isCreator。
     *
     * @param userId 用户 ID
     * @param knowledgeBaseId 知识库 ID
     * @return 条件是否成立（检查评测调用方是否拥有对应知识库）
     */
    @Override
    public boolean canEvaluate(Long userId, Long knowledgeBaseId) {
        return isCreator(userId, knowledgeBaseId);
    }

    /**
     * 检查是否允许修改或删除对应知识库。
     * 上游为 KnowledgeIndexService.requireOwner、KnowledgeBaseServiceImpl.checkOwnership。
     * 下游为 KnowledgeBaseAccessServiceImpl.isCreator。
     *
     * @param userId 用户 ID
     * @param knowledgeBaseId 知识库 ID
     * @return 条件是否成立（检查是否允许修改或删除对应知识库）
     */
    @Override
    public boolean canManageKnowledgeBase(Long userId, Long knowledgeBaseId) {
        return isCreator(userId, knowledgeBaseId);
    }

    /**
     * 核对目标知识库的归属是否与用户匹配。
     * 上游为 KnowledgeBaseAccessServiceImpl.canRead、KnowledgeBaseAccessServiceImpl.canMaintainDocuments。
     * 下游为 KnowledgeBaseMapper.existsByIdAndCreatorId。
     *
     * @param userId 用户 ID
     * @param knowledgeBaseId 知识库 ID
     * @return 条件是否成立（核对目标知识库的归属是否与用户匹配）
     */
    private boolean isCreator(Long userId, Long knowledgeBaseId) {
        return validIdentityAndResource(userId, knowledgeBaseId)
                && knowledgeBaseMapper.existsByIdAndCreatorId(knowledgeBaseId, userId);
    }

    /**
     * 判断账号和知识库标识是否具备有效格式。
     * 上游为 KnowledgeBaseAccessServiceImpl.canRead、KnowledgeBaseAccessServiceImpl.isCreator。
     *
     * @param userId 用户 ID
     * @param knowledgeBaseId 知识库 ID
     * @return 条件是否成立（判断账号和知识库标识是否具备有效格式）
     */
    private static boolean validIdentityAndResource(Long userId, Long knowledgeBaseId) {
        return userId != null && userId > 0 && knowledgeBaseId != null && knowledgeBaseId > 0;
    }
}
