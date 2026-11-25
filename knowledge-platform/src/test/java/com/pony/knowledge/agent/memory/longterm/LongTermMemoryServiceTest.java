/* 验证长期记忆的用户隔离、管理、去重、排序与容量限制。 */

package com.pony.knowledge.agent.memory.longterm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长期记忆服务（P3-1 最小版）：显式写入 + 词法召回。
 *
 * <p>守两条：① 作用域只有 userId，别人的记忆不可能被写进或读出；② 召回宁缺毋滥——
 * 相关度为 0 的条目一条都不注入，否则无关记忆会占着上下文让模型分心。
 */
class LongTermMemoryServiceTest {

    /**
     * 在该类型内部准备后续运行所需的数据。
     *
     * @param maxItems 本次调用传入的 {@code int}
     * @param injectLimit 本次调用传入的 {@code int}
     * @param enabled 本次调用传入的 {@code boolean}
     * @return 调用方使用的 {@code LongTermMemoryService}
     */
    private LongTermMemoryService service(int maxItems, int injectLimit, boolean enabled) {
        return new LongTermMemoryService(new InMemoryLongTermMemoryStore(),
                enabled, maxItems, injectLimit, 500);
    }

    /** 通过 {@code svc.remember}、{@code controller.list} 驱动用例，抛出 {@code ForbiddenException}，布尔状态符合断言。 */
    @Test
    void management_isCurrentUserScoped_andDoesNotExposeInternalIdentity() {
        LongTermMemoryService svc = service(50, 5, true);
        svc.remember(1L, "原始偏好", "PREFERENCE", "internal-session-key");
        String id = svc.managementList(1L).items().get(0).id();
        com.pony.knowledge.utils.CurrentUserUtil user = org.mockito.Mockito.mock(com.pony.knowledge.utils.CurrentUserUtil.class);
        com.pony.knowledge.agent.controller.AgentMemoryController controller =
                new com.pony.knowledge.agent.controller.AgentMemoryController(svc, user);
        org.mockito.Mockito.when(user.getUserId()).thenReturn(2L);
        assertTrue(controller.list().getData().items().isEmpty());
        org.junit.jupiter.api.Assertions.assertThrows(com.pony.knowledge.exception.ForbiddenException.class,
                () -> controller.correct(id, new com.pony.knowledge.agent.controller.AgentMemoryController.CorrectionRequest("伪造纠正", "FACT")));
        org.junit.jupiter.api.Assertions.assertThrows(com.pony.knowledge.exception.ForbiddenException.class, () -> controller.delete(id));
        org.mockito.Mockito.when(user.getUserId()).thenReturn(1L);
        assertEquals("纠正后", controller.correct(id,
                new com.pony.knowledge.agent.controller.AgentMemoryController.CorrectionRequest("纠正后", "PREFERENCE")).getData().content());
        List<String> fields = java.util.Arrays.stream(LongTermMemoryService.ItemView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertEquals(List.of("id", "kind", "content", "createdAt", "updatedAt"), fields);
        controller.delete(id);
        assertTrue(controller.list().getData().items().isEmpty());
    }

    /** 通过 {@code enabled.remember}、{@code disabled.recall} 驱动用例，抛出 {@code BusinessException}，布尔状态符合断言。 */
    @Test
    void disabledMemoryStillAllowsManagementCleanup_butRejectsCorrection() {
        InMemoryLongTermMemoryStore store = new InMemoryLongTermMemoryStore();
        LongTermMemoryService enabled = new LongTermMemoryService(store, true, 50, 5, 500);
        enabled.remember(1L, "保留的用户自述", "FACT", "s");
        LongTermMemoryService disabled = new LongTermMemoryService(store, false, 50, 5, 500);
        String id = disabled.managementList(1L).items().get(0).id();
        assertFalse(disabled.managementList(1L).enabled());
        assertTrue(disabled.recall(1L, "用户自述", 5).isEmpty());
        org.junit.jupiter.api.Assertions.assertThrows(com.pony.knowledge.exception.BusinessException.class,
                () -> disabled.correct(1L, id, "纠正", "FACT"));
        org.junit.jupiter.api.Assertions.assertThrows(com.pony.knowledge.exception.BusinessException.class,
                () -> enabled.correct(1L, id, "长".repeat(501), "FACT"));
        disabled.delete(1L, id);
        assertTrue(enabled.list(1L).isEmpty());
    }

    /** 交错修正、删除与新增长期记忆，检查对单条记录的操作保留并发新增项。 */
    @Test
    void correctingAndDeletingAnItem_doesNotOverwriteConcurrentAppends() throws Exception {
        LongTermMemoryService svc = service(50, 5, true);
        svc.remember(1L, "待纠正", "FACT", "s");
        String correctedId = svc.list(1L).get(0).getId();
        svc.remember(1L, "待删除", "FACT", "s");
        String deletedId = svc.list(1L).get(0).getId();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            var writer = pool.submit(() -> { start.await(); for (int i = 0; i < 20; i++) svc.remember(1L, "新增" + i, "FACT", "s"); return null; });
            var manager = pool.submit(() -> { start.await(); svc.correct(1L, correctedId, "已纠正", "PREFERENCE"); svc.delete(1L, deletedId); return null; });
            start.countDown();
            writer.get(5, java.util.concurrent.TimeUnit.SECONDS);
            manager.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(21, svc.list(1L).size());
            assertEquals(20, svc.list(1L).stream().filter(item -> item.getContent().startsWith("新增")).count());
            assertTrue(svc.list(1L).stream().anyMatch(item -> item.getId().equals(correctedId) && item.getContent().equals("已纠正")));
            assertFalse(svc.list(1L).stream().anyMatch(item -> item.getId().equals(deletedId)));
        } finally { pool.shutdownNow(); }
    }

