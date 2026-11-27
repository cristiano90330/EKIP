/* 验证对象回收候选由有效引用、对象年龄和策略范围共同决定。 */

package com.pony.knowledge.service.storage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 升级项 01 章 3.1 P2-16：删除顺序反转（P1-6）后必然累积孤儿对象，
 * 本测试钉死回收任务的判定边界——尤其是「绝不能把整个 bucket 删掉」。
 */
class MinioReclaimPlannerTest {

    /** 通过 {@code MinioReclaimPlanner.findOrphans} 驱动该场景，返回的数据、边界或顺序符合断言。 */
    @Test
    void deletesOnlyKeysWithNoDocumentRow() {
        Set<String> orphans = MinioReclaimPlanner.findOrphans(
                List.of("a.pdf", "b.pdf", "c.md"), Set.of("b.pdf", "c.md"));

        assertThat(orphans).containsExactly("a.pdf");
    }

    /** 通过 {@code MinioReclaimPlanner.findOrphans} 驱动该场景，核对 {@code orphans} 为空。 */
    @Test
    void refusesToDeleteWhenReferenceLoadFailed() {
        // 读 document.file_path 抛异常时引用集是空的，此时全删等于清空整个 bucket
        Set<String> orphans = MinioReclaimPlanner.findOrphans(
                List.of("a.pdf", "b.pdf"), Set.of(), false);

        assertThat(orphans).isEmpty();
    }

    /** 通过 {@code MinioReclaimPlanner.findOrphans} 驱动该场景，核对 {@code orphans} 为空。 */
    @Test
    void refusesToDeleteWhenNotASingleDocumentExists() {
        // 库里一条文档都没有 → 要么真是空系统，要么查询出错，两种都不该动手
        Set<String> orphans = MinioReclaimPlanner.findOrphans(
                List.of("a.pdf", "b.pdf"), Set.of(), true);

        assertThat(orphans).isEmpty();
    }

    /** 通过 {@code MinioReclaimPlanner.findOrphans} 驱动该场景，返回的数据、边界或顺序符合断言。 */
    @Test
    void nothingOrphanedWhenBucketEmpty() {
        assertThat(MinioReclaimPlanner.findOrphans(List.of(), Set.of("a.pdf"))).isEmpty();
    }

    /** 通过 {@code MinioReclaimPlanner.findOrphans} 驱动该场景，返回的数据、边界或顺序符合断言。 */
    @Test
    void ignoresNullAndBlankReferencedPaths() {
        // file_path 可为 null（V0 起就有未落盘字节的记录），null 不该进引用集参与比对
        Set<String> orphans = MinioReclaimPlanner.findOrphans(
                List.of("a.pdf", "b.pdf"), Set.of("b.pdf", ""));

        assertThat(orphans).containsExactly("a.pdf");
    }

    /** 验证过滤空白路径后仍必须有可用引用，才允许产出候选对象。 */
    @Test
    void refusesCandidatesWhenAllReferencesAreBlank() {
        assertThat(MinioReclaimPlanner.findOrphans(List.of("a.pdf"), Set.of("", " "))).isEmpty();
    }
}
