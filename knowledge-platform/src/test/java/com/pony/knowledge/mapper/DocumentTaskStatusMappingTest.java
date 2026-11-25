/* 核对文档任务的数据库状态编码与业务枚举。 */

package com.pony.knowledge.mapper;

import com.pony.knowledge.entity.Document;
import com.pony.knowledge.entity.DocumentStatus;
import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.entity.DocumentTaskStatus;
import com.pony.knowledge.entity.KnowledgeBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 换库后最易静默出错的一处：int 列 ↔ 枚举的桥。
 * MybatisEnumTypeHandler + map-underscore-to-camel-case 必须能正确往返，
 * 否则状态读出来是错的且不报错。
 *
 * 与 UserMapperIntegrationTest 同一条原则：@EnumValue 只对 MyBatis-Plus 自己生成的语句生效，
 * 手写 @Select 的 SELECT *（findActiveTasks / findStuckTasks，恢复与卡死扫描在读）走的是
 * MyBatis 自动映射，依赖 application.yml 的 default-enum-type-handler。两条路都要验。
 *
 * 已知局限：DocumentTaskStatus 四个常量的 code 与 ordinal 恰好一一相同（0/1/2/3），
 * 故本测试**无法**区分 @EnumValue 与按 ordinal 映射；它能证明的是「落库的是数字码而非枚举名」
 * 且两条读取路径都能映射回枚举。
 *
 * 变异验证（实测）：把 DocumentTaskStatus 的 @EnumValue 注释掉后重跑本类，3 个用例全挂，
 * 报错为 `column "status" is of type integer but expression is of type character varying`
 * —— 说明写入侧其实是「响的」（MP 回退成按枚举名写）。因此本类真正不可替代的价值在读回侧：
 * 手写 SELECT * 若没走 MybatisEnumTypeHandler，读回来是 null 或错值，不会报错。
 */
@SpringBootTest(properties = "task.reliability.recovery-enabled=false")
@Transactional
class DocumentTaskStatusMappingTest {

    @Autowired
    private DocumentTaskMapper documentTaskMapper;
    @Autowired private DocumentMapper documentMapper;
    @Autowired private KnowledgeBaseMapper knowledgeBaseMapper;
    private Long documentId;

    /** 每个用例前准备夹具，调用 {@code kb.setName}、{@code UUID.randomUUID}。 */
    @BeforeEach
    void createOwnedDocumentFixture() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName("task-status-" + UUID.randomUUID());
        kb.setCreatorId(999_996L);
        knowledgeBaseMapper.insert(kb);
        Document document = new Document();
        document.setKnowledgeBaseId(kb.getId());
        document.setFileName("task-status.pdf");
        document.setFilePath("task-status.pdf");
        document.setStatus(DocumentStatus.UPLOADED);
        documentMapper.insert(document);
        documentId = document.getId();
    }

    /** 通过 {@code task.setDocumentId} 执行本例并核对输出或记录。 */
    @Test
    void status_roundTripsAsEnumNotOrdinal() {
        DocumentTask task = new DocumentTask();
        task.setDocumentId(documentId); // V9 requires a real owning document.
        task.setStatus(DocumentTaskStatus.WAITING);
        task.setAutoRetryCount(0);
        task.setManualRetryCount(0);
        documentTaskMapper.insert(task);

        DocumentTask loaded = documentTaskMapper.selectById(task.getId());
        assertThat(loaded.getStatus()).isEqualTo(DocumentTaskStatus.WAITING);
        assertThat(loaded.getAutoRetryCount()).isZero();
        assertThat(loaded.getManualRetryCount()).isZero();

        documentTaskMapper.deleteById(task.getId());
    }

    /** 通过 {@code task.setDocumentId} 执行本例并核对输出或记录。 */
    @Test
    void status_roundTripsForNonZeroCode() {
        DocumentTask task = new DocumentTask();
        task.setDocumentId(documentId);
        task.setStatus(DocumentTaskStatus.FAILED);
        task.setAutoRetryCount(2);
        task.setManualRetryCount(1);
        documentTaskMapper.insert(task);

        DocumentTask loaded = documentTaskMapper.selectById(task.getId());
        assertThat(loaded.getStatus()).isEqualTo(DocumentTaskStatus.FAILED);
        assertThat(loaded.getStatus().getCode()).isEqualTo(DocumentTaskStatus.FAILED.getCode());
        assertThat(loaded.getAutoRetryCount()).isEqualTo(2);
        assertThat(loaded.getManualRetryCount()).isEqualTo(1);

        documentTaskMapper.deleteById(task.getId());
    }

    /** 通过 {@code task.setDocumentId} 执行本例并核对输出或记录。 */
    @Test
    void status_roundTripsThroughHandWrittenSelectStar() {
        DocumentTask task = new DocumentTask();
        task.setDocumentId(documentId);
        task.setStatus(DocumentTaskStatus.WAITING);
        task.setAutoRetryCount(0);
        task.setManualRetryCount(0);
        documentTaskMapper.insert(task);

        List<DocumentTask> active = documentTaskMapper.findRecoverableTasks(0L, 100);
        assertThat(active)
                .filteredOn(t -> task.getId().equals(t.getId()))
                .singleElement()
                .satisfies(t -> assertThat(t.getStatus()).isEqualTo(DocumentTaskStatus.WAITING));

        documentTaskMapper.deleteById(task.getId());
    }
}
