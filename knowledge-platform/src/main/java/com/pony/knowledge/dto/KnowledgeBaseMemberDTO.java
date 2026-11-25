/* 向知识库管理者暴露成员标识和用户名。 */

package com.pony.knowledge.dto;

import lombok.Data;

/** 知识库成员列表项，仅暴露成员用户 ID 与用户名。 */
@Data
public class KnowledgeBaseMemberDTO {
    private Long userId;
    private String username;
}
