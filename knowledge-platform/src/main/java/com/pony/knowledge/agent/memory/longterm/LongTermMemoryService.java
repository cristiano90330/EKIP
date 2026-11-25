/* 处理长期记忆写入、用户管理和按提问相关性检索，约束写入与注入长度。 */

package com.pony.knowledge.agent.memory.longterm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.pony.knowledge.exception.BusinessException;
import com.pony.knowledge.exception.ForbiddenException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 跨会话长期记忆：显式写入 + 词法召回。
 *
 * <p>边界（与 WeKnora 的长期记忆相比**故意做小**，写在这里避免被误读成已对齐）：
 * <ul>
 *   <li><b>只支持显式写入</b>：模型调用 {@code memory_save} 提出申请，用户确认后写入。
 *       没有后台抽取、没有把普通对话自动沉淀成记忆——那需要抽取模型、去重、待确认队列与抑制记录，
 *       是另一个量级的子系统；</li>
 *   <li><b>只做词法召回</b>：字符二元组重合度 + 子串命中的简单打分，不接向量模型。
 *       记忆条目通常只有一两句，词法足够；真正的语义召回要等条目规模上来再谈。</li>
 * </ul>
 *
 * <p>两条安全约束：
 * <ol>
 *   <li>作用域只有 userId（来自认证上下文），任何入参都无法指定别的用户；</li>
 *   <li>注入文本里明确写「与知识库冲突时以知识库为准」——记忆是用户自述，可能已经过期，
 *       不能让一条旧偏好压过检索到的最新制度。</li>
 * </ol>
 */
@Service
public class LongTermMemoryService {

    private final LongTermMemoryStore store;

    private final boolean enabled;

    private final int maxItems;

    private final int injectLimit;

    private final int maxContentChars;

    /**
     * 用注入依赖和构造参数建立此实例执行时使用的状态。
     *
     * @param store 负责持久化当前实体的存储接口
     * @param enabled 本次调用传入的 {@code boolean}
     * @param maxItems 本次调用传入的 {@code int}
     * @param injectLimit 本次调用传入的 {@code int}
     * @param maxContentChars 本次调用传入的 {@code int}
     */
    public LongTermMemoryService(
            LongTermMemoryStore store,
            @Value("${agent.memory.long-term.enabled:true}") boolean enabled,
            @Value("${agent.memory.long-term.max-items:50}") int maxItems,
            @Value("${agent.memory.long-term.inject-limit:5}") int injectLimit,
            @Value("${agent.memory.long-term.max-content-chars:500}") int maxContentChars) {
        this.store = store;
        this.enabled = enabled;
        this.maxItems = Math.max(1, maxItems);
        this.injectLimit = Math.max(0, injectLimit);
        this.maxContentChars = Math.max(20, maxContentChars);
    }

    /**
     * 读取全局长期记忆功能是否开启。
     * 上游由 {@code MemorySaveApprovalService.decidePending}、{@code MemorySaveTool.execute}、{@code MemorySearchTool.execute} 调用。
     *
     * @return 当前记录中的 {@code enabled}
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 返回允许保存的单条记忆正文长度上限。
     * 上游由 {@code MemorySaveApprovalService.decidePending}、{@code MemorySaveTool.execute} 调用。
     *
     * @return 当前记录中的 {@code maxContentChars}
     */
    public int maxContentChars() {
        return maxContentChars;
    }

    /**
     * 返回当前提问可选入模型上下文的长期记忆条数。
     * 上游由 {@code DefaultContextBuilder.longTermBlock} 调用。
     *
     * @return 当前记录中的 {@code injectLimit}
     */
    public int injectLimit() {
        return injectLimit;
    }

