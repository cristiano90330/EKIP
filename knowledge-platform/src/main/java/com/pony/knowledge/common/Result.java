/* 定义 Java 常规 API 的业务状态、消息与响应数据包装。 */

package com.pony.knowledge.common;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 普通业务接口使用的 JSON 响应包装。code 是响应体字段，HTTP 状态由 Controller、
 * 异常处理器或过滤器另行设置；message 提供说明，data 承载调用方传入的业务数据。
 * RAG 等直接返回专用 DTO 的入口不必经过本包装。
 */
@Data
@AllArgsConstructor
public class Result<T>{

    private Integer code;

    private String message;

    private T data;

    /**
     * 构建状态为成功且携带调用结果的业务响应。
     * 上游为 AgentMemoryController.list、AgentMemoryController.correct。
     *
     * @param data 当前调用需要包装或转交的数据
     * @return 包含 T 数据的业务响应
     */
    public static <T> Result<T> success(T data){

        return new Result<>(200,"success",data);

    }

    /**
     * 构建失败业务响应，返回错误状态与说明，数据字段留空。
     * 上游为 ObservabilityController.getTrace。
     *
     * @param msg 构建失败或提示响应时使用的文字
     * @return 包含 T 数据的业务响应
     */
    public static <T> Result<T> error(String msg){

        return error(500,msg);

    }

    /**
     * 构建失败业务响应，返回错误状态与说明，数据字段留空。
     * 上游为 ObservabilityController.getTrace。
     *
     * @param code 持久化或对外公开的状态码
     * @param msg 构建失败或提示响应时使用的文字
     * @return 包含 T 数据的业务响应
     */
    public static <T> Result<T> error(int code, String msg){

        return new Result<>(code,msg,null);

    }

}
