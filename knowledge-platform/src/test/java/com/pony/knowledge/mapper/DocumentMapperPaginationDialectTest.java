/* 在 PostgreSQL 上核对文档查询的分页 SQL 和返回记录。 */

package com.pony.knowledge.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pony.knowledge.entity.Document;
import com.pony.knowledge.entity.DocumentStatus;
import com.pony.knowledge.entity.KnowledgeBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分页方言回归：offset > 0 时 MyBatis-Plus 必须生成 PostgreSQL 认的 LIMIT/OFFSET，
 * 而不是 MySQL 的 {@code LIMIT ?,?}。
 *
 * 为什么需要这个测试：单 PG 收口之前分页插件钉死 {@code DbType.MYSQL}（业务库当时确实是 MySQL），
 * 而 PG 不接受 {@code LIMIT ?,?} → <b>第 2 页起直接 SQL 语法错误</b>。既有的
 * {@code DocumentTaskMapperIntegrationTest} 只查 page=1（offset=0，生成的 {@code LIMIT ?}
 * 恰好 PG 也认），所以这个缺陷一直藏得住。故本测试<b>必须</b>用 page=2 才有效。
 *
 * 数据由 {@code @Transactional} 回滚清理，不留残留（与同目录既有集成测试同一约定）。
 */
@SpringBootTest(properties = "task.reliability.recovery-enabled=false")
@Transactional
class DocumentMapperPaginationDialectTest {

    private static final long USER_ID = 999_996L;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    /**
     * 向夹具写入记录，使用 {@code kb.setName}、{@code kb.setCreatorId}。
     * 由 {@code secondPageWithOffsetGeneratesPostgresCompatibleLimit} 调用。
     * @param count 本用例传入的{@code count}
     * 返回 {@code kb.getId()}。
     */
    private Long insertKbWithDocs(int count) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName("pagination-dialect-" + System.nanoTime());
        kb.setCreatorId(USER_ID);
        knowledgeBaseMapper.insert(kb);

        for (int i = 0; i < count; i++) {
            Document doc = new Document();
            doc.setKnowledgeBaseId(kb.getId());
            doc.setFileName("p" + i + ".pdf");
            doc.setFileType("application/pdf");
            doc.setFilePath("p" + i + ".pdf");
            doc.setFileSize(100L);
            doc.setUploaderId(USER_ID);
            doc.setStatus(DocumentStatus.UPLOADED);
            documentMapper.insert(doc);
        }
        return kb.getId();
    }

    /** 通过 {@code documentMapper.selectPageByKnowledgeBaseId} 执行本例并核对输出或记录。 */
    @Test
    void secondPageWithOffsetGeneratesPostgresCompatibleLimit() {
        Long kbId = insertKbWithDocs(3);

        Page<Document> firstPage = new Page<>(1, 2);
        var firstRecords = documentMapper.selectPageByKnowledgeBaseId(firstPage, kbId).getRecords();
        assertThat(firstRecords).hasSize(2);

        // offset = 2：MySQL 方言在这里生成 `LIMIT 2,2`，PG 报 syntax error at or near ","
        Page<Document> secondPage = new Page<>(2, 2);
        var secondRecords = documentMapper.selectPageByKnowledgeBaseId(secondPage, kbId).getRecords();

        // 3 条数据、每页 2 条 → 第 2 页只剩 1 条；且不可是第 1 页的重放
        assertThat(secondRecords).singleElement();
        Set<Long> firstIds = firstRecords.stream().map(Document::getId).collect(Collectors.toSet());
        assertThat(firstIds).doesNotContain(secondRecords.get(0).getId());
    }
}
