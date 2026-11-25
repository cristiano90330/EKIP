/* 约定知识库创建、更新、查询与所有权检查。 */

package com.pony.knowledge.service;

import com.pony.knowledge.dto.KnowledgeBaseCreateDTO;
import com.pony.knowledge.entity.KnowledgeBase;

import java.util.List;

/**
 * 知识库业务接口，由 Controller 和其他 Service 调用。实现负责读取当前用户、
 * 认定创建者和组织可访问列表，持久化交给 Mapper；当前创建方法执行一次插入，未声明用例级事务。
 */
public interface KnowledgeBaseService {

    /**
     * 以登录账号为创建者建立知识库，并记录创建审计。
     * 上游为 KnowledgeBaseController.create。
     *
     * @param dto 本次操作使用的 KnowledgeBaseCreateDTO 组件或记录
     */
    void create(KnowledgeBaseCreateDTO dto);

    /**
     * 按所有者约束更新知识库名称和描述，并记录操作结果。
     * 上游为 KnowledgeBaseController.update。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param dto 本次操作使用的 KnowledgeBaseCreateDTO 组件或记录
     */
    void updateDetails(Long knowledgeBaseId, KnowledgeBaseCreateDTO dto);

    /**
     * 读取当前账号创建或获共享的知识库。
     * 上游为 KnowledgeBaseController.list。
     *
     * @return 与过滤条件匹配的 List&lt;KnowledgeBase&gt; 数据集合
     */
    List<KnowledgeBase> list();

    /**
     * 获取需要维护的知识库，并确保当前账号为创建者。
     * 上游为 KnowledgeBaseRemovalService.remove。
     *
     * @param knowledgeBaseId 知识库 ID
     */
    void checkOwnership(Long knowledgeBaseId);
}
