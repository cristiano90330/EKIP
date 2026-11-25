/* 映射知识库与获准用户的成员关系。 */

package com.pony.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 知识库与用户之间的成员关系记录，用于授权读取，不授予创建者管理权限。 */
@Data
@TableName("knowledge_base_member")
public class KnowledgeBaseMember {
    private Long id;
    private Long knowledgeBaseId;
    private Long userId;
    private LocalDateTime createTime;
}
