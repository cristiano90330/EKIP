/* 接收评测问题、知识库、标签、历史和相关切片标注。 */

package com.pony.knowledge.dto;

import com.pony.knowledge.service.rag.query.ChatMessage;
import lombok.Data;

import java.util.List;

@Data
public class EvaluationRequestDTO {
    private Long knowledgeBaseId;

    private String question;

    private String groundTruth;

    private List<Long> relevantChunkIds;

    /** 对话历史（多轮用例；hybrid-rerank-rewrite 档用它做 Query Rewrite，单轮留空） */
    private List<ChatMessage> history;

    /** 难度：normal / hard */
    private String difficulty;

    /** 失败类型：keyword / synonym / cross-chunk / ambiguity */
    private String type;
}
