/* 标记当前账号无权访问请求资源或执行操作。 */

package com.pony.knowledge.exception;

/** 当前用户无权访问或操作目标资源时抛出，由全局处理器映射为 HTTP 403。 */
public class ForbiddenException extends BusinessException {

    /**
     * 创建用于向调用方传递当前错误的异常。
     *
     * @param message 用于构建结果或异常的说明文字
     */
    public ForbiddenException(String message){
        super(message);
    }
}
