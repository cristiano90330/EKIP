/* 描述知识问答工具提供给 Agent 的正文、来源和诊断。 */

package com.pony.knowledge.agent.tool.output;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * knowledge_ask 输出：接地答案 + 来源片段 + 改写后的查询（便于观察多轮改写效果）。
 *
 * sources 面向工具消费方（LLM / MCP 客户端），刻意不复用 HTTP 侧的 SourceDTO。
 */
@Data
@Builder
public class KnowledgeAskOutput {

    private String answer;

    private String rewrittenQuery;

    private Integer tokenCost;

    private String outcome;

    private String reasonCode;

    private String failedStage;

    private String requestId;

    private List<Source> sources;

    @Data
    @Builder
    public static class Source {

        private Long documentId;

        private Long chunkId;

        private Long knowledgeBaseId;

        private Integer chunkIndex;

        private String content;

        private String headingPath;

        private Integer pageNo;

        private Double score;

        private Double distance;

        private String sourceType;
    }
}
