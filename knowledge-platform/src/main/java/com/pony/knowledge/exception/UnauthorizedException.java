/* 标记请求缺少可用的登录身份。 */

package com.pony.knowledge.exception;

/** 身份未认证或认证凭证不可用时抛出，由全局处理器映射为 HTTP 401。 */
public class UnauthorizedException extends BusinessException {

    /**
     * 创建用于向调用方传递当前错误的异常。
     *
     * @param message 用于构建结果或异常的说明文字
     */
    public UnauthorizedException(String message){
        super(message);
    }
}
