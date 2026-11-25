/* 实现知识库的创建、列表、属性更新与创建者校验。 */

package com.pony.knowledge.service.impl;

import com.pony.knowledge.audit.AuditAction;
import com.pony.knowledge.audit.AuditLogService;
import com.pony.knowledge.dto.KnowledgeBaseCreateDTO;
import com.pony.knowledge.entity.KnowledgeBase;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.mapper.KnowledgeBaseMapper;
import com.pony.knowledge.mapper.KnowledgeBaseMemberMapper;
import com.pony.knowledge.service.KnowledgeBaseService;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.utils.CurrentUserUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库业务主线：绑定当前用户创建知识库，合并创建库与成员库，并提供创建者权限检查。
 * 由 KnowledgeBaseController 调用；数据查询交给知识库和成员 Mapper，不接受客户端指定创建者。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final CurrentUserUtil currentUserUtil;
    private final KnowledgeBaseMemberMapper knowledgeBaseMemberMapper;
    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final AuditLogService auditLogService;

    /**
     * 以登录账号为创建者建立知识库，并记录创建审计。
     * 上游为 KnowledgeBaseController.create。
     * 下游为 KnowledgeBaseMapper.insert、AuditLogService.record。
     *
     * @param dto 本次操作使用的 KnowledgeBaseCreateDTO 组件或记录
     */
    @Override
    public void create(KnowledgeBaseCreateDTO dto){
        try {
            KnowledgeBase knowledgeBase = new KnowledgeBase();
            knowledgeBase.setName(dto.getName());
            knowledgeBase.setDescription(dto.getDescription());

            Long userId = currentUserUtil.getUserId();
            knowledgeBase.setCreatorId(userId);

            knowledgeBaseMapper.insert(knowledgeBase);
            auditLogService.record(AuditAction.KB_CREATED, "knowledge_base", knowledgeBase.getId(),
                    AuditLogService.details("name", knowledgeBase.getName()));
        } catch (RuntimeException failure) {
            auditLogService.recordFailure(AuditAction.KB_CREATED, "knowledge_base", null, failure, null);
            throw failure;
        }
    }

    /**
     * 读取当前账号创建或获共享的知识库。
     * 上游为 KnowledgeBaseController.list。
     * 下游为 KnowledgeBaseMapper.selectByCreatorId、KnowledgeBaseMemberMapper.selectMemberKbIds。
     *
     * @return 与过滤条件匹配的 List&lt;KnowledgeBase&gt; 数据集合
     */
    @Override
    public List<KnowledgeBase> list() {
        Long userId = currentUserUtil.getUserId();
        List<KnowledgeBase> owned = knowledgeBaseMapper.selectByCreatorId(userId);
        List<Long> memberKbIds = knowledgeBaseMemberMapper.selectMemberKbIds(userId);

        // 没有成员关系时直接返回创建库，省去额外查询。
        if (memberKbIds.isEmpty()) {
            return owned;
        }
        List<KnowledgeBase> memberKbs = knowledgeBaseMapper.selectBatchIds(memberKbIds);

        Map<Long, KnowledgeBase> byId = new LinkedHashMap<>();
        owned.forEach(k -> byId.put(k.getId(), k));
        memberKbs.forEach(k -> byId.putIfAbsent(k.getId(), k));

        return new ArrayList<>(byId.values());
    }

    /**
     * 按所有者约束更新知识库名称和描述，并记录操作结果。
     * 上游为 KnowledgeBaseController.update。
     * 下游为 KnowledgeBaseMapper.updateOwnedDetails、AuditLogService.record。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param dto 本次操作使用的 KnowledgeBaseCreateDTO 组件或记录
     */
    @Override
    public void updateDetails(Long knowledgeBaseId, KnowledgeBaseCreateDTO dto) {
        try {
            int updated = knowledgeBaseMapper.updateOwnedDetails(knowledgeBaseId, currentUserUtil.getUserId(),
                    dto.getName(), dto.getDescription());
            if (updated != 1) {
                throw new ForbiddenException("无权操作该知识库");
            }
            auditLogService.record(AuditAction.KB_UPDATED, "knowledge_base", knowledgeBaseId,
                    AuditLogService.details("name", dto.getName()));
        } catch (RuntimeException failure) {
            auditLogService.recordFailure(AuditAction.KB_UPDATED, "knowledge_base", knowledgeBaseId, failure, null);
            throw failure;
        }
    }

    /**
     * 获取需要维护的知识库，并确保当前账号为创建者。
     * 上游为 KnowledgeBaseRemovalService.remove。
     * 下游为 KnowledgeBaseAccessService.canManageKnowledgeBase。
     *
     * @param knowledgeBaseId 知识库 ID
     */
    @Override
    public void checkOwnership(Long knowledgeBaseId) {
        Long userId = currentUserUtil.getUserId();
        if (!knowledgeBaseAccessService.canManageKnowledgeBase(userId, knowledgeBaseId)) {
            throw new ForbiddenException("无权操作该知识库");
        }
    }
}
