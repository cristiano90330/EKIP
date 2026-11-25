/* 约定账号注册、登录、刷新、登出和档案查询服务的行为。 */

package com.pony.knowledge.service;

import com.pony.knowledge.dto.LoginDTO;
import com.pony.knowledge.dto.RegisterDTO;
import com.pony.knowledge.dto.UserProfileDTO;
import com.pony.knowledge.dto.UserSearchResultDTO;
import com.pony.knowledge.vo.LoginVO;

import java.util.List;

/**
 * 账户用例接口：注册、登录与令牌轮换、登出及成员添加时的用户查找。
 * 认证异常向上交给 Controller 记录限流状态，并由统一异常处理映射 HTTP 响应。
 */
public interface UserService {

    /**
     * 规范化并检查注册资料，使用密码摘要保存新账号。
     * 上游为 UserController.register。
     *
     * @param dto 携带当前操作参数的 RegisterDTO 数据
     */
    void register(RegisterDTO dto);

    /**
     * 认证用户名密码，返回同一登录会话的访问与刷新令牌。
     * 上游为 UserController.login。
     *
     * @param dto 携带当前操作参数的 LoginDTO 数据
     * @return 本次得到的 LoginVO 实例
     */
    LoginVO login(LoginDTO dto);

    /**
     * 校验刷新令牌、账号与会话版本，再轮换凭证。
     * 上游为 UserController.refresh。
     *
     * @param refreshToken 刷新令牌
     * @return 本次得到的 LoginVO 实例
     */
    LoginVO refresh(String refreshToken);

    /**
     * 撤销与请求凭证对应的刷新能力和会话。
     * 上游为 UserController.logout。
     *
     * @param refreshToken 刷新令牌
     */
    void logout(String refreshToken);

    /**
     * 根据持久化账号读取当前资料。
     * 上游为 UserController.profile。
     *
     * @param userId 用户 ID
     * @return 本次得到的 UserProfileDTO 实例
     */
    UserProfileDTO profile(Long userId);

    /**
     * 根据用户名查找可共享的有效账号。
     * 上游为 UserController.search。
     *
     * @param keyword 用于模糊匹配的关键词
     * @return 与过滤条件匹配的 List&lt;UserSearchResultDTO&gt; 数据集合
     */
    List<UserSearchResultDTO> search(String keyword);

}
