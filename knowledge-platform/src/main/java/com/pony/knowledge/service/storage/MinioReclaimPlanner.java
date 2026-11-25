/* 按数据库对象引用和扫描候选找出可回收的孤儿对象。 */

package com.pony.knowledge.service.storage;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 孤儿对象判定的纯逻辑：不碰 MinIO 与数据库，所以「会不会误删」这件事能被完整单测覆盖。
 */
final class MinioReclaimPlanner {

    /** 初始化 MinioReclaimPlanner，供本文件定义的构建入口使用。 */
    private MinioReclaimPlanner() {
    }

    /**
     * 从候选对象键中移除持久化文档仍引用的路径。
     * 上游为 MinioReclaimService.reclaimOrphanObjects。
     *
     * @param staleBucketKeys 需要本方法按序读取或筛选的 List&lt;String&gt; 数据
     * @param referencedPaths 需要本方法按序读取或筛选的 Set&lt;String&gt; 数据
     * @return 与过滤条件匹配的 Set&lt;String&gt; 数据集合
     */
    static Set<String> findOrphans(List<String> staleBucketKeys, Set<String> referencedPaths) {
        return findOrphans(staleBucketKeys, referencedPaths, true);
    }

    /**
     * 从候选对象键中移除持久化文档仍引用的路径。
     * 上游为 MinioReclaimService.reclaimOrphanObjects。
     * 下游为 MinioReclaimPlanner.normalize。
     *
     * @param staleBucketKeys 需要本方法按序读取或筛选的 List&lt;String&gt; 数据
     * @param referencedPaths 需要本方法按序读取或筛选的 Set&lt;String&gt; 数据
     * @param referencedLoadOk 本方法计算或判断使用的 referencedLoadOk 值
     * @return 与过滤条件匹配的 Set&lt;String&gt; 数据集合
     */
    static Set<String> findOrphans(List<String> staleBucketKeys,
                                   Set<String> referencedPaths,
                                   boolean referencedLoadOk) {
        if (!referencedLoadOk || referencedPaths.isEmpty()) {
            return Set.of();
        }
        Set<String> orphans = new LinkedHashSet<>(staleBucketKeys);
        orphans.removeAll(normalize(referencedPaths));
        return orphans;
    }

    /**
     * 整理对象键写法以便对比扫描候选与数据库引用。
     * 上游为 MinioReclaimPlanner.findOrphans。
     *
     * @param referencedPaths 需要本方法按序读取或筛选的 Set&lt;String&gt; 数据
     * @return 与过滤条件匹配的 Set&lt;String&gt; 数据集合
     */
    private static Set<String> normalize(Set<String> referencedPaths) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String path : referencedPaths) {
            if (path != null && !path.isBlank()) {
                normalized.add(path);
            }
        }
        return normalized;
    }
}
