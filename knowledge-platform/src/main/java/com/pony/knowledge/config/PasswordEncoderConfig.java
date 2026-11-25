/* 提供账号注册与登录验证共用的密码编码器。 */

package com.pony.knowledge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 提供账户密码哈希使用的 BCrypt PasswordEncoder。 */
@Configuration
public class PasswordEncoderConfig {

    /**
     * 向认证容器注册账号密码编码器。
     * 上游为 Spring 容器组件装配。
     *
     * @return 本次计算或查询得到的 PasswordEncoder 结果
     */
    @Bean
    public PasswordEncoder passwordEncoder(){

        return new BCryptPasswordEncoder();

    }

}
