/* 从数据库加载账号凭证和状态供登录认证使用。 */

package com.pony.knowledge.security;

import com.pony.knowledge.entity.User;
import com.pony.knowledge.entity.UserStatus;
import com.pony.knowledge.mapper.UserMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 登录时按用户名读取账号，并转换为认证所需的用户 ID、密码哈希和启用状态。
 * 只承担登录用户加载；后续业务请求的当前身份由 JwtAuthenticationFilter 建立。
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserMapper userMapper;

    /**
     * 保存从数据库加载账号凭证和状态供登录认证使用所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param userMapper UserMapper，负责提供登录账号、启停版本、部门和管理列表的数据库操作
     */
    public UserDetailsServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 为认证框架加载指定用户名的账号凭据与状态。
     * 下游为 UserMapper.selectByUsername。
     *
     * @param username 登录用户名
     * @return 本次计算或查询得到的 UserDetails 结果
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userMapper.selectByUsername(username);

        if (user == null) {
            throw new UsernameNotFoundException("用户不存在: " + username);
        }

        boolean enabled = user.getStatus() == UserStatus.NORMAL;

        return new AuthenticatedUserDetails(user.getId(), user.getUsername(), user.getPassword(), enabled,
                user.getSessionVersion());
    }
}
