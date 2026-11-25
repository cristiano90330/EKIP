/* 约定调度器执行单次文档处理任务的服务入口。 */

package com.pony.knowledge.service;

/** 文档处理工作体接口；任务必须由 TaskDispatchCoordinator 统一提交和去重。 */
public interface DocumentProcessService {
    /**
     * 读取任务和文档，取得数据库租约后委派本次解析并关闭续租登记。
     * 上游为 TaskDispatchCoordinator.submitReserved 提交的文档工作任务。
     *
     * @param taskId 文档任务 ID
     */
    void process(Long taskId);
}
