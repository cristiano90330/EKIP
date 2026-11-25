/* 在当前线程及异步任务间传递模型执行的用户和配置快照。 */

package com.pony.knowledge.modelmanagement;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/** Request-scoped authorized model snapshot. Never persisted to conversation storage. */
public final class ModelExecutionContext implements AutoCloseable {
    private record State(Long ownerId, Map<String,Object> execution) { }
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();
    private final State previous;

    /**
     * 保存在当前线程及异步任务间传递模型执行的用户和配置快照所需的依赖和初始配置。
     *
     * @param state 本次操作使用的 State 组件或记录
     */
    private ModelExecutionContext(State state) {
        previous = CURRENT.get();
        if (state == null) CURRENT.remove(); else CURRENT.set(state);
    }

    /**
     * 为当前线程挂接用户和模型执行快照，并保留旧上下文供恢复。
     * 上游为 AgentService.executeTurn。
     * 下游为 ModelExecutionContext.currentOwnerId。
     *
     * @param execution 含当前操作所需属性的映射
     * @return 本次得到的 ModelExecutionContext 实例
     */
    public static ModelExecutionContext open(Map<String,Object> execution) {
        return open(execution, currentOwnerId());
    }

    /**
     * 为当前线程挂接用户和模型执行快照，并保留旧上下文供恢复。
     * 上游为 AgentService.executeTurn。
     * 下游为 ModelExecutionContext.freeze。
     *
     * @param execution 含当前操作所需属性的映射
     * @param ownerId 所有者用户 ID
     * @return 本次得到的 ModelExecutionContext 实例
     */
    @SuppressWarnings("unchecked")
    public static ModelExecutionContext open(Map<String,Object> execution, Long ownerId) {
        return new ModelExecutionContext(new State(ownerId,
                execution == null ? null : (Map<String,Object>)freeze(execution)));
    }

    /**
     * 从线程作用域读取当前的模型执行快照。
     * 上游为 DefaultContextBudgetManager.budget、GatewayAgentLlm.payload。
     *
     * @return 当前线程已绑定的模型执行快照
     */
    public static Map<String,Object> current() {
        State state = CURRENT.get();
        return state == null ? null : state.execution();
    }
    /**
     * 读取当前模型执行作用域所属的账号 ID。
     * 上游为 ModelExecutionContext.open、AiServiceClient.evaluateGenerate。
     *
     * @return 当前模型执行作用域的用户 ID
     */
    public static Long currentOwnerId() {
        State state = CURRENT.get();
        return state == null ? null : state.ownerId();
    }

    /**
     * 捕获当前上下文，供异步执行新线程恢复。
     * 上游为 ToolExecutor.runWithTimeout、ModelHttpDeadline.run。
     *
     * @param operation 由此处在对应上下文或作用域内执行的 Callable&lt;T&gt; 回调
     * @return 本次计算或查询得到的 Callable&lt;T&gt; 结果
     */
    public static <T> Callable<T> wrap(Callable<T> operation) {
        State captured = CURRENT.get();
        return () -> { try (ModelExecutionContext ignored = new ModelExecutionContext(captured)) { return operation.call(); } };
    }
    /**
     * 包装异步任务以传递当前模型执行上下文。
     *
     * @param operation 由此处在对应上下文或作用域内执行的 Runnable 回调
     * @return 本次计算或查询得到的 Runnable 结果
     */
    public static Runnable wrapRunnable(Runnable operation) {
        State captured = CURRENT.get();
        return () -> { try (ModelExecutionContext ignored = new ModelExecutionContext(captured)) { operation.run(); } };
    }
    /**
     * 包装取值函数以传递模型执行上下文和返回结果。
     * 上游为 ToolActExecutor.runBatch。
     *
     * @param operation 由此处在对应上下文或作用域内执行的 Supplier&lt;T&gt; 回调
     * @return 本次计算或查询得到的 Supplier&lt;T&gt; 结果
     */
    public static <T> Supplier<T> wrapSupplier(Supplier<T> operation) {
        State captured = CURRENT.get();
        return () -> { try (ModelExecutionContext ignored = new ModelExecutionContext(captured)) { return operation.get(); } };
    }

    /**
     * 复制执行快照集合并建立不可变视图。
     * 上游为 ModelExecutionContext.open。
     *
     * @param value 待校验或转换的值
     * @return 本次计算或查询得到的 Object 结果
     */
    private static Object freeze(Object value) {
        if (value instanceof Map<?,?> map) {
            Map<String,Object> copy = new LinkedHashMap<>();
            map.forEach((key,item) -> copy.put(String.valueOf(key), freeze(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(freeze(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
    /** 离开当前作用域并恢复调用前的模型执行上下文。 */
    @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
