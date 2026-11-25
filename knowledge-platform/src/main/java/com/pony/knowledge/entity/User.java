/* 映射账号身份、部门、启停状态和会话版本。 */

package com.pony.knowledge.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 账户记录，供注册写入及登录加载使用。
 * password 保存密码哈希，status 决定账号是否可登录；实体不直接作为登录响应返回。
 */
@Data
@TableName("sys_user")
public class User {

    private Long id;

    private String username;

    private String password;

    private String email;

    private String department;

    private UserStatus status;
    private long sessionVersion;

    private LocalDateTime createTime;

    @TableField(fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

    /**
     * 检查账号当前启用状态和会话版本能否接受令牌。
     *
     * @param version 当前需要验证、记录或使用的版本
     * @return 条件是否成立（检查账号当前启用状态和会话版本能否接受令牌）
     */
    public boolean acceptsSession(long version) {
        return status == UserStatus.NORMAL && sessionVersion == version;
    }
}
