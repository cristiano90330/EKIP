/* 验证工具调用历史的条数、文本大小和失败原因保留。 */

package com.pony.knowledge.agent.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具步骤入史（P2-1）的容量与形态约束。
 *
 * <p>这些用例守的是「历史不会变成第二个上下文预算黑洞」：条数有上限、摘要按字符截断、
 * 失败必须留痕（否则下一轮会把「工具报错」读成「知识库里没有」）。
 */
class ToolStepsTest {

    /**
     * 在该类型内部准备后续运行所需的数据。
     *
     * @param name 本次调用传入的 {@code String}
     * @param ok 本次调用传入的 {@code boolean}
     * @param summary 本次调用传入的 {@code String}
     * @return 调用方使用的 {@code AgentResponse.ToolInvocation}
     */
    private static AgentResponse.ToolInvocation inv(String name, boolean ok, String summary) {
        return AgentResponse.ToolInvocation.builder()
                .toolName(name).success(ok).dataSummary(summary).errorMessage(summary).build();
    }

    /** 通过 {@code ToolSteps.from} 驱动该场景，布尔状态符合断言，返回的数据、边界或顺序符合断言。 */
    @Test
    void noInvocations_yieldsEmpty() {
        assertTrue(ToolSteps.from(null).isEmpty());
        assertTrue(ToolSteps.from(List.of()).isEmpty(), "无调用时不挂字段");
    }

    /** 通过 {@code ToolSteps.from}、{@code inv} 驱动该场景，实际结果与固定预期一致。 */
    @Test
    void capsStepCount() {
        List<AgentResponse.ToolInvocation> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(inv("knowledge_search", true, "hit"));
        }

        List<ToolStep> steps = ToolSteps.from(many);

        assertEquals(ToolSteps.MAX_STEPS, steps.size(), "一回合最多 10 次工具调用，不能全存");
    }

    /** 通过 {@code steps.get} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void truncatesLongSummary_byCodePoint() {
        String longText = "证据".repeat(200);      // 400 字
        List<ToolStep> steps = ToolSteps.from(List.of(inv("knowledge_search", true, longText)));

        String summary = steps.get(0).getSummary();
        assertTrue(summary.endsWith("…"), "截断要有标记");
        assertEquals(ToolSteps.SUMMARY_CHARS + 1, summary.codePointCount(0, summary.length()),
                "截到上限 + 一个省略号");
    }

    /** 通过 {@code steps.get} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void failedStep_keepsErrorReason() {
        List<ToolStep> steps = ToolSteps.from(
                List.of(inv("knowledge_ask", false, "工具超时（60s）")));

        assertFalse(steps.get(0).isOk());
        assertEquals("工具超时（60s）", steps.get(0).getSummary(),
                "失败原因必须留下：否则下一轮会把「工具报错」读成「知识库没有」");
    }

    /** 通过 {@code steps.get} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void blankSummary_isEmptyStringNotPlaceholder() {
        List<ToolStep> steps = ToolSteps.from(List.of(inv("knowledge_search", true, "   ")));

        assertEquals("", steps.get(0).getSummary(), "空摘要保持空，装配侧据此不输出冒号");
    }
}