    /**
     * 校验开关、用户归属和正文上限后，记录一条经过确认的长期记忆或复用重复项。
     * 上游由 {@code MemorySaveApprovalService.decidePending} 调用。
     * 下游调用 {@code text.length}、{@code store.list}、{@code UUID.randomUUID}。
     *
     * @param userId 当前用户 ID
     * @param content 待处理的正文内容
     * @param kind 长期记忆业务分类
     * @param sessionId 受当前用户与策略约束的服务端会话 ID
     * @return 条件成立时为 true，其他状态按 false 处理
     */
    public boolean remember(Long userId, String content, String kind, String sessionId) {
        if (!enabled || userId == null || content == null || content.isBlank()) {
            return false;
        }
        String text = content.strip();
        if (text.length() > maxContentChars) {
            text = text.substring(0, maxContentChars);
        }
        // 去重按「忽略大小写与首尾空白后的全等」：不做语义去重（没有向量），
        // 但至少让用户重复说同一句话时不会存成两条。
        for (LongTermMemoryItem existing : store.list(userId, maxItems)) {
            if (existing.getContent() != null && existing.getContent().strip().equalsIgnoreCase(text)) {
                return false;
            }
        }
        store.add(userId, LongTermMemoryItem.builder()
                .id(UUID.randomUUID().toString())
                .userId(userId)
                .kind(LongTermMemoryItem.normalizeKind(kind))
                .content(text)
                .sourceSessionId(sessionId)
                .createdAt(Instant.now())
                .build(), maxItems);
        return true;
    }

    /**
     * 从存储读取指定用户的有限记忆列表，用于召回和字段管理。
     * 下游调用 {@code store.list}。
     *
     * @param userId 当前用户 ID
     * @return 调用方使用的 {@code List<LongTermMemoryItem>}
     */
    public List<LongTermMemoryItem> list(Long userId) {
        if (!enabled || userId == null) {
            return List.of();
        }
        return store.list(userId, maxItems);
    }

    /**
     * 按用户返回管理界面所需的长期记忆，并映射为公开字段。
     * 上游由 {@code AgentMemoryController.list} 调用。
     * 下游调用 {@code store.list}、{@code ItemView::from}。
     *
     * @param userId 当前用户 ID
     * @return 调用方使用的 {@code ManagementView}
     */
    public ManagementView managementList(Long userId) {
        return new ManagementView(enabled, maxItems, maxContentChars,
                userId == null ? List.of() : store.list(userId, maxItems).stream().map(ItemView::from).toList());
    }

    /**
     * 校验目标记忆及新正文后，按用户与记录标识修正一条长期记忆。
     * 上游由 {@code AgentMemoryController.correct} 调用。
     * 下游调用 {@code store.update}、{@code LongTermMemoryItem.normalizeKind}、{@code Instant.now}。
     *
     * @param userId 当前用户 ID
     * @param itemId 目标记忆条目 ID
     * @param content 待处理的正文内容
     * @param kind 长期记忆业务分类
     * @return 调用方使用的 {@code ItemView}
     */
    public ItemView correct(Long userId, String itemId, String content, String kind) {
        if (!enabled) throw new BusinessException("长期记忆已全局停用，不能纠正写入；仍可查看和删除已有条目");
        if (userId == null) throw new ForbiddenException("无法确定当前用户");
        if (content == null || content.isBlank() || content.strip().length() > maxContentChars) {
            throw new BusinessException("记忆内容须为 1 到 " + maxContentChars + " 个字符");
        }
        return store.update(userId, itemId, content.strip(), LongTermMemoryItem.normalizeKind(kind), Instant.now())
                .map(ItemView::from).orElseThrow(() -> new ForbiddenException("记忆不存在或无权访问"));
    }

    /**
     * 按用户与记录标识删除长期记忆，并返回删除状态。
     * 上游由 {@code AgentMemoryController.delete} 调用。
     * 下游调用 {@code store.delete}。
     *
     * @param userId 当前用户 ID
     * @param itemId 目标记忆条目 ID
     */
    public void delete(Long userId, String itemId) {
        if (userId == null || !store.delete(userId, itemId)) {
            throw new ForbiddenException("记忆不存在或无权访问");
        }
    }

    /** 公开管理视图不暴露用户身份、来源会话或存储键。 */
    public record ItemView(String id, String kind, String content, Instant createdAt, Instant updatedAt) {
        /**
         * 从输入解析或创建当前类型。
         * 上游由 {@code LongTermMemoryService.correct}、{@code LongTermMemoryService.managementList} 调用。
         * @param item 本次操作涉及的待存储或返回项
         * @return 调用方使用的 {@code ItemView}
         */
        static ItemView from(LongTermMemoryItem item) {
            return new ItemView(item.getId(), item.getKind(), item.getContent(), item.getCreatedAt(), item.getUpdatedAt());
        }
    }
    public record ManagementView(boolean enabled, int maxItems, int maxContentChars, List<ItemView> items) { }

