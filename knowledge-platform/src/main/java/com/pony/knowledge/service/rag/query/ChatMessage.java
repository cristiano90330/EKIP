/* 表示问答历史中的消息角色和正文。 */

package com.pony.knowledge.service.rag.query;

import lombok.Data;

/**
 * 对话历史消息（Query Rewrite 用）：role 取 user/assistant，content 为消息正文。
 */
@Data
public class ChatMessage {

    private String role;

    private String content;
}
