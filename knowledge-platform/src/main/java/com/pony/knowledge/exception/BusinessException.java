/* 承载 Java 业务约束无法满足时的异常信息。 */

package com.pony.knowledge.exception;

/** 可映射为业务响应的运行时异常基类。 */
public class BusinessException extends RuntimeException{

    /**
     * 创建用于向调用方传递当前错误的异常。
     *
     * @param message 用于构建结果或异常的说明文字
     */
    public BusinessException(String message){
        super(message);
    }
}