    /**
     * 从用户自己的长期记忆中按提问相关度选择有界条目；空提问按更新时间返回。
     * 上游由 {@code DefaultContextBuilder.longTermBlock}、{@code MemorySearchTool.execute} 调用。
     * 下游调用 {@code store.list}、{@code all.subList}、{@code order.sort}。
     *
     * @param userId 当前用户 ID
     * @param query 待检索或组织上下文的查询文本
     * @param limit 最多返回或保留的条数
     * @return 调用方使用的 {@code List<LongTermMemoryItem>}
     */
    public List<LongTermMemoryItem> recall(Long userId, String query, int limit) {
        if (!enabled || userId == null || limit <= 0) {
            return List.of();
        }

        List<LongTermMemoryItem> all = store.list(userId, maxItems);
        if (all.isEmpty()) {
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return all.size() <= limit ? all : all.subList(0, limit);
        }

        Set<String> queryGrams = grams(query);
        List<LongTermMemoryItem> scored = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (LongTermMemoryItem item : all) {
            double score = score(queryGrams, query, item.getContent());
            if (score > 0) {
                scored.add(item);
                scores.add(score);
            }
        }

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            order.add(i);
        }

        order.sort(Comparator.comparingDouble((Integer i) -> scores.get(i)).reversed());

        List<LongTermMemoryItem> out = new ArrayList<>();
        for (int i = 0; i < order.size() && out.size() < limit; i++) {
            out.add(scored.get(order.get(i)));
        }

        return out;
    }

    /**
     * 把相关长期记忆渲染为受提示词约束的上下文段。
     * 上游由 {@code DefaultContextBuilder.longTermBlock} 调用。
     * 下游调用 {@code sb.append}。
     *
     * @param items 本次调用传入的 {@code List<LongTermMemoryItem>}
     * @return 供后续处理使用的文本或标识
     */
    public String renderBlock(List<LongTermMemoryItem> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("[长期记忆] 该用户此前明确要求记住的内容（用户自述，"
                + "可能已过期；与知识库冲突时以知识库为准）：");
        for (LongTermMemoryItem item : items) {
            sb.append("\n- [").append(item.getKind()).append("] ").append(item.getContent());
        }
        return sb.toString();
    }

    /**
     * 依据原句命中和分词重合度计算记忆与当前搜索的相关性。
     * 上游由 {@code LongTermMemoryService.recall} 调用。
     * 下游调用 {@code content.toLowerCase}、{@code grams}。
     *
     * @param queryGrams 本次调用传入的 {@code Set<String>}
     * @param query 待检索或组织上下文的查询文本
     * @param content 待处理的正文内容
     * @return 按当前方法口径计算或读取的数值
     */
    private static double score(Set<String> queryGrams, String query, String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }

        String normalized = content.toLowerCase(Locale.ROOT);
        double bonus = normalized.contains(query.strip().toLowerCase(Locale.ROOT)) ? 0.3 : 0;
        Set<String> contentGrams = grams(content);
        if (queryGrams.isEmpty() || contentGrams.isEmpty()) {
            return bonus;
        }

        int common = 0;
        for (String gram : queryGrams) {
            if (contentGrams.contains(gram)) {
                common++;
            }
        }

        double jaccard = (double) common / (queryGrams.size() + contentGrams.size() - common);
        return jaccard + bonus;
    }

    /**
     * 从查询或记忆内容提取用于计算相关性的短片段。
     * 上游由 {@code LongTermMemoryService.recall}、{@code LongTermMemoryService.score} 调用。
     * 下游调用 {@code text.toLowerCase}、{@code normalized.split}、{@code token.length}。
     *
     * @param text 待处理正文
     * @return 当前记录中的 {@code grams}
     */
    private static Set<String> grams(String text) {
        Set<String> grams = new LinkedHashSet<>();
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[^0-9a-z\\u4e00-\\u9fff]", " ");
        for (String token : normalized.split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.length() == 1) {
                grams.add(token);
                continue;
            }
            for (int i = 0; i + 2 <= token.length(); i++) {
                grams.add(token.substring(i, i + 2));
            }
        }
        return grams;
    }
}
