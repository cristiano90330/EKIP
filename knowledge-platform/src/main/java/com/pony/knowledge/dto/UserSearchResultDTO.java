/* 向授权的账号搜索调用方返回用户标识和用户名。 */

package com.pony.knowledge.dto;

import lombok.Data;

/** 用户搜索候选，仅暴露 ID 与用户名，不包含邮箱和密码等账号字段。 */
@Data
public class UserSearchResultDTO {
    private Long id;
    private String username;
}
