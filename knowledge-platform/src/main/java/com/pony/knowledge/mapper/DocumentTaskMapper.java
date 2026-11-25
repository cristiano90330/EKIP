/* 以状态、尝试号和数据库租约时间约束任务认领、恢复与写回。 */

package com.pony.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pony.knowledge.entity.DocumentTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 通过 MyBatis 代理提供任务状态更新和恢复查询，供文档处理、任务完成及恢复服务调用。
 * 数据库时间决定租约和重试是否到期。认领递增累计尝试号，续租与写回按尝试号约束。
 * 分页查询只挑选候选，执行权由条件更新取得。
 */
@Mapper
public interface DocumentTaskMapper extends BaseMapper<DocumentTask> {

    String RECOVERY_DUE = "COALESCE(lease_expires_at, retry_not_before, '-infinity'::timestamptz) <= clock_timestamp()";
    String RECOVERABLE = "status IN (0, 1) AND " + RECOVERY_DUE;
    String OWNED_ATTEMPT = "status = 1 AND total_attempt_count = #{attempt} AND lease_expires_at > clock_timestamp()";

    /**
     * 仅将失败任务恢复为等待状态并记录手动重试。
     * 上游为 DocumentTaskServiceImpl.retry。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param taskId 文档任务 ID
     * @return 数据库中受本次写操作影响的行数
     */
    @Update("""
            UPDATE document_task
            SET status = 0,
                manual_retry_count = manual_retry_count + 1,
                auto_retry_count = 0,
                last_error_message = COALESCE(error_message, last_error_message),
                last_error_code = COALESCE(error_code, last_error_code),
                error_message = NULL,
                error_code = NULL,
                start_time = NULL,
                finish_time = NULL,
                update_time = NOW()
            WHERE id = #{taskId}
              AND status = 3
            """)
    int retryTask(@Param("taskId") Long taskId);

    /**
     * 将等待或处理状态的任务置为取消，清空租约和自动重试时间。
     * 上游为 TaskCompletionService.cancel。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param taskId 文档任务 ID
     * @return 数据库中受本次写操作影响的行数
     */
    @Update("""
            UPDATE document_task
            SET status = 4, finish_time = NOW(), update_time = NOW(),
                error_message = NULL, error_code = NULL,
                lease_expires_at = NULL, lease_deadline_at = NULL, retry_not_before = NULL
            WHERE id = #{taskId} AND status IN (0, 1)
            """)
    int markCancelled(@Param("taskId") Long taskId);

    /**
     * 仅认领可执行的任务，增加尝试号并建立租约期限。
     * 上游为 TaskCompletionService.claim。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param taskId 文档任务 ID
     * @param leaseMs 租约有效时长（毫秒）
     * @param maxRunMs 单次尝试的最长执行时间（毫秒）
     * @return 数据库中受本次写操作影响的行数
     */
    @Update("""
            UPDATE document_task
            SET status = 1,
                auto_retry_count = auto_retry_count + 1,
                total_attempt_count = total_attempt_count + 1,
                start_time = NOW(),
                update_time = NOW(),
                lease_expires_at = clock_timestamp() + (#{leaseMs} * INTERVAL '1 millisecond'),
                lease_deadline_at = clock_timestamp() + (#{maxRunMs} * INTERVAL '1 millisecond'),
                retry_not_before = NULL
            WHERE id = #{taskId}
              AND
            """ + RECOVERABLE)
    int claimProcessing(@Param("taskId") Long taskId, @Param("leaseMs") long leaseMs, @Param("maxRunMs") long maxRunMs);

    /**
     * 仅为仍拥有租约的同一尝试延长有效期。
     * 上游为 TaskLeaseHeartbeat.maintain。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param taskId 文档任务 ID
     * @param attempt 本次执行的尝试编号
     * @param leaseMs 租约有效时长（毫秒）
     * @return 数据库中受本次写操作影响的行数
     */
    @Update("""
            UPDATE document_task
            SET lease_expires_at = LEAST(lease_deadline_at, clock_timestamp() + (#{leaseMs} * INTERVAL '1 millisecond'))
            WHERE id = #{taskId} AND
            """ + OWNED_ATTEMPT)
    int renewLease(@Param("taskId") Long taskId, @Param("attempt") int attempt, @Param("leaseMs") long leaseMs);

    /**
     * 在尝试号和租约仍有效时记录任务成功。
     * 上游为 TaskCompletionService.finishLocked。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param taskId 文档任务 ID
     * @param attempt 本次执行的尝试编号
     * @return 数据库中受本次写操作影响的行数
     */
    @Update("""
            UPDATE document_task
            SET status = 2,
                finish_time = NOW(),
                update_time = NOW(),
                error_message = NULL,
                error_code = NULL,
                lease_expires_at = NULL, lease_deadline_at = NULL
            WHERE id = #{taskId} AND
            """ + OWNED_ATTEMPT)
    int markSuccess(@Param("taskId") Long taskId, @Param("attempt") int attempt);

