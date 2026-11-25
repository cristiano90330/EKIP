/* 表达单次 Agent 循环对下一轮执行或停止的控制结果。 */

package com.pony.knowledge.agent.runtime;

/**
 * 一次 ReAct 迭代之后的控制流指令（对应 WeKnora 的 {@code iterOutcome}）。
 *
 * <p>把「继续转圈」「不增轮次重跑」「结束回合」三种结局显式化，是为了让
 * {@code executeLoop} 里只剩一条 switch，而不是在每个终止分支里各自 return——
 * 后者正是改造前终止条件散落、难以枚举的原因。
 */
public enum IterOutcome {

    /** 正常推进：本轮已执行工具并回灌结果，轮次 +1 后继续。 */
    NEXT,

    /** 不消耗轮次重跑：空回复 nudge 用，避免「重试」吃掉迭代预算。 */
    CONTINUE,

    /** 结束回合：已确定最终答案、或命中某条终止护栏。 */
    BREAK
}
