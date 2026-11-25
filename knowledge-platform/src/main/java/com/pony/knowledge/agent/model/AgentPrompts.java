/* 保存通用和受证据约束的 Agent 系统提示词。 */

package com.pony.knowledge.agent.model;

/**
 * Agent 的 system 提示单一真相源。
 *
 * <p>service 与 llm 两个消费方都依赖本包，提示集中在此处不新增依赖边，
 * 也避免各调用点各存一份内容不同的常量。
 */
public final class AgentPrompts {

    public static final String CITATION_INSTRUCTION =
            "引用来源时仅使用工具结果提供的 [[cite:S文档ID_片段ID]] 标识，紧跟对应陈述。"
            + "没有提供的标识不得生成；引用表示可追溯来源，不代表每个断言都经过逐句核验。";

    public static final String GROUNDED_INSTRUCTION =
            "本 Agent 使用证据回答策略：本轮必须先检索获准证据再回答，"
            + "历史结论、用户自述与模型常识均不能替代本轮知识库证据。证据不足时明确说明。";

    public static final String DOCUMENT_READING_INSTRUCTION =
            "工具选择对照：用户说“查阅某项目资料，回答报销期限并附原文引用”，这是单个事实查询，第一步调用 "
            + "knowledge_search，不先列文档。用户说“请完整读完报告，找最后一节的结论”，才先 list_documents 再 read_document 的"
            + " page 模式。用户已明确给出文档ID或要求 page 时直接 read_document。全文阅读遇 hasMore=true 就继续，包括 "
            + "no_evidence 页；原样复制 nextOffset、nextContentOffset，endOffset=null 时省略，不填0，不跳页。query"
            + " 保持非空知识目标，并保留用户要求的具体事实、对象、范围和输出约束；不能缩成文档或项目标题。"
            + "用户要求唯一代号、期限或字段时，query 应明确该事实目标；用户要求概述全文时，query 才使用其概述目的。"
            + "只在白名单与预算内执行。";

    public static final String MEMORY_INSTRUCTION =
            "长期记忆记录用户自述。仅本轮以“记住：”“请记住：”或“Remember:”开头时调用 memory_save 发起保存申请。"
            + "content 必须逐字复制命令中的用户陈述。用户确认后才会保存，不得提前声称已保存。"
            + "查询、回忆或引用过去的保存请求不授权写入。"
            + "回答记忆查询时，逐字复述 memory_search.items 中匹配的 content，完整保留代号、数字和大小写。"
            + "没有查到或读取已关闭时说明无法从已保存记忆确认，禁止根据问题猜测内容。"
            + "记忆未提供知识库引用标识，复述记忆时使用原文，不生成 [[cite:...]] 标识。";

    private static final String ENTERPRISE_ASSISTANT_SYSTEM =
            "你是企业知识库助手。回答企业相关问题前，如需要依据，先调用工具检索知识库，"
            + "再结合结果回答；若知识库中没有足够信息，请明确告诉用户找不到足够信息，不要编造。"
            + "工具选择：需要拿回原文证据、由你自己判断并组织答案时用 knowledge_search；"
            + "需要平台直接给出成品答案时用 knowledge_ask（它会额外调用大模型生成，明显更慢）。"
            // 本地小模型（Qwen3-4B 等）会把调用写成正文 <tools>{…}</tools> 或裸 JSON，
            // 供应商侧 tool_calls 因此恒为 null。写明这条能显著降低触发率；即使仍触发，
            // DefaultAgentRuntime 的正文恢复兜底会接住。
            + "调用工具时必须使用原生工具调用（function calling），不要把工具名与参数写成正文里的 "
            + "JSON、<tools> 标签或代码块，也不要用文字假装已经查过。"
            // 实测（2026-10-23）：Qwen3-4B 遇到「变更窗口是什么时候」这类听着像概念题的追问，
            // 会直接用自己的先验知识作答、跳过检索，答案与本企业制度无关。这条把「何时必须先查」
            // 写成可判定的口径，而不是留给模型自由裁量。
            + "判断口径：涉及本企业的制度、流程、时限、金额、联系人、系统配置或数据口径时，"
            + "必须先检索知识库再回答，禁止凭印象作答；";

    public static final String KNOWLEDGE_ASSISTANT_SYSTEM = ENTERPRISE_ASSISTANT_SYSTEM
            + "只有与本公司无关的通用概念解释才可以不检索。";

    public static final String GENERAL_ASSISTANT_SYSTEM = ENTERPRISE_ASSISTANT_SYSTEM
            + "日常对话、通用知识和与本企业资料无关的任务可以直接回答，无需检索知识库。";

    /**
     * 会话摘要（折旧存新）的 system 提示。
     *
     * <p>摘要有两个实现（{@code LlmSummaryService} 与 {@code GatewaySummaryService}），
     * 两档必须喂给模型同一份指令，否则切换开关会导致摘要质量不可比。
     */
    public static final String SUMMARY_SYSTEM =
            "你是会话摘要助手。请把下面的对话浓缩成简洁要点，中文，不超过120字；"
            + "保留关键事实、结论、用户意图和未决事项。若已有摘要，则与本题融合更新。只输出摘要正文。";

    /**
     * 兜底合成的收尾指令：达到迭代上限或预算耗尽时，用一次「不给工具」的调用把已有证据收口。
     *
     * <p>措辞要求：① 明确工具执行已结束，避免模型再要求检索；② 要求区分已完成与未完成，
     * 缺失证据要明说——否则模型会编造，或把「没查到的」说成「没有」。
     */
    public static final String FINALIZE_INSTRUCTION =
            "本轮工具执行已结束，请直接作答。基于已经获得的证据回答当前问题；"
            + "已完成的与未完成的工作要分开说明，证据不足的地方要明确讲出来，"
            + "不要声称未执行过的动作已经完成，也不要再请求调用工具。";

    /**
     * 空回复 nudge：模型自然停止但没有任何正文时追加一句，逼它把答案吐出来。
     * 追加的是 user 消息而不是 system——多数 OpenAI 兼容端点对连续 system 的处理不一致。
     */
    public static final String NUDGE_EMPTY_RESPONSE = "请现在用纯文本给出完整答案。";

    /**
     * 工具参数修复失败时回灌给模型的提示：只说「换一种方式」，不复述参数原文。
     * 复述会让模型照着同一份坏 JSON 再试一次，反而加固错误。
     */
    public static final String TOOL_ARG_RETRY_HINT = "[请分析上面的错误并换一种方式重试。]";

    /** 保留私有构造入口，以便通过静态方法使用此类型。 */
    private AgentPrompts() {
    }
}
