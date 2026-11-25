/* 查询知识库共享成员关系和用户可读范围。 */

package com.pony.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pony.knowledge.entity.KnowledgeBaseMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 成员关系的数据访问接口。显式查询提供成员库 ID 和关系存在性，新增与删除使用
 * BaseMapper；创建者关系另从知识库表判断，不能仅凭成员表推导全部读取权限。
 */
@Mapper
public interface KnowledgeBaseMemberMapper extends BaseMapper<KnowledgeBaseMember> {

    /**
     * 查询该账号通过成员关系获得的知识库 ID。
     * 上游为 KnowledgeBaseAccessServiceImpl.resolveReadableIds、KnowledgeBaseServiceImpl.list。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param userId 用户 ID
     * @return 与过滤条件匹配的 List&lt;Long&gt; 数据集合
     */
    @Select("SELECT knowledge_base_id FROM knowledge_base_member WHERE user_id = #{userId}")
    List<Long> selectMemberKbIds(@Param("userId") Long userId);

    /**
     * 查询目标账号是否具有指定知识库的共享关系。
     * 上游为 KnowledgeBaseAccessServiceImpl.canRead、KnowledgeBaseMemberServiceImpl.add。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param userId 用户 ID
     * @return 符合本方法描述的状态或完成条件时为 true
     */
    @Select("SELECT COUNT(*) > 0 FROM knowledge_base_member WHERE knowledge_base_id = #{knowledgeBaseId} AND user_id = #{userId}")
    boolean existsMember(@Param("knowledgeBaseId") Long knowledgeBaseId,
                         @Param("userId") Long userId);
}
