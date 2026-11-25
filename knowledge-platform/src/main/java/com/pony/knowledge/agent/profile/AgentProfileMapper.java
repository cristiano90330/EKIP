/* 提供按用户列举与定位自建 Agent 配置的数据库查询。 */

package com.pony.knowledge.agent.profile;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** Agent 配置的数据访问接口；按用户过滤的查询是授权边界，调用方不再自行拼归属条件。 */
@Mapper
public interface AgentProfileMapper extends BaseMapper<AgentProfile> {

    /**
     * 按所有者读取自建 Agent 配置列表，并解析知识库和工具列表字段。
     * 上游由 {@code AgentProfileService.list} 调用。
     *
     * @param userId 当前用户 ID
     * @return 调用方使用的 {@code List<AgentProfile>}
     */
    @Select("SELECT id, user_id, name, chat_model_id, system_prompt, kb_selection_mode, knowledge_base_ids, "
            + "allowed_tools, answer_policy, memory_read_enabled, memory_write_enabled, memory_suggestions_enabled, create_time, update_time "
            + "FROM agent_profile WHERE user_id = #{userId} ORDER BY update_time DESC, id DESC")
    @Results(id = "agentProfile", value = {
            @Result(column = "knowledge_base_ids", property = "knowledgeBaseIdsJson"),
            @Result(column = "allowed_tools", property = "allowedToolsJson")
    })
    List<AgentProfile> selectByUserId(@Param("userId") Long userId);

    /**
     * 同时使用配置 ID 和用户 ID 查找归属匹配的配置实体。
     * 上游由 {@code AgentProfileService.requireOwned} 调用。
     *
     * @param id 目标自建 Agent 配置 ID
     * @param userId 当前用户 ID
     * @return 数据库持久化的自建 Agent 配置实体
     */
    @Select("SELECT id, user_id, name, chat_model_id, system_prompt, kb_selection_mode, knowledge_base_ids, "
            + "allowed_tools, answer_policy, memory_read_enabled, memory_write_enabled, memory_suggestions_enabled, create_time, update_time "
            + "FROM agent_profile WHERE id = #{id} AND user_id = #{userId}")
    @ResultMap("agentProfile")
    AgentProfile selectOwned(@Param("id") Long id, @Param("userId") Long userId);
}
