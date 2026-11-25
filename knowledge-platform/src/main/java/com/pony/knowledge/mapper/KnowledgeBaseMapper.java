/* 按创建者查询和更新知识库，提供归属判定。 */

package com.pony.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pony.knowledge.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 知识库数据访问：读取某用户创建的库，并按库 ID 与创建者 ID 判断归属。
 * 通用插入由 BaseMapper 提供；当前用户身份和授权决策由上层 Service 负责。
 */
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

    /**
     * 按创建者 ID 查询其拥有的知识库。
     * 上游为 KnowledgeBaseAccessServiceImpl.resolveReadableIds、KnowledgeBaseServiceImpl.list。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param userId 用户 ID
     * @return 与过滤条件匹配的 List&lt;KnowledgeBase&gt; 数据集合
     */
    @Select("""
            SELECT *
            FROM knowledge_base
            WHERE creator_id = #{userId}
            ORDER BY create_time DESC
            """)
    List<KnowledgeBase> selectByCreatorId(@Param("userId") Long userId);

    /**
     * 以知识库和创建者为联合条件修改名称与说明。
     * 上游为 KnowledgeBaseServiceImpl.updateDetails。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param userId 用户 ID
     * @param name 记录对外显示的名称
     * @param description 本方法计算或判断使用的 description 值
     * @return 数据库中受本次写操作影响的行数
     */
    @Update("""
            UPDATE knowledge_base
            SET name = #{name}, description = #{description}, update_time = CURRENT_TIMESTAMP
            WHERE id = #{knowledgeBaseId} AND creator_id = #{userId}
            """)
    int updateOwnedDetails(@Param("knowledgeBaseId") Long knowledgeBaseId, @Param("userId") Long userId,
                           @Param("name") String name, @Param("description") String description);

    /**
     * 查询指定账号是否为目标知识库创建者。
     * 上游为 KnowledgeBaseAccessServiceImpl.isCreator、KnowledgeBaseMemberServiceImpl.add。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param userId 用户 ID
     * @return 符合本方法描述的状态或完成条件时为 true
     */
    @Select("""
            SELECT COUNT(*) > 0
            FROM knowledge_base
            WHERE id = #{knowledgeBaseId}
            AND creator_id = #{userId}
            """)
    boolean existsByIdAndCreatorId(
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("userId") Long userId
    );
}
