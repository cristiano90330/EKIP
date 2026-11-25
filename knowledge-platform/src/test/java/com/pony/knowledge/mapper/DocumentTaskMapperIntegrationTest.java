/* 验证数据库任务状态转换、租约认领、重试与恢复条件。 */

package com.pony.knowledge.mapper;

import com.pony.knowledge.entity.Document;
import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.entity.DocumentTaskStatus;
import com.pony.knowledge.entity.DocumentStatus;
import com.pony.knowledge.entity.KnowledgeBase;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "task.reliability.recovery-enabled=false")
@Transactional
class DocumentTaskMapperIntegrationTest {

    @Autowired
    private DocumentTaskMapper taskMapper;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 向夹具写入记录，使用 {@code kb.setName}、{@code kb.setCreatorId}。
     * 由 {@code claimThenSuccessTransition}、{@code claimFailedMarksFailedAndBlocksReclaim} 调用。
     * @param userId 账号 ID
     * 返回 {@code doc.getId()}。
     */
    private Long insertDoc(Long userId) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName("p2b-it-" + userId);
        kb.setCreatorId(userId);
        knowledgeBaseMapper.insert(kb);

        Document doc = new Document();
        doc.setKnowledgeBaseId(kb.getId());
        doc.setFileName("t.pdf");
        doc.setFileType("application/pdf");
        doc.setFilePath("t.pdf");
        doc.setFileSize(100L);
        doc.setUploaderId(userId);
        doc.setStatus(DocumentStatus.UPLOADED);
        documentMapper.insert(doc);
        return doc.getId();
    }

    /**
     * 向夹具写入记录，使用 {@code task.setDocumentId}、{@code task.setStatus}。
     * 由 {@code claimThenSuccessTransition}、{@code claimFailedMarksFailedAndBlocksReclaim} 调用。
     * @param docId 文档 ID
     * @param status 拟使用的状态值
     * @param autoRetryCount 本用例传入的{@code autoRetryCount}
     * 返回 {@code task}。
     */
    private DocumentTask insertTask(Long docId, DocumentTaskStatus status, int autoRetryCount) {
        DocumentTask task = new DocumentTask();
        task.setDocumentId(docId);
        task.setStatus(status);
        task.setAutoRetryCount(autoRetryCount);
        task.setManualRetryCount(0);
        taskMapper.insert(task);
        return task;
    }

    /** 通过 {@code taskMapper.selectById} 执行本例并核对输出或记录。 */
    @Test
    void claimThenSuccessTransition() {
        Long docId = insertDoc(999_991L);
        DocumentTask task = insertTask(docId, DocumentTaskStatus.WAITING, 0);

        assertThat(claim(task)).isEqualTo(1);

        DocumentTask claimed = taskMapper.selectById(task.getId());
        assertThat(claimed.getStatus()).isEqualTo(DocumentTaskStatus.PROCESSING);
        assertThat(claimed.getAutoRetryCount()).isEqualTo(1);   // 引擎认领 +1
        assertThat(claimed.getTotalAttemptCount()).isEqualTo(1);
        assertThat(claimed.getManualRetryCount()).isZero();
        assertThat(claimed.getStartTime()).isNotNull();

        assertThat(taskMapper.markSuccess(task.getId(), 1)).isEqualTo(1);
        DocumentTask done = taskMapper.selectById(task.getId());
        assertThat(done.getStatus()).isEqualTo(DocumentTaskStatus.SUCCESS);
        assertThat(done.getFinishTime()).isNotNull();

        // 终态不可再认领
        assertThat(claim(task)).isEqualTo(0);
    }

    /** 通过 {@code taskMapper.markFailed} 执行本例并核对输出或记录。 */
    @Test
    void claimFailedMarksFailedAndBlocksReclaim() {
        Long docId = insertDoc(999_992L);
        DocumentTask task = insertTask(docId, DocumentTaskStatus.WAITING, 0);

        claim(task);
        assertThat(taskMapper.markFailed(task.getId(), 1, "解析失败", "DOCUMENT_INVALID")).isEqualTo(1);
        DocumentTask failed = taskMapper.selectById(task.getId());
        assertThat(failed.getStatus()).isEqualTo(DocumentTaskStatus.FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo("解析失败");
        assertThat(claim(task)).isEqualTo(0);
    }

    /** 通过 {@code taskMapper.markFailed} 执行本例并核对输出或记录。 */
    @Test
    void markFailedOnlyAcceptsProcessing() {
        Long docId = insertDoc(999_993L);
        DocumentTask task = insertTask(docId, DocumentTaskStatus.SUCCESS, 0);
        assertThat(taskMapper.markFailed(task.getId(), 1, "x", "PROCESSING_FAILED")).isEqualTo(0);
    }

    /** 覆盖重试的相关边界。通过 {@code taskMapper.markFailed} 执行本例并核对输出或记录。 */
    @Test
    void cumulativeAttemptCountSurvivesManualRetryCycle() {
        Long docId = insertDoc(999_996L);
        DocumentTask task = insertTask(docId, DocumentTaskStatus.WAITING, 0);

        assertThat(claim(task)).isEqualTo(1);
        assertThat(taskMapper.markFailed(task.getId(), 1, "transient", "AI_UNAVAILABLE")).isEqualTo(1);
        assertThat(taskMapper.retryTask(task.getId())).isEqualTo(1);
        assertThat(claim(task)).isEqualTo(1);

        DocumentTask retried = taskMapper.selectById(task.getId());
        assertThat(retried.getAutoRetryCount()).isEqualTo(1); // per-cycle count resets
        assertThat(retried.getManualRetryCount()).isEqualTo(1);
        assertThat(retried.getTotalAttemptCount()).isEqualTo(2); // cumulative claims do not reset
    }

    /** 通过 {@code taskMapper.findRecoverableTasks} 执行本例并核对输出或记录。 */
    @Test
    void findActiveAndStuckQueries() {
        Long docId = insertDoc(999_994L);
        DocumentTask waiting = insertTask(docId, DocumentTaskStatus.WAITING, 0);
        DocumentTask processing = insertTask(docId, DocumentTaskStatus.PROCESSING, 0);
        DocumentTask done = insertTask(docId, DocumentTaskStatus.SUCCESS, 0);

        List<DocumentTask> active = taskMapper.findRecoverableTasks(0L, 100);
        assertThat(active.stream().map(DocumentTask::getId))
                .contains(waiting.getId(), processing.getId())
                .doesNotContain(done.getId());

        // 构造 10 分钟前的 PROCESSING 任务
        DocumentTask stuck = new DocumentTask();
        stuck.setDocumentId(docId);
        stuck.setStatus(DocumentTaskStatus.PROCESSING);
        stuck.setAutoRetryCount(0);
        stuck.setManualRetryCount(0);
        stuck.setStartTime(LocalDateTime.now().minus(Duration.ofMinutes(20)));
        taskMapper.insert(stuck);

        List<DocumentTask> stuckTasks = taskMapper.findRecoverableProcessingTasks(0L, 100);
        assertThat(stuckTasks.stream().map(DocumentTask::getId)).contains(stuck.getId());
    }

    /** 覆盖过期的相关边界。通过 {@code task.getId} 执行本例并核对输出或记录。 */
    @Test
    void activeLeaseSurvivesStartupAndExpiredAttemptCannotWriteOrRenew() {
        DocumentTask task = insertTask(insertDoc(999_990L), DocumentTaskStatus.WAITING, 0);
        long taskId = task.getId();
        assertThat(claim(task)).isEqualTo(1);
        assertThat(claim(task)).isZero();
        assertThat(taskMapper.findRecoverableTasks(taskId - 1, 1)).extracting(DocumentTask::getId).doesNotContain(taskId);
        assertThat(taskMapper.renewLease(taskId, 1, 900_000)).isEqualTo(1); // capped at the original deadline
        assertThat(jdbc.queryForObject("SELECT lease_expires_at=lease_deadline_at FROM document_task WHERE id=?",
                Boolean.class, taskId)).isTrue();

        jdbc.update("UPDATE document_task SET lease_expires_at=clock_timestamp()-INTERVAL '1 second' WHERE id=?", taskId);
        assertThat(taskMapper.renewLease(taskId, 1, 120_000)).isZero();
        assertThat(taskMapper.markSuccess(taskId, 1)).isZero();
        assertThat(taskMapper.findRecoverableProcessingTasks(taskId - 1, 1)).extracting(DocumentTask::getId).containsExactly(taskId);
        assertThat(claim(task)).isEqualTo(1);
        assertThat(taskMapper.markFailed(taskId, 1, "old worker", "AI_UNAVAILABLE")).isZero();
        assertThat(taskMapper.markSuccess(taskId, 2)).isEqualTo(1);
    }

    /** 覆盖重试的相关边界。通过 {@code task.getId} 执行本例并核对输出或记录。 */
    @Test
    void delayedRetryIsDurableAndLateRejectionCannotResetNewClaim() {
        DocumentTask task = insertTask(insertDoc(999_989L), DocumentTaskStatus.WAITING, 0);
        long taskId = task.getId();
        assertThat(claim(task)).isEqualTo(1);
        assertThat(taskMapper.recordRetryFailure(taskId, 1, 60_000, "retry", "AI_UNAVAILABLE")).isEqualTo(1);
        assertThat(taskMapper.findRecoverableTasks(taskId - 1, 1)).extracting(DocumentTask::getId).doesNotContain(taskId);
        assertThat(taskMapper.markWaitingAfterRetryScheduleFailure(taskId, 1, "queue full", "REJECTED")).isEqualTo(1);
        assertThat(taskMapper.findWaitingTasks(0, taskId - 1, 1)).extracting(DocumentTask::getId).doesNotContain(taskId);
        assertThat(claim(task)).isZero();

        jdbc.update("UPDATE document_task SET retry_not_before=clock_timestamp()-INTERVAL '1 second' WHERE id=?", taskId);
        assertThat(claim(task)).isEqualTo(1);
        assertThat(taskMapper.markWaitingAfterRetryScheduleFailure(taskId, 1, "late callback", "REJECTED")).isZero();
        assertThat(taskMapper.markCancelled(taskId)).isEqualTo(1);
        assertThat(taskMapper.markSuccess(taskId, 2)).isZero();
    }

    /**
     * 构造 {@code int} 供测试使用，使用 {@code taskMapper.claimProcessing}、{@code task.getId}。
     * 由 {@code claimThenSuccessTransition}、{@code claimFailedMarksFailedAndBlocksReclaim} 调用。
     * @param task 本用例传入的{@code task}
     * 返回 {@code taskMapper.claimProcessing(task.getId(), 120000, 600000)}。
     */
    private int claim(DocumentTask task) {
        return taskMapper.claimProcessing(task.getId(), 120_000, 600_000);
    }

    /**
     * Document.status 枚举化后，值必须仍然落在 int 列上，且三条读取路径都能映射回枚举：
     * BaseMapper 生成语句（列清单由 MP 按 @EnumValue 生成）与手写 @Select 的 SELECT d.*
     * （走 MyBatis 自动映射，依赖 default-enum-type-handler）行为要一致。
     */
    /** 覆盖文档的相关边界。通过 {@code documentMapper.selectById} 执行本例并核对输出或记录。 */
    @Test
    void documentStatusEnumRoundTripsThroughIntColumnOnAllQueryPaths() {
        Long userId = 999_995L;
        Long docId = insertDoc(userId);

        Document doc = documentMapper.selectById(docId);
        doc.setStatus(DocumentStatus.COMPLETED);
        documentMapper.updateById(doc);

        assertThat(documentMapper.selectById(docId).getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(documentMapper.selectOwnedDocument(docId, userId).getStatus())
                .isEqualTo(DocumentStatus.COMPLETED);

        // 注意这条查询按 knowledge_base_id 过滤，不是文档 id
        Page<Document> page = new Page<>(1, 10);
        assertThat(documentMapper.selectPageByKnowledgeBaseId(page, doc.getKnowledgeBaseId()).getRecords())
                .singleElement()
                .satisfies(record -> assertThat(record.getStatus()).isEqualTo(DocumentStatus.COMPLETED));
    }
}
