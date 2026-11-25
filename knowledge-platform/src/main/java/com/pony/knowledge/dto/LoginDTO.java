/* 接收并校验登录名和密码。 */

package com.pony.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 登录凭证输入，用户名与密码均为必填字段。 */
@Data
public class LoginDTO {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