    /**
     * 在尝试号和租约仍有效时持久化任务失败和原因。
     * 上游为 TaskCompletionService.finishLocked。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param taskId 文档任务 ID
     * @param attempt 本次执行的尝试编号
     * @param errorMessage 可记录或返回的错误详情
     * @param errorCode 稳定的错误分类码
     * @return 数据库中受本次写操作影响的行数
     */
    @Update("""
            UPDATE document_task
            SET status = 3,
                finish_time = NOW(),
                update_time = NOW(),
                error_message = #{errorMessage},
                error_code = #{errorCode},
                last_error_message = #{errorMessage},
                last_error_code = #{errorCode},
                lease_expires_at = NULL, lease_deadline_at = NULL
            WHERE id = #{taskId} AND
            """ + OWNED_ATTEMPT)
    int markFailed(@Param("taskId") Long taskId,
                   @Param("attempt") int attempt,
                   @Param("errorMessage") String errorMessage,
                   @Param("errorCode") String errorCode);

    /**
     * 保存当前尝试的失败详情与下次重试时间，释放处理租约。
     * 上游为 DocumentProcessServiceImpl.processAttempt。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param taskId 文档任务 ID
     * @param attempt 本次执行的尝试编号
     * @param delayMs 下次投递前的等待时长（毫秒）
     * @param errorMessage 可记录或返回的错误详情
     * @param errorCode 稳定的错误分类码
     * @return 数据库中受本次写操作影响的行数
     */
    @Update("""
            UPDATE document_task
            SET error_message = #{errorMessage},
                error_code = #{errorCode},
                last_error_message = #{errorMessage},
                last_error_code = #{errorCode},
                update_time = NOW(),
                retry_not_before = clock_timestamp() + (#{delayMs} * INTERVAL '1 millisecond'),
                lease_expires_at = NULL, lease_deadline_at = NULL
            WHERE id = #{taskId} AND
            """ + OWNED_ATTEMPT)
    int recordRetryFailure(@Param("taskId") Long taskId,
                           @Param("attempt") int attempt,
                           @Param("delayMs") long delayMs,
                           @Param("errorMessage") String errorMessage,
                           @Param("errorCode") String errorCode);

    /**
     * 将调度失败且没有有效租约的处理任务退回等待。
     * 上游为 TaskCompletionService.finishLocked。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param taskId 文档任务 ID
     * @param attempt 本次执行的尝试编号
     * @param errorMessage 可记录或返回的错误详情
     * @param errorCode 稳定的错误分类码
     * @return 数据库中受本次写操作影响的行数
     */
    @Update("""
            UPDATE document_task
            SET status = 0,
                error_message = #{errorMessage},
                error_code = #{errorCode},
                last_error_message = COALESCE(last_error_message, error_message, #{errorMessage}),
                last_error_code = COALESCE(last_error_code, error_code, #{errorCode}),
                start_time = NULL,
                finish_time = NULL,
                update_time = NOW()
            WHERE id = #{taskId}
              AND status = 1
              AND total_attempt_count = #{attempt}
              AND lease_expires_at IS NULL
              AND retry_not_before IS NOT NULL
            """)
    int markWaitingAfterRetryScheduleFailure(@Param("taskId") Long taskId,
                                             @Param("attempt") int attempt,
                                             @Param("errorMessage") String errorMessage,
                                             @Param("errorCode") String errorCode);

    /**
     * 按游标和批次大小查找没有有效租约的可恢复任务。
     * 上游为 TaskRecoveryService.onApplicationEvent。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param afterId 上一页最后一个标识
     * @param limit 本次最多处理或返回的项数
     * @return 与过滤条件匹配的 List&lt;DocumentTask&gt; 数据集合
     */
    @Select("SELECT * FROM document_task WHERE " + RECOVERABLE + " AND id > #{afterId} ORDER BY id LIMIT #{limit}")
    List<DocumentTask> findRecoverableTasks(@Param("afterId") Long afterId, @Param("limit") int limit);

    /**
     * 按游标分页查找等待时间达到阈值的任务。
     * 上游为 TaskRecoveryService.findPage。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param waitingMs 以毫秒计的时间限制
     * @param afterId 上一页最后一个标识
     * @param limit 本次最多处理或返回的项数
     * @return 与过滤条件匹配的 List&lt;DocumentTask&gt; 数据集合
     */
    @Select("""
            SELECT * FROM document_task
            WHERE status = 0
              AND update_time <= clock_timestamp()::timestamp - (#{waitingMs} * INTERVAL '1 millisecond')
              AND id > #{afterId}
              AND
            """ + RECOVERY_DUE + " ORDER BY id LIMIT #{limit}")
    List<DocumentTask> findWaitingTasks(@Param("waitingMs") long waitingMs,
                                        @Param("afterId") Long afterId,
                                        @Param("limit") int limit);

    /**
     * 按游标分页查找租约过期或重试到期的处理任务。
     * 上游为 TaskRecoveryService.findPage。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param afterId 上一页最后一个标识
     * @param limit 本次最多处理或返回的项数
     * @return 与过滤条件匹配的 List&lt;DocumentTask&gt; 数据集合
     */
    @Select("SELECT * FROM document_task WHERE status = 1 AND " + RECOVERY_DUE
            + " AND id > #{afterId} ORDER BY id LIMIT #{limit}")
    List<DocumentTask> findRecoverableProcessingTasks(@Param("afterId") Long afterId, @Param("limit") int limit);
}
