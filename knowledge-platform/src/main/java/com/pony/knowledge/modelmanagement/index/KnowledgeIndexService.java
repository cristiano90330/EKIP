/* 管理活动索引代际、向量空间和可恢复的影子索引重建任务。 */

package com.pony.knowledge.modelmanagement.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.modelmanagement.ModelRegistryService;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.ai.AiServiceClient;
import com.pony.knowledge.utils.CurrentUserUtil;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** Owns immutable vector spaces, active generations and recoverable shadow rebuilds. */
@Service
public class KnowledgeIndexService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexService.class);
    private static final String CHILD = "source_type IS DISTINCT FROM 'parent'";
    private static final int BATCH_SIZE = 16;
    private static final long LOCK_NAMESPACE = 0x4b42494e00000000L;
    private static final long LEASE_SECONDS = 180;
    private final JdbcTemplate jdbc;
    private final ModelRegistryService models;
    private final AiServiceClient ai;
    private final CurrentUserUtil users;
    private final KnowledgeBaseAccessService access;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final boolean workerEnabled;
    private final AtomicBoolean dispatched = new AtomicBoolean();
    private final AtomicBoolean requested = new AtomicBoolean();
    private final AtomicReference<RunningClaim> running = new AtomicReference<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "knowledge-index-rebuild");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    /**
     * 保存管理活动索引代际、向量空间和可恢复的影子索引重建任务所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param jdbc 负责数据库 SQL 的 JDBC 组件
     * @param models ModelRegistryService，负责管理模型档案、用途绑定、凭证与执行配置的解析
     * @param ai AiServiceClient，负责装配 Java 到 AI 服务的内部 HTTP 请求，承接解析、嵌入、问答与能力检查
     * @param users 本次操作使用的 CurrentUserUtil 组件或记录
     * @param access KnowledgeBaseAccessService，负责约定知识库可读范围和创建者操作的权限判定
     * @param mapper 操作本组件所需的数据记录的组件
     * @param manager 本次操作所用的 PlatformTransactionManager 数据
     * @param workerEnabled 是否启用相关处理
     */
    public KnowledgeIndexService(JdbcTemplate jdbc, ModelRegistryService models, AiServiceClient ai,
                                 CurrentUserUtil users, KnowledgeBaseAccessService access, ObjectMapper mapper,
                                 PlatformTransactionManager manager,
                                 @Value("${model-management.index.worker-enabled:true}") boolean workerEnabled) {
        this.jdbc = jdbc;
        this.models = models;
        this.ai = ai;
        this.users = users;
        this.access = access;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(manager);
        this.workerEnabled = workerEnabled;
    }

    /**
     * 在文档写事务中获取知识库索引屏障并检查维护可写状态。
     * 上游为 KnowledgeIndexService.assertWritable、KnowledgeIndexService.embeddingExecution。
     * 下游为 KnowledgeIndexService.requireTransaction、KnowledgeIndexService.lockKnowledgeBase。
     *
     * @param knowledgeBaseId 知识库 ID
     */
    public void acquireWriteBarrier(long knowledgeBaseId) {
        requireTransaction();
        lockKnowledgeBase(knowledgeBaseId);
        checkWritable(knowledgeBaseId);
    }

    /**
     * 单独执行一次知识库可写性检查，供准备外部对象上传的调用方使用。
     * 上游为 DocumentServiceImpl.upload。
     * 下游为 KnowledgeIndexService.acquireWriteBarrier。
     *
     * @param knowledgeBaseId 知识库 ID
     */
    public void assertWritable(long knowledgeBaseId) {
        transactions.executeWithoutResult(status -> acquireWriteBarrier(knowledgeBaseId));
    }

    /**
     * 按知识库 ID 取得事务级 PostgreSQL advisory lock。
     * 上游为 KnowledgeIndexService.acquireWriteBarrier、KnowledgeIndexService.cancelRebuild。
     *
     * @param knowledgeBaseId 知识库 ID
     */
    private void lockKnowledgeBase(long knowledgeBaseId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class,
                LOCK_NAMESPACE ^ knowledgeBaseId);
    }

    /**
     * 拒绝在索引维护期间执行与重建冲突的文档修改。
     * 上游为 KnowledgeIndexService.acquireWriteBarrier。
     * 下游为 IndexLifecycleException.conflict。
     *
     * @param knowledgeBaseId 知识库 ID
     */
    private void checkWritable(long knowledgeBaseId) {
        List<String> pending = jdbc.queryForList(
                "SELECT maintenance_task_id FROM knowledge_index_binding WHERE knowledge_base_id=?",
                String.class, knowledgeBaseId);
        if (!pending.isEmpty() && pending.get(0) != null) {
            throw IndexLifecycleException.conflict("知识库正在重建索引，暂时不能上传、删除或重新处理文档");
        }
    }

    /**
     * 在确认当前账号有读取权限后返回知识库索引状态。
     * 上游为 KnowledgeIndexController.status。
     * 下游为 KnowledgeIndexService.requireRead、KnowledgeIndexService.statusInternal。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 当前活动索引、向量维度和最近重建任务状态
     */
    public Map<String, Object> status(long knowledgeBaseId) {
        requireRead(knowledgeBaseId);
        return statusInternal(knowledgeBaseId);
    }

    /**
     * 编排活动、影子索引与重建任务的显示信息。
     * 上游为 KnowledgeIndexService.status。
     * 下游为 KnowledgeIndexService.active、KnowledgeIndexService.childCount。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 活动索引、向量维度、维护状态及任务信息
     */
    private Map<String, Object> statusInternal(long knowledgeBaseId) {
        Map<String, Object> active = active(knowledgeBaseId);
        List<Map<String, Object>> bindings = jdbc.queryForList(
                "SELECT maintenance_task_id FROM knowledge_index_binding WHERE knowledge_base_id=?", knowledgeBaseId);
        Object taskId = bindings.isEmpty() ? null : bindings.get(0).get("maintenance_task_id");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("knowledgeBaseId", knowledgeBaseId);
        result.put("status", active != null ? "ready" : childCount(knowledgeBaseId) > 0 ? "requires_rebuild" : "unconfigured");
        result.put("activeGenerationId", active == null ? null : active.get("generation_id"));
        result.put("spaceId", active == null ? null : active.get("space_id"));
        result.put("dimension", active == null ? null : active.get("dimension"));
        result.put("modelId", active == null ? null : active.get("model_id"));
        result.put("maintenance", taskId != null);
        if (taskId == null) {
            List<String> latest = jdbc.queryForList("SELECT id FROM knowledge_index_rebuild_task WHERE knowledge_base_id=? ORDER BY created_at DESC,id DESC LIMIT 1",
                    String.class, knowledgeBaseId);
            if (!latest.isEmpty()) taskId = latest.get(0);
        }
        if (taskId != null) result.put("task", taskInternal(knowledgeBaseId, taskId.toString()));
        return result;
    }

    /**
     * 把已授权知识库逐个解析为当前代际、维度和模型组成的检索范围。
     * 上游为 PythonRagPipeline.buildRequest。
     * 下游为 IndexLifecycleException.unavailable。
     *
     * @param authorizedKnowledgeBaseIds 相关记录的 ID 列表
     * @return 与过滤条件匹配的 List&lt;Map&lt;String, Object&gt;&gt; 数据集合
     */
    public List<Map<String, Object>> resolveIndexScopes(List<Long> authorizedKnowledgeBaseIds) {
        if (authorizedKnowledgeBaseIds == null || authorizedKnowledgeBaseIds.isEmpty()) return List.of();
        List<Map<String, Object>> scopes = new ArrayList<>();

        for (Long kb : authorizedKnowledgeBaseIds.stream().distinct().sorted().toList()) {
            Map<String, Object> active = active(kb);
            if (active == null) throw IndexLifecycleException.unavailable("requires_rebuild",
                    "知识库没有已验证的活动向量索引，请先配置模型并重建索引");
            scopes.add(scope(kb, active));
        }
        return scopes;
    }

    /**
     * 为文档写入解析索引代际和模型执行快照。
     * 上游为 DocumentChunkServiceImpl.replaceChunks、DocumentChunkServiceImpl.completeTask。
     * 下游为 IndexLifecycleException.unavailable、ModelRegistryService.boundModelId。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param budgetMs 本次调用的可用预算（毫秒）
     * @return 写入时使用的模型绑定、预算、活动代际和向量范围
     */
    public Map<String, Object> embeddingExecution(long knowledgeBaseId, int budgetMs) {
        Map<String, Object> current = transactions.execute(status -> {
            acquireWriteBarrier(knowledgeBaseId);
            Map<String, Object> found = active(knowledgeBaseId);
            if (found != null) return found;
            if (childCount(knowledgeBaseId) != 0) throw IndexLifecycleException.unavailable("requires_rebuild",
                    "已有文档向量来源尚未确认，请先显式重建知识库索引");
            long owner = owner(knowledgeBaseId);
            String modelId = models.boundModelId(owner, "embedding");
            if (modelId == null) modelId = models.deploymentDefaultModelId("embedding");
            if (modelId == null) throw IndexLifecycleException.unavailable("model_not_configured", "部署尚未提供默认向量模型，请联系管理员");
            Space space = ensureSpace(owner, modelId);
            String generation = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO knowledge_index_generation(id,knowledge_base_id,space_id,state,activated_at) VALUES(?,?,?,'active',now())",
                    generation, knowledgeBaseId, space.id());
            jdbc.update("INSERT INTO knowledge_index_binding(knowledge_base_id,active_generation_id) VALUES(?,?) "
                            + "ON CONFLICT(knowledge_base_id) DO UPDATE SET active_generation_id=excluded.active_generation_id,updated_at=now()",
                    knowledgeBaseId, generation);
            return active(knowledgeBaseId);
        });
        Map<String, Object> scope = scope(knowledgeBaseId, current);
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("schemaVersion", 1);
        execution.put("budgetMs", budgetMs);
        execution.put("bindings", Map.of("embedding", scope.get("embeddingModel")));
        execution.put("indexScopes", List.of(scope));
        return execution;
    }

    /**
     * 验证执行快照所属索引仍活动，再将文档向量写入正确维度的分表。
     * 上游为 DocumentChunkServiceImpl.writeChunks。
     * 下游为 IndexLifecycleException.conflict。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param execution 含当前操作所需属性的映射
     * @param chunkIds 切片 ID 列表
     * @param vectors 与切片次序对齐的嵌入向量
     */
    public void writeDocumentVectors(long knowledgeBaseId, Map<String, Object> execution,
                                     List<Long> chunkIds, List<List<Double>> vectors) {
        requireTransaction();
        acquireWriteBarrier(knowledgeBaseId);
        @SuppressWarnings("unchecked") List<Map<String, Object>> scopes = (List<Map<String, Object>>) execution.get("indexScopes");
        if (scopes == null || scopes.size() != 1) throw new IllegalArgumentException("one ingestion index scope is required");
        Map<String, Object> scope = scopes.get(0);
        String generation = String.valueOf(scope.get("generationId"));

        // 当前写操作必须继续使用其取得的代际和向量空间，避免把已算向量混入新索引。
        Map<String, Object> current = active(knowledgeBaseId);
        if (current == null || !generation.equals(current.get("generation_id")))
            throw IndexLifecycleException.conflict("活动索引已变化，请重新处理文档");
        int dimension = ((Number) scope.get("dimension")).intValue();

        if (!Objects.equals(scope.get("spaceId"), current.get("space_id"))
                || dimension != ((Number) current.get("dimension")).intValue()
                || !(scope.get("knowledgeBaseId") instanceof Number kb) || kb.longValue() != knowledgeBaseId)
            throw IndexLifecycleException.conflict("向量写入范围与当前知识库索引不一致");
        validateVectors(vectors, chunkIds.size(), dimension);
        insertVectors(knowledgeBaseId, generation, dimension, chunkIds, vectors);
    }

    /**
     * 检查知识库和目标模型，建立影子代际及重建任务并安排工作。
     * 上游为 KnowledgeIndexController.rebuild。
     * 下游为 IndexLifecycleException.conflict。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param modelId 模型档案 ID
     * @param expectedActiveGenerationId 要定位的关联标识
     * @return 本次建立的重建任务的 ID 和初始状态
     */
    public Map<String, Object> startRebuild(long knowledgeBaseId, String modelId, String expectedActiveGenerationId) {
        requireOwner(knowledgeBaseId);
        Map<String, Object> result = transactions.execute(status -> {
            acquireWriteBarrier(knowledgeBaseId);
            Integer tasks = jdbc.queryForObject("SELECT count(*) FROM document_task t JOIN document d ON d.id=t.document_id "
                    + "WHERE d.knowledge_base_id=? AND t.status IN (0,1)", Integer.class, knowledgeBaseId);
            if (tasks != null && tasks > 0) throw IndexLifecycleException.conflict("请先等待该知识库的文档处理任务结束，再重建索引");
            Map<String, Object> current = active(knowledgeBaseId);
            String previous = current == null ? null : current.get("generation_id").toString();
            if (!Objects.equals(expectedActiveGenerationId, previous))
                throw IndexLifecycleException.conflict("活动索引已变化，请刷新后重新发起重建");
            Space space = ensureSpace(owner(knowledgeBaseId), modelId);
            String generation = UUID.randomUUID().toString(), task = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO knowledge_index_generation(id,knowledge_base_id,space_id,state) VALUES(?,?,?,'building')",
                    generation, knowledgeBaseId, space.id());
            jdbc.update("INSERT INTO knowledge_index_rebuild_task(id,knowledge_base_id,generation_id,previous_generation_id,status,total_chunks,source_fingerprint) "
                            + "VALUES(?,?,?,?,'waiting',?,?)", task, knowledgeBaseId, generation, previous,
                    childCount(knowledgeBaseId), sourceFingerprint(knowledgeBaseId));
            jdbc.update("INSERT INTO knowledge_index_binding(knowledge_base_id,active_generation_id,maintenance_task_id) VALUES(?,?,?) "
                            + "ON CONFLICT(knowledge_base_id) DO UPDATE SET maintenance_task_id=excluded.maintenance_task_id,updated_at=now()",
                    knowledgeBaseId, previous, task);
            return taskInternal(knowledgeBaseId, task);
        });
        schedule();
        return result;
    }

    /**
     * 校验知识库访问权后返回索引重建任务状态。
     * 上游为 KnowledgeIndexController.task。
     * 下游为 KnowledgeIndexService.requireRead、KnowledgeIndexService.taskInternal。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param taskId 索引重建任务 ID
     * @return 指定重建任务的进度、代际和执行结果
     */
    public Map<String, Object> taskStatus(long knowledgeBaseId, String taskId) {
        requireRead(knowledgeBaseId);
        return taskInternal(knowledgeBaseId, taskId);
    }

    /**
     * 按知识库归属和任务状态取消重建并释放维护标记。
     * 上游为 KnowledgeIndexController.cancel。
     * 下游为 KnowledgeIndexService.requireOwner、KnowledgeIndexService.lockKnowledgeBase。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param taskId 索引重建任务 ID
     * @return 取消操作后重新读取的任务状态
     */
    public Map<String, Object> cancelRebuild(long knowledgeBaseId, String taskId) {
        requireOwner(knowledgeBaseId);
        Map<String, Object> result = transactions.execute(status -> {
            lockKnowledgeBase(knowledgeBaseId);
            Map<String, Object> task = taskForUpdate(knowledgeBaseId, taskId);
            if (Set.of("waiting", "processing").contains(task.get("status"))) {
                finishTask(task, "cancelled", "index_rebuild_cancelled", "索引重建已取消");
            }
            return taskInternal(knowledgeBaseId, taskId);
        });
        // Fence persisted writes first. Interrupt only this claim's worker after commit,
        // allowing an interruptible HTTP transport to release its in-flight request.
        synchronized (running) {
            RunningClaim active = running.get();
            if ("cancelled".equals(result.get("status")) && active != null && active.claim().task().equals(taskId))
                active.thread().interrupt();
        }
        return result;
    }

    /**
     * 在服务就绪后检查需要继续处理的索引重建。
     * 上游为 Spring 容器生命周期或应用事件。
     * 下游为 KnowledgeIndexService.schedule。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverAtStartup() { schedule(); }

    /**
     * 协调当前实例的索引任务处理请求并避免重复投递。
     * 上游为 应用的定时调度器。
     * 下游为 KnowledgeIndexService.claimNext、KnowledgeIndexService.runClaim。
     */
    @Scheduled(fixedDelayString = "${model-management.index.recovery-interval-ms:10000}")
    public void schedule() {
        if (!workerEnabled || worker.isShutdown()) return;
        requested.set(true);
        if (!dispatched.compareAndSet(false, true)) return;
        requested.set(false);

        try {
            worker.execute(() -> {
                try {
                    Claim claim;
                    while (!Thread.currentThread().isInterrupted() && (claim = claimNext()) != null) runClaim(claim);
                } catch (RuntimeException failure) {
                    log.warn("索引任务调度暂时不可用 type={}", failure.getClass().getSimpleName());
                } finally {
                    dispatched.set(false);
                    // A committed task can arrive just after claimNext sees an empty queue.
                    // Preserve that wake-up instead of waiting for the recovery scan.
                    if (requested.getAndSet(false)) schedule();
                }
            });
        } catch (RuntimeException rejected) { dispatched.set(false); }
    }

    /**
     * 查询并认领具备执行条件的索引重建任务。
     * 上游为 KnowledgeIndexService.schedule。
     *
     * @return 含独立执行令牌的已认领任务，暂无候选时为空
     */
    private Claim claimNext() {
        return transactions.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM knowledge_index_rebuild_task WHERE status='waiting' "
                    + "OR (status='processing' AND lease_until < now()) ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED");
            if (rows.isEmpty()) return null;
            Map<String, Object> row = rows.get(0);
            String token = UUID.randomUUID().toString();
            jdbc.update("UPDATE knowledge_index_rebuild_task SET status='processing',run_token=?,lease_until=now()+(? * interval '1 second'),updated_at=now() WHERE id=?",
                    token, LEASE_SECONDS, row.get("id"));
            return new Claim(row.get("id").toString(), ((Number) row.get("knowledge_base_id")).longValue(),
                    row.get("generation_id").toString(), token);
        });
    }

    /**
     * 按批次为影子代际生成向量，持续核对重建租约和输入完整性。
     * 上游为 KnowledgeIndexService.schedule。
     * 下游为 Claim.generation、Claim.kb。
     *
     * @param claim 包含任务和执行令牌的重建认领结果
     */
    private void runClaim(Claim claim) {
        RunningClaim active = new RunningClaim(claim, Thread.currentThread());
        synchronized (running) { running.set(active); }
        try {
            Map<String, Object> generation = generation(claim.generation());
            Map<String, Object> model = resolveSpaceModel(generation);
            int dimension = ((Number) generation.get("dimension")).intValue();
            Map<String, Object> execution = Map.of("schemaVersion", 1, "budgetMs", 120000,
                    "bindings", Map.of("embedding", model), "indexScopes", List.of());

            // 逐批处理稳定的源切片范围，向量写入与游标更新共同推进恢复位置。
            while (!Thread.currentThread().isInterrupted()) {
                List<Chunk> batch = transactions.execute(status -> {
                    lockKnowledgeBase(claim.kb());
                    Map<String, Object> task = liveTask(claim);
                    long last = ((Number) task.get("last_chunk_id")).longValue();
                    renew(claim);
                    return jdbc.query("SELECT id,content FROM document_chunk WHERE knowledge_base_id=? AND " + CHILD
                                    + " AND id>? ORDER BY id LIMIT ?", (rs, n) -> new Chunk(rs.getLong(1), rs.getString(2)),
                            claim.kb(), last, BATCH_SIZE);
                });
                if (batch == null || batch.isEmpty()) { activate(claim, dimension); return; }
                if (Thread.currentThread().isInterrupted()) return;
                List<List<Double>> vectors = ai.embed(batch.stream().map(Chunk::content).toList(), execution).getEmbeddings();
                validateVectors(vectors, batch.size(), dimension);
                transactions.executeWithoutResult(status -> {
                    lockKnowledgeBase(claim.kb());
                    liveTask(claim);
                    insertVectors(claim.kb(), claim.generation(), dimension, batch.stream().map(Chunk::id).toList(), vectors);
                    jdbc.update("UPDATE knowledge_index_rebuild_task SET completed_chunks=completed_chunks+?,last_chunk_id=?,"
                                    + "lease_until=now()+(? * interval '1 second'),updated_at=now() WHERE id=? AND run_token=? AND status='processing'",
                            batch.size(), batch.get(batch.size() - 1).id(), LEASE_SECONDS, claim.task(), claim.token());
                });
            }
            // An interrupted process leaves its claim recoverable after the lease expires.
        } catch (Stopped ignored) {
            // Cancellation or a newer lease fences the late network result.
        } catch (RuntimeException error) {
            if (Thread.currentThread().isInterrupted()) return;
            failClaim(claim, "index_rebuild_failed", "索引重建失败，旧活动索引保持不变，请检查模型配置后重试");
            log.warn("索引重建失败 taskId={} type={}", claim.task(), error.getClass().getSimpleName());
        } finally {
            synchronized (running) { running.compareAndSet(active, null); }
        }
    }

    /**
     * 锁定知识库后再次验证来源及代际状态，将完成的影子代际切换为活动版本。
     * 上游为 KnowledgeIndexService.runClaim。
     * 下游为 Claim.kb、Claim.generation。
     *
     * @param claim 包含任务和执行令牌的重建认领结果
     * @param dimension 向量空间的维度
     */
    private void activate(Claim claim, int dimension) {
        transactions.executeWithoutResult(status -> {
            lockKnowledgeBase(claim.kb());
            Map<String, Object> task = liveTask(claim);
            long expected = ((Number) task.get("total_chunks")).longValue();
            Long actual = jdbc.queryForObject("SELECT count(*) FROM " + table(dimension) + " WHERE generation_id=? AND knowledge_base_id=?",
                    Long.class, claim.generation(), claim.kb());
            if (actual == null || actual != expected || childCount(claim.kb()) != expected
                    || !sourceFingerprint(claim.kb()).equals(task.get("source_fingerprint")))
                throw new IllegalStateException("index source validation failed");
            Long missing = jdbc.queryForObject("SELECT count(*) FROM document_chunk c WHERE c.knowledge_base_id=? AND c." + CHILD
                            + " AND NOT EXISTS(SELECT 1 FROM " + table(dimension) + " v WHERE v.generation_id=? AND v.chunk_id=c.id AND v.knowledge_base_id=c.knowledge_base_id)",
                    Long.class, claim.kb(), claim.generation());
            if (missing == null || missing != 0) throw new IllegalStateException("index missing chunks");
            Object previous = task.get("previous_generation_id");
            int changed = jdbc.update("UPDATE knowledge_index_binding SET active_generation_id=?,maintenance_task_id=NULL,updated_at=now() "
                            + "WHERE knowledge_base_id=? AND maintenance_task_id=? AND active_generation_id IS NOT DISTINCT FROM ?",
                    claim.generation(), claim.kb(), claim.task(), previous);
            if (changed != 1) throw new Stopped();
            if (previous != null) jdbc.update("UPDATE knowledge_index_generation SET state='retired' WHERE id=? AND state='active'", previous);
            jdbc.update("UPDATE knowledge_index_generation SET state='active',activated_at=now() WHERE id=? AND state='building'", claim.generation());

            // 切换活动代际和写入任务成功必须处于同一事务，使读路径始终看到一致状态。
            jdbc.update("UPDATE knowledge_index_rebuild_task SET status='completed',completed_chunks=total_chunks,run_token=NULL,lease_until=NULL,"
                    + "finished_at=now(),updated_at=now() WHERE id=? AND run_token=?", claim.task(), claim.token());
        });
    }

    /**
     * 标记重建尝试的失败原因并结束仍由本次持有的任务。
     * 上游为 KnowledgeIndexService.runClaim。
     * 下游为 Claim.kb。
     *
     * @param claim 包含任务和执行令牌的重建认领结果
     * @param code 持久化或对外公开的状态码
     * @param message 用于构建结果或异常的说明文字
     */
    private void failClaim(Claim claim, String code, String message) {
        try {
            transactions.executeWithoutResult(status -> {
                lockKnowledgeBase(claim.kb());
                Map<String, Object> task = liveTask(claim);
                finishTask(task, "failed", code, message);
            });
        } catch (Stopped ignored) { }
    }

    /**
     * 完成重建任务并按结果更新知识库维护状态。
     * 上游为 KnowledgeIndexService.cancelRebuild、KnowledgeIndexService.failClaim。
     *
     * @param task 任务记录
     * @param state 本方法计算或判断使用的 state 值
     * @param code 持久化或对外公开的状态码
     * @param message 用于构建结果或异常的说明文字
     */
    private void finishTask(Map<String, Object> task, String state, String code, String message) {
        jdbc.update("UPDATE knowledge_index_rebuild_task SET status=?,run_token=NULL,lease_until=NULL,error_code=?,error_message=?,finished_at=now(),updated_at=now() WHERE id=?",
                state, code, message, task.get("id"));
        jdbc.update("UPDATE knowledge_index_generation SET state=? WHERE id=? AND state='building'", state, task.get("generation_id"));
        jdbc.update("UPDATE knowledge_index_binding SET maintenance_task_id=NULL,updated_at=now() WHERE knowledge_base_id=? AND maintenance_task_id=?",
                task.get("knowledge_base_id"), task.get("id"));
    }

    /**
     * 查找当前仍有有效执行权的重建任务。
     * 上游为 KnowledgeIndexService.runClaim、KnowledgeIndexService.activate。
     * 下游为 Claim.kb、Claim.task。
     *
     * @param claim 包含任务和执行令牌的重建认领结果
     * @return 仍由本次执行令牌持有的重建记录
     */
    private Map<String, Object> liveTask(Claim claim) {
        Map<String, Object> task = taskForUpdate(claim.kb(), claim.task());
        if (!"processing".equals(task.get("status")) || !claim.token().equals(task.get("run_token"))) throw new Stopped();
        List<String> activeTasks = jdbc.queryForList("SELECT maintenance_task_id FROM knowledge_index_binding WHERE knowledge_base_id=?",
                String.class, claim.kb());
        if (activeTasks.isEmpty() || !claim.task().equals(activeTasks.get(0))) throw new Stopped();
        return task;
    }

    /**
     * 续期指定重建任务的处理租约。
     * 上游为 KnowledgeIndexService.runClaim。
     * 下游为 Claim.task、Claim.token。
     *
     * @param claim 包含任务和执行令牌的重建认领结果
     */
    private void renew(Claim claim) {
        jdbc.update("UPDATE knowledge_index_rebuild_task SET lease_until=now()+(? * interval '1 second'),updated_at=now() WHERE id=? AND run_token=?",
                LEASE_SECONDS, claim.task(), claim.token());
    }

    /**
     * 在事务中读取并锁定指定索引任务。
     * 上游为 KnowledgeIndexService.cancelRebuild、KnowledgeIndexService.liveTask。
     * 下游为 IndexLifecycleException.unavailable。
     *
     * @param kb 目标知识库 ID
     * @param id 要处理的记录 ID
     * @return 由本方法收集或转换的键值映射
     */
    private Map<String, Object> taskForUpdate(long kb, String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM knowledge_index_rebuild_task WHERE id=? AND knowledge_base_id=? FOR UPDATE", id, kb);
        if (rows.isEmpty()) throw IndexLifecycleException.unavailable("index_task_not_found", "索引重建任务不存在");
        return rows.get(0);
    }

    /**
     * 读取指定知识库的索引任务并整理出接口字段。
     * 上游为 KnowledgeIndexService.statusInternal、KnowledgeIndexService.startRebuild。
     *
     * @param kb 目标知识库 ID
     * @param id 要处理的记录 ID
     * @return 由本方法收集或转换的键值映射
     */
    private Map<String, Object> taskInternal(long kb, String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,knowledge_base_id,generation_id,status,total_chunks,completed_chunks,error_code,error_message,created_at,updated_at,finished_at "
                + "FROM knowledge_index_rebuild_task WHERE id=? AND knowledge_base_id=?", id, kb);
        if (rows.isEmpty()) throw new IndexLifecycleException(org.springframework.http.HttpStatus.NOT_FOUND, "index_task_not_found", "索引重建任务不存在");
        Map<String, Object> row = rows.get(0), view = new LinkedHashMap<>();
        view.put("id", row.get("id")); view.put("knowledgeBaseId", row.get("knowledge_base_id"));
        view.put("generationId", row.get("generation_id")); view.put("status", row.get("status"));
        view.put("totalChunks", row.get("total_chunks")); view.put("processedChunks", row.get("completed_chunks"));
        view.put("errorCode", row.get("error_code")); view.put("errorMessage", row.get("error_message"));

        for (String key : List.of("created_at", "updated_at", "finished_at")) {
            Object value = row.get(key);
            view.put(switch (key) { case "created_at" -> "createdAt"; case "updated_at" -> "updatedAt"; default -> "finishedAt"; },
                    value instanceof Timestamp timestamp ? timestamp.toLocalDateTime() : value);
        }
        return view;
    }

    /**
     * 读取指定知识库绑定的当前活动代际和向量空间。
     * 上游为 KnowledgeIndexService.statusInternal、KnowledgeIndexService.resolveIndexScopes。
     *
     * @param kb 目标知识库 ID
     * @return 由本方法收集或转换的键值映射
     */
    private Map<String, Object> active(long kb) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT g.id AS generation_id,g.space_id,s.owner_id,s.model_id,s.semantic_fingerprint,s.dimension "
                + "FROM knowledge_index_binding b JOIN knowledge_index_generation g ON g.id=b.active_generation_id AND g.knowledge_base_id=b.knowledge_base_id "
                + "JOIN embedding_space s ON s.id=g.space_id WHERE b.knowledge_base_id=? AND g.state='active'", kb);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 按代际标识查询对应索引代际及其向量空间信息。
     * 上游为 KnowledgeIndexService.runClaim。
     *
     * @param id 要处理的记录 ID
     * @return 由本方法收集或转换的键值映射
     */
    private Map<String, Object> generation(String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT g.id AS generation_id,g.space_id,s.owner_id,s.model_id,s.semantic_fingerprint,s.dimension "
                + "FROM knowledge_index_generation g JOIN embedding_space s ON s.id=g.space_id WHERE g.id=?", id);
        if (rows.isEmpty()) throw new Stopped();
        return rows.get(0);
    }

    /**
     * 把知识库的活动代际及向量空间记录转换为 AI 检索范围。
     * 上游为 KnowledgeIndexService.resolveIndexScopes、KnowledgeIndexService.embeddingExecution。
     * 下游为 KnowledgeIndexService.resolveSpaceModel。
     *
     * @param kb 目标知识库 ID
     * @param active 含当前操作所需属性的映射
     * @return 知识库、活动代际、向量维度和模型配置组成的执行范围
     */
    private Map<String, Object> scope(long kb, Map<String, Object> active) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("knowledgeBaseId", kb); result.put("generationId", active.get("generation_id"));
        result.put("spaceId", active.get("space_id")); result.put("dimension", active.get("dimension"));
        result.put("embeddingModel", resolveSpaceModel(active));
        return result;
    }

    /**
     * 根据持久化的空间快照核对模型的一致性。
     * 上游为 KnowledgeIndexService.runClaim、KnowledgeIndexService.scope。
     * 下游为 ModelRegistryService.resolveForPurpose、IndexLifecycleException.unavailable。
     *
     * @param space 含当前操作所需属性的映射
     * @return 由本方法收集或转换的键值映射
     */
    private Map<String, Object> resolveSpaceModel(Map<String, Object> space) {
        Map<String, Object> model = models.resolveForPurpose(((Number) space.get("owner_id")).longValue(),
                "embedding", space.get("model_id").toString());
        if (!Objects.equals(space.get("semantic_fingerprint"), model.get("semanticFingerprint")))
            throw IndexLifecycleException.unavailable("index_model_changed", "索引模型语义已变化，请重建知识库索引");
        return model;
    }

    /**
     * 查找或建立同一模型语义和维度对应的向量空间。
     * 上游为 KnowledgeIndexService.embeddingExecution、KnowledgeIndexService.startRebuild。
     * 下游为 ModelRegistryService.isDeploymentDefaultModel、ModelRegistryService.resolveForPurpose。
     *
     * @param owner 指明数据归属的账号 ID
     * @param modelId 模型档案 ID
     * @return 与目标模型和向量维度对应的空间 ID 及配置
     */
    private Space ensureSpace(long owner, String modelId) {
        Map<String, Object> model;

        if (models.isDeploymentDefaultModel("embedding", modelId)) {
            model = models.resolveForPurpose(owner, "embedding", modelId);
        } else {
            List<String> locked = jdbc.queryForList("SELECT id FROM managed_model WHERE id=? AND owner_id=? FOR UPDATE", String.class, modelId, owner);
            if (locked.isEmpty()) throw new IndexLifecycleException(org.springframework.http.HttpStatus.NOT_FOUND,
                    "model_not_found", "模型不存在或无权使用");
            model = models.resolveOwned(owner, modelId);
        }
        if (!"embedding".equals(model.get("type")) || !(model.get("dimension") instanceof Number n))
            throw new IndexLifecycleException(org.springframework.http.HttpStatus.BAD_REQUEST, "index_model_invalid", "请选择明确维度的向量模型");
        int dimension = n.intValue(); table(dimension);
        String fingerprint = Objects.toString(model.get("semanticFingerprint"), "");
        if (fingerprint.isBlank()) throw IndexLifecycleException.unavailable("index_model_invalid", "向量模型缺少语义指纹");
        String id = sha(owner + ":" + modelId + ":" + fingerprint);
        Map<String, Object> safe = new LinkedHashMap<>(model); safe.remove("apiKey");

        try {
            jdbc.update("INSERT INTO embedding_space(id,owner_id,model_id,semantic_fingerprint,dimension,config_snapshot) VALUES(?,?,?,?,?,?::jsonb) ON CONFLICT(id) DO NOTHING",
                    id, owner, modelId, fingerprint, dimension, mapper.writeValueAsString(safe));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("cannot serialize non-secret model snapshot");
        }
        return new Space(id, dimension);
    }

    /**
     * 把对应切片 ID、代际与向量批量写入受支持的维度表。
     * 上游为 KnowledgeIndexService.writeDocumentVectors、KnowledgeIndexService.runClaim。
     * 下游为 KnowledgeIndexService.table、KnowledgeIndexService.vectorLiteral。
     *
     * @param kb 目标知识库 ID
     * @param generation 本方法计算或判断使用的 generation 值
     * @param dimension 向量空间的维度
     * @param ids 需要本方法按序读取或筛选的 List&lt;Long&gt; 数据
     * @param vectors 与切片次序对齐的嵌入向量
     */
    private void insertVectors(long kb, String generation, int dimension, List<Long> ids, List<List<Double>> vectors) {
        String sql = "INSERT INTO " + table(dimension) + "(generation_id,chunk_id,knowledge_base_id,embedding) VALUES(?,?,?,?::halfvec) "
                + "ON CONFLICT(generation_id,chunk_id) DO UPDATE SET embedding=excluded.embedding";
        for (int i = 0; i < ids.size(); i++) jdbc.update(sql, generation, ids.get(i), kb, vectorLiteral(vectors.get(i)));
    }

    /**
     * 根据向量维度选择允许写入的固定 PostgreSQL 表名。
     * 上游为 KnowledgeIndexService.activate、KnowledgeIndexService.ensureSpace。
     *
     * @param dimension 向量空间的维度
     * @return 受支持的向量维度对应的现有表名
     */
    public static String table(int dimension) {
        return switch (dimension) {
            case 1024 -> "document_chunk_vector_1024";
            case 2560 -> "document_chunk_vector_2560";
            default -> throw new IndexLifecycleException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "index_dimension_unsupported", "当前索引支持 1024 或 2560 维向量模型");
        };
    }

    /**
     * 检查向量数量、维度与写入所需的数值约束。
     * 上游为 KnowledgeIndexService.writeDocumentVectors、KnowledgeIndexService.runClaim。
     * 下游为 KnowledgeIndexService.table。
     *
     * @param vectors 与切片次序对齐的嵌入向量
     * @param count 需要处理或记录的项数
     * @param dimension 向量空间的维度
     */
    public static void validateVectors(List<List<Double>> vectors, int count, int dimension) {
        table(dimension);
        if (vectors == null || vectors.size() != count) throw new IllegalStateException("embedding count mismatch");
        for (List<Double> vector : vectors) {
            if (vector == null || vector.size() != dimension) throw new IllegalStateException("embedding dimension mismatch");
            boolean nonzero = false;
            for (Double value : vector) {
                if (value == null || !Double.isFinite(value) || Math.abs(value) > 65504)
                    throw new IllegalStateException("invalid half precision embedding value");
                nonzero |= Math.abs(value) >= 5.960464477539063e-8;
            }
            if (!nonzero) throw new IllegalStateException("zero embedding cannot be indexed");
        }
    }

    /**
     * 把数值序列转换成 PostgreSQL 向量字面值。
     * 上游为 KnowledgeIndexService.insertVectors。
     *
     * @param vector 需要本方法按序读取或筛选的 List&lt;Double&gt; 数据
     * @return 可直接交给向量列参数转换的文本
     */
    private static String vectorLiteral(List<Double> vector) {
        return vector.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }

    /**
     * 统计知识库当前可用于索引的子切片数量。
     * 上游为 KnowledgeIndexService.statusInternal、KnowledgeIndexService.embeddingExecution。
     *
     * @param kb 目标知识库 ID
     * @return 目标知识库内的子切片数量
     */
    private long childCount(long kb) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM document_chunk WHERE knowledge_base_id=? AND " + CHILD, Long.class, kb);
        return count == null ? 0 : count;
    }

    /**
     * 计算重建期间可用于检测源切片改动的指纹。
     * 上游为 KnowledgeIndexService.startRebuild、KnowledgeIndexService.activate。
     * 下游为 KnowledgeIndexService.digest。
     *
     * @param kb 目标知识库 ID
     * @return 根据当前可索引切片字段计算的来源签名
     */
    private String sourceFingerprint(long kb) {
        MessageDigest digest = digest();
        jdbc.query("SELECT id,content FROM document_chunk WHERE knowledge_base_id=? AND " + CHILD + " ORDER BY id", rs -> {
            byte[] content = rs.getString(2).getBytes(StandardCharsets.UTF_8);
            digest.update((rs.getLong(1) + ":" + content.length + ":").getBytes(StandardCharsets.UTF_8));
            digest.update(content);
        }, kb);
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 读取目标知识库的所有者账号 ID。
     * 上游为 KnowledgeIndexService.embeddingExecution、KnowledgeIndexService.startRebuild。
     *
     * @param kb 目标知识库 ID
     * @return 知识库的所有者账号 ID
     */
    private long owner(long kb) {
        List<Long> owners = jdbc.queryForList("SELECT creator_id FROM knowledge_base WHERE id=?", Long.class, kb);
        if (owners.isEmpty()) throw new ForbiddenException("知识库不存在或无权访问");
        return owners.get(0);
    }

    /**
     * 检查当前账号是否拥有索引维护权限。
     * 上游为 KnowledgeIndexService.startRebuild、KnowledgeIndexService.cancelRebuild。
     * 下游为 KnowledgeBaseAccessService.canManageKnowledgeBase。
     *
     * @param kb 目标知识库 ID
     */
    private void requireOwner(long kb) {
        if (!access.canManageKnowledgeBase(users.getUserId(), kb)) throw new ForbiddenException("只有知识库创建者可以重建或取消索引");
    }

    /**
     * 检查当前账号是否拥有索引读取权限。
     * 上游为 KnowledgeIndexService.status、KnowledgeIndexService.taskStatus。
     * 下游为 KnowledgeBaseAccessService.canRead。
     *
     * @param kb 目标知识库 ID
     */
    private void requireRead(long kb) {
        if (!access.canRead(users.getUserId(), kb)) throw new ForbiddenException("知识库不存在或无权访问");
    }

    /**
     * 构造用于生成索引来源指纹的消息摘要器。
     * 上游为 KnowledgeIndexService.sourceFingerprint、KnowledgeIndexService.sha。
     *
     * @return 本次计算或查询得到的 MessageDigest 结果
     */
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    /**
     * 计算指定内容的 SHA-256 十六进制摘要。
     * 上游为 KnowledgeIndexService.ensureSpace。
     * 下游为 KnowledgeIndexService.digest。
     *
     * @param value 待校验或转换的值
     * @return 内容的 SHA-256 十六进制摘要
     */
    private static String sha(String value) { return HexFormat.of().formatHex(digest().digest(value.getBytes(StandardCharsets.UTF_8))); }
    /**
     * 拒绝在缺少实际事务时运行依赖事务锁的索引操作。
     * 上游为 KnowledgeIndexService.acquireWriteBarrier、KnowledgeIndexService.writeDocumentVectors。
     * 下游为 TransactionSynchronizationManager.isActualTransactionActive。
     */
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("index writes require a transaction");
    }
    /**
     * 停止本实例的索引重建执行器。
     * 上游为 Spring 容器生命周期或应用事件。
     */
    @PreDestroy public void shutdown() { worker.shutdownNow(); }
    private record Space(String id, int dimension) { }
    private record Chunk(long id, String content) { }
    private record Claim(String task, long kb, String generation, String token) { }
    private record RunningClaim(Claim claim, Thread thread) { }
    private static final class Stopped extends RuntimeException { }
}
