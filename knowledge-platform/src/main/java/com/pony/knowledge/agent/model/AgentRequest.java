/* 承载 Agent 问答所用的服务端会话标识、用户提问和自建 Agent 选项。 */

package com.pony.knowledge.agent.model;

import com.pony.knowledge.agent.session.AgentSession;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;
import lombok.Data;

/**
 * Agent 请求入参。注意：userId / 权限不在其中——
 * 系统身份由 AgentService 从 JWT（Authentication）+ Permission 解析，不信任客户端。
 */
@Data
@Builder
public class AgentRequest {

    private String question;

    /**
     * 必填：先调 POST /agent/sessions 创建会话，再用返回的 {@code as_...} id 发起对话。
     */
    @NotNull(message = "请先创建 Agent 会话再发起对话")
    @Pattern(regexp = AgentSession.PUBLIC_ID_PATTERN, message = "请使用服务端签发的 Agent 会话 ID")
    private String sessionId;

    /**
     * 可选：当前用户自己的 Agent 配置 ID。为空时使用默认 Agent（全部可读知识库与全部已注册工具）；
     * 非空时由 AgentService 校验归属并解析实际范围，客户端不能借此传入提示词或工具列表。
     */
    private Long agentId;
}
