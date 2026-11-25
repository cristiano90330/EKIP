/* 定义文档状态及其持久化数值。 */

package com.pony.knowledge.entity;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/** 文档解析生命周期状态；持久化和 JSON 响应均使用显式数字编码。 */
public enum DocumentStatus {

    UPLOADED(0),

    PROCESSING(1),

    COMPLETED(2),

    FAILED(3),

    CANCELLED(4);

    /** MyBatis-Plus 使用该字段与数据库整数列互转。 */
    @EnumValue
    private final int code;

    /**
     * 为枚举值绑定供持久化或接口展示使用的编码。
     *
     * @param code 持久化或对外公开的状态码
     */
    DocumentStatus(int code){
        this.code = code;
    }

    /** 返回持久化或对外的状态码。 */
    @JsonValue
    public int getCode() {
        return code;
    }
}
