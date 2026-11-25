/* 映射知识库名称、描述、创建者和时间戳。 */

package com.pony.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * knowledge_base 表的持久化对象。Service 从创建 DTO 复制名称和描述，并设置创建者；
 * MyBatis-Plus 根据表名和字段映射读写记录，主键及插入时间默认值由数据库定义提供。
 * 该对象也被当前知识库列表接口直接用作响应数据。
 */
@Data
@TableName("knowledge_base")
public class KnowledgeBase {
    private Long id;

    private String name;

    private String description;

    /** 知识库创建者的用户 ID，是创建者权限判断的依据。 */
    private Long creatorId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
