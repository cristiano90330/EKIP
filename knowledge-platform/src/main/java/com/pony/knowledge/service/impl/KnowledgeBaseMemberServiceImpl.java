/* 维护知识库的共享成员关系并记录授权变更审计。 */

package com.pony.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pony.knowledge.audit.AuditAction;
import com.pony.knowledge.audit.AuditLogService;
import com.pony.knowledge.dto.KnowledgeBaseMemberDTO;
import com.pony.knowledge.entity.KnowledgeBaseMember;
import com.pony.knowledge.entity.User;
import com.pony.knowledge.exception.BusinessException;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.mapper.KnowledgeBaseMapper;
import com.pony.knowledge.mapper.KnowledgeBaseMemberMapper;
import com.pony.knowledge.mapper.UserMapper;
import com.pony.knowledge.service.KnowledgeBaseMemberService;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.utils.CurrentUserUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库创建者管理成员关系的业务入口。
 * 先检查操作者是否为创建者，再处理目标成员的存在、重复添加或移除；目标 userId 不代表操作者。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseMemberServiceImpl implements KnowledgeBaseMemberService {

    private final KnowledgeBaseMemberMapper knowledgeBaseMemberMapper;
    private final UserMapper userMapper;
    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final CurrentUserUtil currentUserUtil;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final AuditLogService auditLogService;

    /**
     * 检查共享权限和目标账号后新增成员关系。
     * 上游为 KnowledgeBaseController.addMember。
     * 下游为 KnowledgeBaseMapper.existsByIdAndCreatorId、KnowledgeBaseMemberMapper.existsMember。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param userId 用户 ID
     */
    @Override
    public void add(Long knowledgeBaseId, Long userId) {
        try {
            requireMemberManagement(knowledgeBaseId);
            if (knowledgeBaseMapper.existsByIdAndCreatorId(knowledgeBaseId, userId)) return;
            if (knowledgeBaseMemberMapper.existsMember(knowledgeBaseId, userId)) return;
            if (userMapper.selectById(userId) == null) throw new BusinessException("用户不存在");
            KnowledgeBaseMember m = new KnowledgeBaseMember();
            m.setKnowledgeBaseId(knowledgeBaseId);
            m.setUserId(userId);
            try {
                knowledgeBaseMemberMapper.insert(m);
            } catch (DuplicateKeyException duplicate) {
                // 并发请求已创建该关系，本次按幂等成功处理，不重复记录成员变更。
                return;
            }
            auditLogService.record(AuditAction.KB_MEMBER_ADDED, "knowledge_base", knowledgeBaseId,
                    AuditLogService.details("member_user_id", userId));
        } catch (RuntimeException failure) {
            auditLogService.recordFailure(AuditAction.KB_MEMBER_ADDED, "knowledge_base", knowledgeBaseId,
                    failure, AuditLogService.details("member_user_id", userId));
            throw failure;
        }
    }

    /**
     * 检查共享权限后删除指定成员关系。
     * 上游为 KnowledgeBaseController.removeMember。
     * 下游为 KnowledgeBaseMemberMapper.delete、AuditLogService.record。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param userId 用户 ID
     */
    @Override
    public void remove(Long knowledgeBaseId, Long userId) {
        try {
            requireMemberManagement(knowledgeBaseId);
            int removed = knowledgeBaseMemberMapper.delete(new LambdaQueryWrapper<KnowledgeBaseMember>()
                    .eq(KnowledgeBaseMember::getKnowledgeBaseId, knowledgeBaseId)
                    .eq(KnowledgeBaseMember::getUserId, userId));
            if (removed > 0) {
                auditLogService.record(AuditAction.KB_MEMBER_REMOVED, "knowledge_base", knowledgeBaseId,
                        AuditLogService.details("member_user_id", userId));
            }
        } catch (RuntimeException failure) {
            auditLogService.recordFailure(AuditAction.KB_MEMBER_REMOVED, "knowledge_base", knowledgeBaseId,
                    failure, AuditLogService.details("member_user_id", userId));
            throw failure;
        }
    }

    /**
     * 读取知识库共享成员和当前用户名列表。
     * 上游为 KnowledgeBaseController.listMembers。
     * 下游为 KnowledgeBaseMemberMapper.selectList、UserMapper.selectBatchIds。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 与过滤条件匹配的 List&lt;KnowledgeBaseMemberDTO&gt; 数据集合
     */
    @Override
    public List<KnowledgeBaseMemberDTO> listMembers(Long knowledgeBaseId) {
        requireMemberManagement(knowledgeBaseId);
        List<KnowledgeBaseMember> rows = knowledgeBaseMemberMapper.selectList(
                new LambdaQueryWrapper<KnowledgeBaseMember>()
                        .eq(KnowledgeBaseMember::getKnowledgeBaseId, knowledgeBaseId));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = rows.stream().map(KnowledgeBaseMember::getUserId).toList();
        Map<Long, String> nameById = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
        return rows.stream().map(r -> {
            KnowledgeBaseMemberDTO dto = new KnowledgeBaseMemberDTO();
            dto.setUserId(r.getUserId());
            dto.setUsername(nameById.getOrDefault(r.getUserId(), "未知用户"));
            return dto;
        }).toList();
    }
    /**
     * 要求当前账号有权改变或读取知识库成员配置。
     * 上游为 KnowledgeBaseMemberServiceImpl.add、KnowledgeBaseMemberServiceImpl.remove。
     * 下游为 KnowledgeBaseAccessService.canManageMembers。
     *
     * @param knowledgeBaseId 知识库 ID
     */
    private void requireMemberManagement(Long knowledgeBaseId) {
        if (!knowledgeBaseAccessService.canManageMembers(currentUserUtil.getUserId(), knowledgeBaseId)) {
            throw new ForbiddenException("无权操作该知识库");
        }
    }
}
