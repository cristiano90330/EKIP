/* 接收更新登录凭证所需的刷新令牌。 */

package com.pony.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 刷新 / 登出请求体，只携带刷新票据。 */
@Data
public class RefreshDTO {

    @NotBlank(message = "刷新令牌不能为空")
    private String refreshToken;
}