    /** 通过 {@code svc.remember}、{@code svc.list} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void remember_thenList_newestFirst() {
        LongTermMemoryService svc = service(50, 5, true);

        assertTrue(svc.remember(1L, "我在华东区", "FACT", "1:s1"));
        assertTrue(svc.remember(1L, "汇报用中文", "PREFERENCE", "1:s1"));

        List<LongTermMemoryItem> items = svc.list(1L);
        assertEquals(2, items.size());
        assertEquals("汇报用中文", items.get(0).getContent(), "最新在前");
        assertEquals("PREFERENCE", items.get(0).getKind());
        assertEquals("1:s1", items.get(0).getSourceSessionId(), "保留来源会话，便于追溯");
    }

    /** 通过 {@code svc.remember}、{@code svc.list} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void remember_deduplicatesExactContent() {
        LongTermMemoryService svc = service(50, 5, true);
        svc.remember(1L, "我在华东区", "FACT", "s1");

        assertFalse(svc.remember(1L, "  我在华东区  ", "FACT", "s2"), "重复内容不重复入库");
        assertEquals(1, svc.list(1L).size());
    }

    /** 通过 {@code service} 驱动该场景，布尔状态符合断言。 */
    @Test
    void remember_rejectsBlankAndDisabled() {
        assertFalse(service(50, 5, true).remember(1L, "   ", "FACT", "s1"));
        assertFalse(service(50, 5, false).remember(1L, "我在华东区", "FACT", "s1"),
                "enabled=false 时不写入");
        assertFalse(service(50, 5, true).remember(null, "我在华东区", "FACT", "s1"),
                "没有用户身份就不写（作用域缺失不能猜）");
    }

    /** 通过 {@code svc.remember}、{@code svc.list} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void capacity_evictsOldest() {
        LongTermMemoryService svc = service(3, 5, true);
        for (int i = 1; i <= 5; i++) {
            svc.remember(1L, "事实" + i, "FACT", "s1");
        }

        List<LongTermMemoryItem> items = svc.list(1L);
        assertEquals(3, items.size(), "超出 max-items 淘汰最旧的");
        assertEquals("事实5", items.get(0).getContent());
        assertEquals("事实3", items.get(2).getContent());
    }

    /** 通过 {@code svc.remember}、{@code svc.list} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void usersAreIsolated() {
        LongTermMemoryService svc = service(50, 5, true);
        svc.remember(1L, "用户一的偏好", "PREFERENCE", "s1");
        svc.remember(2L, "用户二的偏好", "PREFERENCE", "s2");

        assertEquals(1, svc.list(1L).size());
        assertEquals("用户一的偏好", svc.list(1L).get(0).getContent());
        assertEquals("用户二的偏好", svc.list(2L).get(0).getContent());
    }

    /** 通过 {@code svc.remember}、{@code svc.recall} 驱动用例，布尔状态符合断言，返回的数据、边界或顺序符合断言。 */
    @Test
    void recall_ranksRelevantFirst_andDropsUnrelated() {
        LongTermMemoryService svc = service(50, 5, true);
        svc.remember(1L, "汇报材料一律用中文", "PREFERENCE", "s1");
        svc.remember(1L, "我在华东区负责渠道", "FACT", "s1");

        List<LongTermMemoryItem> hits = svc.recall(1L, "汇报要用什么语言", 5);

        assertFalse(hits.isEmpty(), "相关条目应被召回");
        assertEquals("汇报材料一律用中文", hits.get(0).getContent(), "相关度最高的排第一");
        assertTrue(hits.size() < 2, "无关条目（华东区）不该被硬塞进来，实际=" + hits.size());
    }

    /** 通过 {@code svc.remember}、{@code svc.recall} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void recall_blankQuery_returnsNewest() {
        LongTermMemoryService svc = service(50, 5, true);
        svc.remember(1L, "第一条", "FACT", "s1");
        svc.remember(1L, "第二条", "FACT", "s1");

        List<LongTermMemoryItem> hits = svc.recall(1L, "  ", 5);

        assertEquals(2, hits.size());
        assertEquals("第二条", hits.get(0).getContent());
    }

    /** 通过 {@code svc.remember}、{@code svc.recall} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void recall_respectsLimit() {
        LongTermMemoryService svc = service(50, 2, true);
        for (int i = 1; i <= 5; i++) {
            svc.remember(1L, "差旅标准" + i, "FACT", "s1");
        }

        assertEquals(2, svc.recall(1L, "差旅标准", 2).size());
        assertEquals(2, svc.injectLimit());
    }

    /** 通过 {@code svc.remember}、{@code svc.list} 驱动用例，布尔状态符合断言。 */
    @Test
    void renderBlock_carriesConflictGuard() {
        LongTermMemoryService svc = service(50, 5, true);
        svc.remember(1L, "我在华东区", "FACT", "s1");

        String block = svc.renderBlock(svc.list(1L));

        assertTrue(block.contains("[FACT] 我在华东区"));
        assertTrue(block.contains("以知识库为准"), "必须写明冲突时以知识库为准：记忆是用户自述，可能过期");
    }
}
