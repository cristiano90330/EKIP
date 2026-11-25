/* 接收并校验账号注册的用户名、密码和邮箱。 */

package com.pony.knowledge.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 账户注册输入；用户名、密码和可选邮箱在 Controller 边界校验。 */
@Data
public class RegisterDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 50, message = "用户名长度不能超过50位")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度6-20位")
    private String password;

    @Email(message = "邮箱格式错误")
    @Size(max = 100, message = "邮箱长度不能超过100位")
    private String email;
}
