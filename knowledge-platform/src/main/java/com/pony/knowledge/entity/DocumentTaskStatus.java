/* 定义文档任务状态及数据库枚举编码。 */

package com.pony.knowledge.entity;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * document_task.status（DDL: `status int NOT NULL DEFAULT 0`）。
 *
 * 写法与 DocumentStatus / UserStatus 完全一致，不另创风格。
 * 缺 @EnumValue 时 MyBatis-Plus 按枚举名（"WAITING"）而不是 code 去写 int 列，实测 Postgres
 * 直接报 `column "status" is of type integer but expression is of type character varying`；
 * 真正无声的是读取侧（手写 SELECT * 走自动映射），靠 DocumentTaskStatusMappingTest 兜住。
 */
public enum DocumentTaskStatus {

    WAITING(0),

    PROCESSING(1),

    SUCCESS(2),

    FAILED(3),

    CANCELLED(4);

    // @EnumValue：MyBatis-Plus 用这个字段与数据库 int 列互转，而不是枚举名或 ordinal
    @EnumValue
    private final int code;

    /**
     * 为枚举值绑定供持久化或接口展示使用的编码。
     *
     * @param code 持久化或对外公开的状态码
     */
    DocumentTaskStatus(int code){
        this.code = code;
    }

    // @JsonValue：与 DocumentStatus 保持一致，实体进响应体时输出数字而不是 "WAITING"
    /** 返回持久化或对外的状态码。 */
    @JsonValue
    public int getCode() {
        return code;
    }
}
