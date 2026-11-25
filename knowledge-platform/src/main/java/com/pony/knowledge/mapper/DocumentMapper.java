/* 提供文档查询、切片分页、写入锁定和对象引用查询。 */

package com.pony.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pony.knowledge.entity.Document;
import com.pony.knowledge.dto.DocumentChunkDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 文档数据访问：列表查询、创建者归属查询，以及生命周期短事务中的文档行锁。
 * 归属查询用于删除和重试，读取权限由 Service 另行检查；数据库读写不代替业务授权。
 */
@Mapper
public interface DocumentMapper extends BaseMapper<Document> {

    /**
     * 按文档 ID 删除其切片数据库记录。
     * 上游为 TaskCompletionService.cancel。
     *
     * @param documentId 文档 ID
     * @return 本次计算或查询得到的 int 结果
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM document_chunk WHERE document_id = #{documentId}")
    int deleteChunks(@Param("documentId") Long documentId);

    /**
     * 按文档与知识库条件分页读取切片并裁剪预览正文。
     * 上游为 DocumentServiceImpl.pageChunks。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param page 要读取的页码或分页对象
     * @param documentId 文档 ID
     * @param knowledgeBaseId 知识库 ID
     * @return 当前范围内的分页记录和分页元数据
     */
    @Select("""
            SELECT id, document_id, knowledge_base_id, chunk_index, content, char_count,
                   source_type, page_no, heading_path, table_id, row_index, parent_chunk_id
            FROM document_chunk
            WHERE document_id = #{documentId} AND knowledge_base_id = #{knowledgeBaseId}
            ORDER BY chunk_index ASC, id ASC
            """)
    IPage<DocumentChunkDTO> selectChunkPage(
            @Param("page") Page<DocumentChunkDTO> page,
            @Param("documentId") Long documentId,
            @Param("knowledgeBaseId") Long knowledgeBaseId);

    /**
     * 对指定文档加行锁，供删除、取消与终态提交协调使用。
     * 上游为 DocumentTaskServiceImpl.retry、TaskCompletionService.claim。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param documentId 文档 ID
     * @return 知识库文档的对象位置、元数据和处理状态，以 Document 返回
     */
    @Select("SELECT * FROM document WHERE id = #{documentId} FOR UPDATE")
    Document selectByIdForUpdate(@Param("documentId") Long documentId);

    /**
     * 查找由给定用户的知识库所持有的文档。
     * 上游为 DocumentServiceImpl.delete、DocumentTaskServiceImpl.requireDocumentOwnership。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param documentId 文档 ID
     * @param userId 用户 ID
     * @return 知识库文档的对象位置、元数据和处理状态，以 Document 返回
     */
    @Select("""
            SELECT d.*
            FROM document d
            JOIN knowledge_base kb
                ON d.knowledge_base_id = kb.id
            WHERE d.id = #{documentId}
            AND kb.creator_id = #{userId}
            """)
    Document selectOwnedDocument(
            @Param("documentId") Long documentId,
            @Param("userId") Long userId
    );

    /**
     * 按知识库范围和分页参数读取文档列表。
     * 上游为 DocumentServiceImpl.page。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param page 要读取的页码或分页对象
     * @param knowledgeBaseId 知识库 ID
     * @return 当前范围内的分页记录和分页元数据
     */
    @Select("""
            SELECT d.*
            FROM document d
            WHERE d.knowledge_base_id = #{knowledgeBaseId}
            ORDER BY create_time DESC, id DESC
            """)
    IPage<Document> selectPageByKnowledgeBaseId(
            Page<Document> page,
            @Param("knowledgeBaseId") Long knowledgeBaseId
    );

    /**
     * 按授权知识库范围读取指定文档的切片目录窗口。
     * 上游为 DocumentReadAccessService.listReadableDocuments。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @param offset 从窗口起点计算的偏移
     * @param limit 本次最多处理或返回的项数
     * @return 与过滤条件匹配的 List&lt;Document&gt; 数据集合
     */
    @Select("""
            <script>
            SELECT id, knowledge_base_id, file_name, file_type, file_size, status
            FROM document
            WHERE status = 2
            <choose>
              <when test="knowledgeBaseIds != null and !knowledgeBaseIds.isEmpty()">
                AND knowledge_base_id IN
                <foreach collection="knowledgeBaseIds" item="kbId" open="(" separator="," close=")">
                  #{kbId}
                </foreach>
              </when>
              <otherwise>AND 1 = 0</otherwise>
            </choose>
            ORDER BY id ASC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Document> selectReadableDirectory(
            @Param("knowledgeBaseIds") List<Long> knowledgeBaseIds,
            @Param("offset") int offset, @Param("limit") int limit);

    /**
     * 按文档 ID 和授权知识库列表查询文档。
     * 上游为 DocumentReadAccessService.resolveReadableDocument。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param documentId 文档 ID
     * @param knowledgeBaseIds 知识库 ID 列表
     * @return 知识库文档的对象位置、元数据和处理状态，以 Document 返回
     */
    @Select("""
            <script>
            SELECT id, knowledge_base_id, file_name, file_type, file_size, status
            FROM document WHERE id = #{documentId} AND status = 2
            <choose>
              <when test="knowledgeBaseIds != null and !knowledgeBaseIds.isEmpty()">
                AND knowledge_base_id IN
                <foreach collection="knowledgeBaseIds" item="kbId" open="(" separator="," close=")">
                  #{kbId}
                </foreach>
              </when>
              <otherwise>AND 1 = 0</otherwise>
            </choose>
            </script>
            """)
    Document selectReadableInScope(@Param("documentId") Long documentId,
                                  @Param("knowledgeBaseIds") List<Long> knowledgeBaseIds);

    /**
     * 返回文档表当前引用的对象路径，用于对象清理。
     * 上游为 MinioReclaimService.reclaimOrphanObjects。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @return 与过滤条件匹配的 List&lt;String&gt; 数据集合
     */
    @Select("SELECT file_path FROM document")
    List<String> selectAllFilePaths();

    /**
     * 收集指定知识库关联的原文件路径。
     * 上游为 KnowledgeBaseRemovalService.remove。
     * 下游为 MyBatis 发出的 PostgreSQL 语句。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 与过滤条件匹配的 List&lt;String&gt; 数据集合
     */
    @Select("""
            SELECT DISTINCT d.file_path FROM document d
            WHERE d.knowledge_base_id = #{knowledgeBaseId} AND d.file_path IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1 FROM document other
                  WHERE other.file_path = d.file_path AND other.knowledge_base_id <> d.knowledge_base_id
              )
            """)
    List<String> selectFilePathsByKnowledgeBase(@Param("knowledgeBaseId") Long knowledgeBaseId);
}
