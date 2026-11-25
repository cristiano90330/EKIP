/* 标记调用方请求了无法使用的问答管线。 */

package com.pony.knowledge.exception;

/** A user selected a pipeline outside the explicit RAG engine allowlist. */
public class InvalidRagPipelineException extends RuntimeException {
    /** 创建用于向调用方传递当前错误的异常。 */
    public InvalidRagPipelineException() {
        super("pipelineEngine 只能是 fixed 或 langchain");
    }
}
