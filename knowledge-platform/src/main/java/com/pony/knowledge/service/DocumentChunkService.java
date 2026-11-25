/* 约定文档切片和向量的替换以及任务终态的原子提交。 */

package com.pony.knowledge.service;

import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.service.ai.AiServiceClient;

import java.util.List;

/** 文档 chunk 写入边界：接收 Python 返回的切片与向量，原子替换指定文档的旧内容。 */
public interface DocumentChunkService {
    /**
     * 计算新切片向量，在写入屏障和文档锁下替换索引及正文。
     * 上游为 由 DocumentChunkServiceImpl 实现。
     *
     * @param documentId 文档 ID
     * @param knowledgeBaseId 知识库 ID
     * @param chunks 待处理的切片列表
     */
    void replaceChunks(
            Long documentId,
            Long knowledgeBaseId,
            List<AiServiceClient.ChunkResult> chunks
    );

    /**
     * 先准备嵌入向量，再通过任务完成事务共同提交切片和成功状态。
     * 上游为 DocumentProcessServiceImpl.processAttempt。
     *
     * @param attempt 处理尝试对应的任务快照
     * @param knowledgeBaseId 知识库 ID
     * @param chunks 待处理的切片列表
     * @return 符合本方法描述的状态或完成条件时为 true
     */
    boolean completeTask(DocumentTask attempt, Long knowledgeBaseId, List<AiServiceClient.ChunkResult> chunks);
}
