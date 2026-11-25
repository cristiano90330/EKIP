/* 描述搜索工具返回的证据项及其检索状态。 */

package com.pony.knowledge.agent.tool.output;

import com.pony.knowledge.service.rag.model.RagEvidence;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class KnowledgeSearchOutput {

    private String originalQuery;

    private String rewrittenQuery;

    private List<RagEvidence> evidences;

    private String outcome;

    private String reasonCode;
}
