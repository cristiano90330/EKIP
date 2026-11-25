/* 向登录和刷新调用方返回账号身份及访问、刷新凭证。 */

package com.pony.knowledge.vo;

import lombok.Data;

@Data
public class LoginVO {

    private Long id;

    private String username;

    /** 访问令牌：短有效期，随每次请求带上做认证。 */
    private String token;

    /** 用于换取新令牌；登出时服务端吊销其所属会话。 */
    private String refreshToken;

    /** 访问令牌有效期（秒），供前端判断主动刷新时机。 */
    private long expiresIn;
}
