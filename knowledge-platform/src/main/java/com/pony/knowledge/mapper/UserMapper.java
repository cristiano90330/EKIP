/* 提供登录账号、启停版本、部门和管理列表的数据库操作。 */

package com.pony.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pony.knowledge.dto.UserSearchResultDTO;
import com.pony.knowledge.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 账户数据访问：按用户名加载账号，或返回供添加成员使用的用户候选。
 * 注册查重与登录共用不区分用户名大小写的精确查询；是否允许登录由上层依据账号状态决定。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 按登录用户名取得账号记录。
     * 上游为 UserDetailsServiceImpl.loadUserByUsername、UserServiceImpl.register。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param username 登录用户名
     * @return 账号身份、部门、启停状态和会话版本，以 User 返回
     */
    @Select(
            "select * from sys_user where lower(username) = lower(#{username})"
    )
    User selectByUsername(@Param("username") String username);

    /**
     * 按账号 ID 读取后续认证所需的启停和会话版本。
     * 上游为 JwtAuthenticationFilter.doFilterInternal、AccountAdminService.changeEnabled。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param userId 用户 ID
     * @return 账号身份、部门、启停状态和会话版本，以 User 返回
     */
    @Select("SELECT id, username, status, session_version, department FROM sys_user WHERE id = #{userId}")
    User selectAccountState(@Param("userId") long userId);

    /**
     * 检查授权入口要求的目标账号是否启用且名称匹配。
     * 上游为 AccountAdminService.canManage。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param userId 用户 ID
     * @param username 登录用户名
     * @return 条件是否成立（检查授权入口要求的目标账号是否启用且名称匹配）
     */
    @Select("SELECT EXISTS (SELECT 1 FROM sys_user WHERE id = #{userId} AND status = 1 AND lower(username) = lower(#{username}))")
    boolean isEnabledAccountNamed(@Param("userId") long userId, @Param("username") String username);

    /**
     * 核对给定账号和用户名是否对应同一数据库记录。
     * 上游为 AccountAdminService.isReservedUsername。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param username 登录用户名
     * @param reserved 本方法计算或判断使用的 reserved 值
     * @return 条件是否成立（核对给定账号和用户名是否对应同一数据库记录）
     */
    @Select("SELECT lower(CAST(#{username} AS text)) = lower(CAST(#{reserved} AS text))")
    boolean isSameUsername(@Param("username") String username, @Param("reserved") String reserved);

    /**
     * 按账号游标分页读取账号管理所需的字段。
     * 上游为 AccountAdminService.list。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param afterId 上一页最后一个标识
     * @param limit 本次最多处理或返回的项数
     * @return 与过滤条件匹配的 List&lt;User&gt; 数据集合
     */
    @Select("SELECT id, username, status, department FROM sys_user WHERE id > #{afterId} ORDER BY id LIMIT #{limit}")
    List<User> listAccountsAfter(@Param("afterId") long afterId, @Param("limit") int limit);

    /**
     * 启停目标账号并更新会话版本，立即使旧令牌失效。
     * 上游为 AccountAdminService.changeEnabled。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param userId 用户 ID
     * @param status 要读取或写入的状态
     * @return 数据库中受本次写操作影响的行数
     */
    @Update("""
            UPDATE sys_user
            SET status = #{status}, session_version = session_version + 1, update_time = CURRENT_TIMESTAMP
            WHERE id = #{userId} AND status <> #{status}
            """)
    int updateStatus(@Param("userId") long userId, @Param("status") int status);

    /**
     * 更新目标账号所处的部门。
     * 上游为 DepartmentAdminService.assign。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param userId 用户 ID
     * @param department 部门名称或记录
     * @return 数据库中受本次写操作影响的行数
     */
    @Update("UPDATE sys_user SET department = #{department,jdbcType=VARCHAR}, update_time = CURRENT_TIMESTAMP WHERE id = #{userId}")
    int updateDepartment(@Param("userId") long userId, @Param("department") String department);

    /**
     * 按用户名片段检索允许展示的账号。
     * 上游为 UserServiceImpl.search。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param keyword 用于模糊匹配的关键词
     * @return 与过滤条件匹配的 List&lt;UserSearchResultDTO&gt; 数据集合
     */
    @Select("SELECT id, username FROM sys_user WHERE username LIKE '%' || #{keyword} || '%' ORDER BY id LIMIT 20")
    List<UserSearchResultDTO> searchByUsernameLike(@Param("keyword") String keyword);
}
