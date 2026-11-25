/* 标记同一上传请求键关联了不同的文件内容。 */

package com.pony.knowledge.exception;

/** 上传请求键已绑定到不同文件内容时抛出，要求调用方为新上传使用新键。 */
public class UploadIdempotencyConflictException extends RuntimeException {
    /** 创建用于向调用方传递当前错误的异常。 */
    public UploadIdempotencyConflictException() {
        super("该上传请求键已用于不同文件，请为新上传生成新的 requestKey");
    }
}
