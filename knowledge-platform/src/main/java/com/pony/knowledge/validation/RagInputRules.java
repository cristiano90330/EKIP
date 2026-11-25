/* 集中维护问答文本、结果数和允许策略的输入约束。 */

package com.pony.knowledge.validation;

/** 公开问答与检索入口的输入契约；Python 仍独立执行内部请求校验。 */
public final class RagInputRules {
    public static final int MAX_QUESTION_CODE_POINTS = 4000;
    public static final int DEFAULT_TOP_K = 5;
    public static final int MIN_TOP_K = 1;
    public static final int MAX_TOP_K = 20;
    public static final String PIPELINE_ENGINE_PATTERN = "fixed|langchain";
    public static final String STRATEGY_PATTERN = "default|light|rerank_support|rerank_score";

    /** 初始化 RagInputRules，供本文件定义的构建入口使用。 */
    private RagInputRules() {
    }
}
