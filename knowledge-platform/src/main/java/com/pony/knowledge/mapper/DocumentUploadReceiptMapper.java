/* 预留、读取和更新持久化的上传幂等回执。 */

package com.pony.knowledge.mapper;

import com.pony.knowledge.entity.DocumentUploadReceiptRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 上传幂等的持久化入口，以用户、知识库和请求键唯一确定一次上传受理记录。
 * 同一事务内预留键并关联文档 / 任务；竞争失败由 Service 读取已有回执并核对内容摘要。
 */
@Mapper
public interface DocumentUploadReceiptMapper {

    /**
     * 通过唯一约束尝试登记上传请求键和内容指纹。
     * 上游为 DocumentServiceImpl.persistUpload。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param userId 用户 ID
     * @param knowledgeBaseId 知识库 ID
     * @param requestKey 本次上传使用的幂等请求键
     * @param contentSha256 用于确认上传内容是否相同的 SHA-256 指纹
     * @return 数据库中受本次写操作影响的行数
     */
    @Insert("""
            INSERT INTO document_upload_receipt(user_id, knowledge_base_id, request_key, content_sha256)
            VALUES (#{userId}, #{knowledgeBaseId}, #{requestKey}, #{contentSha256})
            ON CONFLICT (user_id, knowledge_base_id, request_key) DO NOTHING
            """)
    int reserve(@Param("userId") Long userId,
                @Param("knowledgeBaseId") Long knowledgeBaseId,
                @Param("requestKey") String requestKey,
                @Param("contentSha256") String contentSha256);

    /**
     * 按用户、知识库和请求键定位原上传回执。
     * 上游为 DocumentServiceImpl.upload、DocumentServiceImpl.findUploadReceipt。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param userId 用户 ID
     * @param knowledgeBaseId 知识库 ID
     * @param requestKey 本次上传使用的幂等请求键
     * @return 按账号、知识库和请求键保存的上传幂等回执，以 DocumentUploadReceiptRecord 返回
     */
    @Select("""
            SELECT id, user_id, knowledge_base_id, request_key, content_sha256,
                   document_id, task_id, create_time
            FROM document_upload_receipt
            WHERE user_id = #{userId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND request_key = #{requestKey}
            """)
    @Results(id = "documentUploadReceipt", value = {
            @Result(column = "id", property = "id", id = true),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "knowledge_base_id", property = "knowledgeBaseId"),
            @Result(column = "request_key", property = "requestKey"),
            @Result(column = "content_sha256", property = "contentSha256"),
            @Result(column = "document_id", property = "documentId"),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "create_time", property = "createTime")
    })
    DocumentUploadReceiptRecord find(@Param("userId") Long userId,
                                     @Param("knowledgeBaseId") Long knowledgeBaseId,
                                     @Param("requestKey") String requestKey);

    /**
     * 为已预留的请求键补齐文档和任务 ID。
     * 上游为 DocumentServiceImpl.persistUpload。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param userId 用户 ID
     * @param knowledgeBaseId 知识库 ID
     * @param requestKey 本次上传使用的幂等请求键
     * @param documentId 文档 ID
     * @param taskId 文档任务 ID
     * @return 数据库中受本次写操作影响的行数
     */
    @Update("""
            UPDATE document_upload_receipt
            SET document_id = #{documentId}, task_id = #{taskId}
            WHERE user_id = #{userId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND request_key = #{requestKey}
              AND document_id IS NULL
            """)
    int complete(@Param("userId") Long userId,
                 @Param("knowledgeBaseId") Long knowledgeBaseId,
                 @Param("requestKey") String requestKey,
                 @Param("documentId") Long documentId,
                 @Param("taskId") Long taskId);

    /**
     * 将当前仍绑定上一任务的回执更新到重试任务。
     * 上游为 DocumentTaskServiceImpl.createReplacement。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param documentId 文档 ID
     * @param cancelledTaskId 要定位的关联标识
     * @param newTaskId 要定位的关联标识
     * @return 数据库中受本次写操作影响的行数
     */
    @Update("""
            UPDATE document_upload_receipt
            SET task_id = #{newTaskId}
            WHERE document_id = #{documentId} AND task_id = #{cancelledTaskId}
            """)
    int followRetriedTask(@Param("documentId") Long documentId,
                          @Param("cancelledTaskId") Long cancelledTaskId,
                          @Param("newTaskId") Long newTaskId);
}
