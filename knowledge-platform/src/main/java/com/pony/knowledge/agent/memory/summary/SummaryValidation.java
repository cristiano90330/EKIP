/* 过滤空白或因长度截断的摘要结果，保护历史折叠游标。 */

package com.pony.knowledge.agent.memory.summary;

import com.pony.knowledge.agent.exception.AgentException;

import java.util.Locale;
import java.util.Set;

/**
 * 摘要产出的可用性校验：两档 SummaryService（治理层 / 直连）在返回前走同一份判断。
 *
 * <p>为什么校验要单独成类而不是各写一份：两档的 prompt 已经刻意共用（{@link SummaryPromptBuilder}），
 * 若失败口径分开写，改动只落一边，就会出现「切了 agent.llm.mode 摘要质量变了」这种无法归因的差异。
 *
 * <p>被判为不可用的两类输出：
 * <ul>
 *   <li><b>空正文</b>——写进去会覆盖掉上一版好摘要，而 ContextBuilder 读到一条空 SUMMARY
 *       比读到「还没有摘要」更糟（它仍会占用一个 system 段的位置，却不含任何信息）。</li>
 *   <li><b>撞到输出上限被截断</b>——这是最隐蔽的一类：截断的摘要读起来像一份正常摘要，
 *       只是后半段没了；而后续每一轮都会把它当作「已折入 N 条消息」的唯一记忆，
 *       于是被截断的部分永久丢失，且从上下文里看不出任何异常。</li>
 * </ul>
 *
 * <p>抛 {@link AgentException} 而不是返回布尔：调用方（{@link SummaryCoordinator}）对「不可用」
 * 的处理是统一的一条路径——降级归档并推进游标，不区分具体哪一类不可用；用异常表达就不必
 * 在每个调用点重复 if。
 */
final class SummaryValidation {

    /** 供应商表示「生成被输出上限截断」的 finish_reason 取值（OpenAI / Ollama / 部分自建网关）。 */
    private static final Set<String> TRUNCATING_FINISH_REASONS =
            Set.of("length", "max_tokens", "max_output_tokens");

    /** 保留私有构造入口，以便通过静态方法使用此类型。 */
    private SummaryValidation() {
    }

    /**
     * 检查模型摘要为有效非空正文且未因长度原因中断。
     * 上游由 {@code GatewaySummaryService.summarize}、{@code LlmSummaryService.summarize} 调用。
     *
     * @param content 待处理的正文内容
     * @param finishReason 模型提供的响应结束原因
     */
    static void requireUsable(String content, String finishReason) {
        String reason = finishReason == null ? "" : finishReason.trim().toLowerCase(Locale.ROOT);
        if (TRUNCATING_FINISH_REASONS.contains(reason)) {
            throw new AgentException("摘要生成撞到输出上限被截断（finish_reason=" + reason
                    + "），不完整摘要会永久替代被折入的历史");
        }
        if (content == null || content.isBlank()) {
            throw new AgentException("摘要返回空正文，写进去会覆盖上一版可用摘要");
        }
    }
}
