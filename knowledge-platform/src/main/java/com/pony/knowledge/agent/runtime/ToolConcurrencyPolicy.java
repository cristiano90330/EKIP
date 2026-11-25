/* 根据工具风险和审批策略决定工具能否并发执行。 */

package com.pony.knowledge.agent.runtime;

import com.pony.knowledge.agent.tool.AgentTool;
import com.pony.knowledge.agent.tool.ToolRegistry;

import java.util.Set;

/**
 * 工具并发策略：一次决策里的多个调用，哪些可以并行、哪些必须构成屏障。
 *
 * <p>口径是「**可写即屏障**」：
 * <ul>
 *   <li>声明了 {@code requiresApproval=true} 的工具视为有副作用（将来接入 MCP 写操作），
 *       必须单独执行——它前后不能有别的东西在跑，否则「先读后写」的顺序会失效；</li>
 *   <li>{@code memory_save} 是无需审批的显式写入，也必须形成执行屏障；</li>
 *   <li>显式登记在 {@code agent.react.barrier-tools} 里的工具同样单独执行（给排查/降级留开关）；</li>
 *   <li>其余（含未注册的名字）按可并发处理。</li>
 * </ul>
 *
 * <p>写入屏障与审批独立：记忆保存保持显式调用，无需新增审批交互。
 */
public class ToolConcurrencyPolicy {

    private final ToolRegistry toolRegistry;

    private final Set<String> barrierTools;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     *
     * @param toolRegistry 本次调用传入的 {@code ToolRegistry}
     * @param barrierTools 本次调用传入的 {@code Set<String>}
     */
    public ToolConcurrencyPolicy(ToolRegistry toolRegistry, Set<String> barrierTools) {
        this.toolRegistry = toolRegistry;
        this.barrierTools = barrierTools == null ? Set.of() : Set.copyOf(barrierTools);
    }

    /**
     * 根据注册工具的审批级别和障碍工具配置判断它能否加入当前并发批次。
     * 上游由 {@code ToolActExecutor.execute} 调用。
     * 下游调用 {@code requiresApproval}。
     *
     * @param toolName 工具在注册表中的公开名称
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    public boolean canRunConcurrently(String toolName) {
        if (toolName == null) {
            return false;
        }
        if ("memory_save".equals(toolName) || barrierTools.contains(toolName)) {
            return false;
        }
        return !requiresApproval(toolName);
    }

    /**
     * 读取工具定义，判断实际执行前是否需要审批。
     * 上游由 {@code ToolConcurrencyPolicy.canRunConcurrently} 调用。
     * 下游调用 {@code tool.definition}。
     *
     * @param toolName 工具在注册表中的公开名称
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    private boolean requiresApproval(String toolName) {
        if (toolRegistry == null) {
            return false;
        }
        for (AgentTool tool : toolRegistry.getAll()) {
            if (toolName.equals(tool.definition().getName())) {
                return tool.definition().isRequiresApproval();
            }
        }
        return false;
    }
}
