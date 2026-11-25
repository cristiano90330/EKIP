/* 传递 Agent 服务调用中的业务异常和原始原因。 */

package com.pony.knowledge.agent.exception;

/**
 * Agent 运行时配置/调用异常：LLM 调用失败、Agent 配置错误等。
 * 抛出后由 GlobalExceptionHandler 统一转成对外错误响应。
 */
public class AgentException extends RuntimeException {

    /**
     * 用给定信息创建异常或子类型，沿用父类构造契约。
     *
     * @param message 待处理的消息或对外提示
     */
    public AgentException(String message) {
        super(message);
    }

    /**
     * 用给定信息创建异常或子类型，沿用父类构造契约。
     *
     * @param message 待处理的消息或对外提示
     * @param cause 本次调用传入的 {@code Throwable}
     */
    public AgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
