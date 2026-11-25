/* 接收加入知识库的成员用户 ID。 */

package com.pony.knowledge.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 添加知识库成员的请求体，userId 表示被添加的目标用户。 */
@Data
public class MemberAddDTO {
    @NotNull
    private Long userId;
}
