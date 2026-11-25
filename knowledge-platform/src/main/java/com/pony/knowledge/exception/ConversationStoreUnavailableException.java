/* 标记会话存储故障，供问答入口返回依赖不可用。 */

package com.pony.knowledge.exception;

/** Redis-backed chat history is unavailable; callers must not continue as if history were empty. */
public class ConversationStoreUnavailableException extends RuntimeException {
    /**
     * 创建用于向调用方传递当前错误的异常。
     *
     * @param message 用于构建结果或异常的说明文字
     * @param cause 当前流程捕获的 Throwable 异常
     */
    public ConversationStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
