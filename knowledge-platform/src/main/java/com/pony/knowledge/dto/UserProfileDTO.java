/* 向当前账号返回用户标识、用户名与部门。 */

package com.pony.knowledge.dto;

/** 当前账号可以查看的资料。 */
public record UserProfileDTO(Long id, String username, String department) { }
