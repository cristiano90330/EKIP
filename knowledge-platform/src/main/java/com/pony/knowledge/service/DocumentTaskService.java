/* 约定文档后台任务的查询、手动重试与取消接口。 */

package com.pony.knowledge.service;

import com.pony.knowledge.dto.DocumentTaskDTO;

/** 文档任务查询、取消与人工重试接口。 */
public interface DocumentTaskService {
    /**
     * 检查文档读取权并将最新任务映射为前端状态快照。
     * 上游为 DocumentController.streamTask、DocumentController.retry。
     *
     * @param documentId 文档 ID
     * @return 本次得到的 DocumentTaskDTO 实例
     */
    DocumentTaskDTO getTask(Long documentId);

    /**
     * 在写入屏障内重置或重建任务，在事务提交后安排执行。
     * 上游为 DocumentController.retry。
     *
     * @param documentId 文档 ID
     */
    void retry(Long documentId);

    /**
     * 检查文档维护权与索引写入屏障，再提交指定处理任务的取消状态。
     * 上游为 DocumentController.cancelTask。
     *
     * @param documentId 文档 ID
     * @param taskId 文档任务 ID
     * @return 本次得到的 DocumentTaskDTO 实例
     */
    DocumentTaskDTO cancel(Long documentId, Long taskId);
}
