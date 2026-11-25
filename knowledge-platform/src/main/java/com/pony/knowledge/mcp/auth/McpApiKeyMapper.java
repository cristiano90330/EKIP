/* 按摘要查找 MCP 密钥并更新最后使用时间。 */

package com.pony.knowledge.mcp.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface McpApiKeyMapper extends BaseMapper<McpApiKey> {

    /**
     * 根据密钥摘要查找关联的密钥记录。
     * 上游为 McpApiKeyServiceImpl.authenticate。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param keyHash 根据密钥计算的存储摘要
     * @return MCP 密钥摘要、归属、状态及使用时间，以 McpApiKey 返回
     */
    @Select("SELECT k.* FROM mcp_api_key k JOIN sys_user u ON u.id = k.user_id WHERE k.key_hash = #{keyHash} AND u.status = 1")
    McpApiKey selectByKeyHash(@Param("keyHash") String keyHash);

    /**
     * 刷新指定密钥的最后使用时间。
     * 上游为 McpApiKeyServiceImpl.authenticate。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param id 密钥记录 ID
     * @param usedTime 本次操作所用的 LocalDateTime 数据
     * @return 数据库中受本次写操作影响的行数
     */
    @Update("UPDATE mcp_api_key SET last_used_time = #{usedTime}, update_time = #{usedTime} WHERE id = #{id}")
    int touchLastUsed(@Param("id") Long id, @Param("usedTime") LocalDateTime usedTime);
}
