/* 验证和维护当前线程及日志上下文中的请求标识。 */

package com.pony.knowledge.request;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/** Request correlation metadata, independent of tracing span identifiers and authentication. */
public final class RequestIdContext {
    public static final String HEADER = "X-Request-ID";
    public static final String MDC_KEY = "requestId";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /** 初始化 RequestIdContext，供本文件定义的构建入口使用。 */
    private RequestIdContext() {
    }

    /**
     * 读取当前线程或日志上下文的请求关联标识。
     * 上游为 AgentGatewayClient.currentRequestId、AuditLogService.record。
     *
     * @return 当前可关联请求与日志的标识
     */
    public static String current() {
        return CURRENT.get();
    }

    /**
     * 检查客户端请求标识的格式，在缺失或格式不合法时生成新标识。
     * 上游为 RequestIdFilter.doFilterInternal。
     *
     * @param incoming 本方法计算或判断使用的 incoming 值
     * @return 校验通过的外部请求标识或新生成的标识
     */
    public static String resolve(String incoming) {
        return incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming : UUID.randomUUID().toString();
    }

    /**
     * 设置线程和 MDC 请求标识，返回可恢复原值的作用域。
     * 上游为 ModelHttpDeadline.run、TracePropagatingTaskDecorator.observationTaskDecorator。
     * 下游为 MDC.remove、MDC.put。
     *
     * @param requestId 关联客户端请求与日志的请求标识
     * @return 关闭后恢复原有标识的请求作用域
     */
    public static Scope open(String requestId) {
        String previous = CURRENT.get();
        String previousMdc = MDC.get(MDC_KEY);
        assign(requestId);
        return () -> {
            assign(previous);
            if (previousMdc == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previousMdc);
            }
        };
    }

    /**
     * 更新线程变量与日志 MDC 中的请求标识。
     * 上游为 RequestIdContext.open。
     * 下游为 MDC.remove、MDC.put。
     *
     * @param requestId 关联客户端请求与日志的请求标识
     */
    private static void assign(String requestId) {
        if (requestId == null) {
            CURRENT.remove();
            MDC.remove(MDC_KEY);
        } else {
            CURRENT.set(requestId);
            MDC.put(MDC_KEY, requestId);
        }
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        /** 释放本实例在作用域内占用的连接、执行器或上下文。 */
        @Override
        void close();
    }
}
