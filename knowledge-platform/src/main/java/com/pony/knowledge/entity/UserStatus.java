/* 定义数据库保存的账号启停状态。 */

package com.pony.knowledge.entity;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 账号启用状态：NORMAL 对应数据库值 1，DISABLED 对应 0。
 * 登录用户加载时只有 NORMAL 被视为启用，数据库读写和响应使用对应数值。
 */
public enum UserStatus {

    DISABLED(0),

    NORMAL(1);

    // @EnumValue：MyBatis-Plus 用这个字段与数据库 int 列互转，而不是枚举名或 ordinal
    @EnumValue
    private final int code;

    /**
     * 为枚举值绑定供持久化或接口展示使用的编码。
     *
     * @param code 持久化或对外公开的状态码
     */
    UserStatus(int code){
        this.code = code;
    }

    // @JsonValue：与 DocumentStatus 保持一致，实体进响应体时输出数字而不是 "NORMAL"
    /** 返回持久化或对外的状态码。 */
    @JsonValue
    public int getCode() {
        return code;
    }
}
