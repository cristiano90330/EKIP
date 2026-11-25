/* 用字符二元组 Jaccard 相似度衡量结果间的重复程度。 */

package com.pony.knowledge.evaluation.retrieval;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Redundancy（冗余度）：Top-K 检索结果里 chunk 之间的**平均两两 Jaccard 相似度**。
 *
 * <p>用于量化「Top-K 有多少重复信息」：切块带 CHUNK_OVERLAP 重叠 + 同标题节内容相似，
 * 重排后的 Top-K 常含近重复块 —— 这些重复块挤占上下文预算却不带来新信息。
 * MMR 去冗余（决策文档 §4 第 3 项）的目标正是压低这个值。值域 0~1，越低越不冗余。</p>
 *
 * <p>口径：字符二元组 Jaccard（与 Python 侧 MMR 去冗余的口径一致）。
 * 这里**独立实现**（不反向依赖检索编排组件），K≤1 或内容缺失 → 0.0（无冗余可算）。</p>
 */
public final class RedundancyMetric {

    /** 初始化 RedundancyMetric，供本文件定义的构建入口使用。 */
    private RedundancyMetric() {
    }

    /**
     * 计算候选文本两两二元组 Jaccard 相似度的平均值。
     * 上游为 EvaluationServiceImpl.evaluateRetrieval、EvaluationServiceImpl.evaluateBatch。
     * 下游为 RedundancyMetric.jaccard。
     *
     * @param contents 需要本方法按序读取或筛选的 List&lt;String&gt; 数据
     * @return 非重复排列文本对的平均 Jaccard 相似度
     */
    public static double meanPairwiseJaccard(List<String> contents) {
        if (contents == null || contents.size() < 2) {
            return 0.0;
        }
        List<Set<String>> bigrams = contents.stream()
                .map(RedundancyMetric::charBigrams)
                .toList();
        double sum = 0.0;
        int pairs = 0;

        for (int i = 0; i < bigrams.size(); i++) {
            for (int j = i + 1; j < bigrams.size(); j++) {
                sum += jaccard(bigrams.get(i), bigrams.get(j));
                pairs++;
            }
        }
        return pairs == 0 ? 0.0 : sum / pairs;
    }

    /**
     * 将文本转换为用于相似度计算的去重字符二元组。
     *
     * @param text 待处理的文本
     * @return 与过滤条件匹配的 Set&lt;String&gt; 数据集合
     */
    static Set<String> charBigrams(String text) {
        if (text == null) {
            return Set.of();
        }
        StringBuilder sb = new StringBuilder(text.length());

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        String t = sb.toString();
        Set<String> out = new HashSet<>();

        for (int i = 0; i + 1 < t.length(); i++) {
            out.add(t.substring(i, i + 2));
        }
        return out;
    }

    /**
     * 计算两个字符二元组集合的交并比。
     * 上游为 RedundancyMetric.meanPairwiseJaccard。
     *
     * @param a 需要本方法按序读取或筛选的 Set&lt;String&gt; 数据
     * @param b 需要本方法按序读取或筛选的 Set&lt;String&gt; 数据
     * @return 字符二元组交集数占并集数的比例
     */
    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = a.size() <= b.size() ? b : a;
        int inter = 0;

        for (String g : smaller) {
            if (larger.contains(g)) {
                inter++;
            }
        }
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0.0 : (double) inter / union;
    }
}
