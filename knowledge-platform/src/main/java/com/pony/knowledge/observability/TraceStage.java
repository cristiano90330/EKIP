/* 统一 RAG 阶段在指标和轨迹中的名称。 */

package com.pony.knowledge.observability;

public enum TraceStage {
        QUERY_REWRITE,
        VECTOR_RETRIEVAL,
        KEYWORD_RETRIEVAL,
        FUSION,
        RERANK,
        EVIDENCE_GATE,
        COMPRESSION,
        CONTEXT_BUILD,
        PROMPT_BUILD,
        LLM_GENERATION
}
