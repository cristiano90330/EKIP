/* 承载工具运行结果、工具调用标识、证据和诊断信息。 */

package com.pony.knowledge.agent.tool.output;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.pony.knowledge.agent.tool.approval.MemorySaveApproval;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ToolResult<T> {

    private String toolName;

    /** 与 LLM 发来的 ToolCall.callId 一一对应，由 ToolExecutor 填充 */
    private String callId;

    private boolean success;

    private T data;

    /** Optional server-rendered reply for a completed data lookup or write confirmation. */
    private String directAnswer;

    @JsonIgnore
    private MemorySaveApproval.Proposal memorySaveProposal;

    private String errorMessage;

    /** RAG 工具的四态结果；普通工具可为空。 */
    private String outcome;

    /** RAG 依赖失败或证据拒绝的稳定原因码；普通工具可为空。 */
    private String reasonCode;

    /**
     * 是否为可重试的瞬时失败。白名单口径：超时、IO 与中断型 IO、
     * Spring HTTP 客户端异常（5xx、429）、工具池饱和拒绝；其余（含工具里的代码 bug）一律 false。
     */
    private boolean retryable;
}
