/* 约定文档上传、回执查找、删除和分页读取操作。 */

package com.pony.knowledge.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.pony.knowledge.dto.DocumentPageQueryDTO;
import com.pony.knowledge.dto.DocumentChunkDTO;
import com.pony.knowledge.dto.DocumentChunkPageQueryDTO;
import com.pony.knowledge.dto.DocumentUploadReceiptDTO;
import com.pony.knowledge.dto.DocumentUploadReceiptLookupDTO;
import com.pony.knowledge.entity.Document;
import org.springframework.web.multipart.MultipartFile;

/** 文档受理与管理接口，覆盖上传回执、文档和切片分页、删除。 */
public interface DocumentService {

    /**
     * 上传原文件并储存文档与任务，使用幂等键时优先复用同内容的回执。
     * 上游为 DocumentController.upload。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param file 上传的原文件
     */
    void upload(
            Long knowledgeBaseId,
            MultipartFile file);

    /**
     * 上传原文件并储存文档与任务，使用幂等键时优先复用同内容的回执。
     * 上游为 DocumentController.upload。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param file 上传的原文件
     * @param requestKey 本次上传使用的幂等请求键
     * @return 本次得到的 DocumentUploadReceiptDTO 实例
     */
    DocumentUploadReceiptDTO upload(Long knowledgeBaseId, MultipartFile file, String requestKey);

    /**
     * 按登录用户、知识库和幂等键读取回执并附带关联状态。
     * 上游为 DocumentController.uploadReceipt。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param requestKey 本次上传使用的幂等请求键
     * @return 本次得到的 DocumentUploadReceiptLookupDTO 实例
     */
    DocumentUploadReceiptLookupDTO findUploadReceipt(Long knowledgeBaseId, String requestKey);

    /**
     * 检查文档归属和可写性，提交数据库删除后清理对象存储。
     * 上游为 DocumentController.delete。
     *
     * @param documentId 文档 ID
     */
    void delete(Long documentId);

    /**
     * 验证知识库读取权后，分页查询其中的文档。
     * 上游为 DocumentController.page。
     *
     * @param dto 携带当前操作参数的 DocumentPageQueryDTO 数据
     * @return 当前范围内的分页记录和分页元数据
     */
    IPage<Document> page(DocumentPageQueryDTO dto);

    /**
     * 先核对文档的知识库读取权，再分页读取切片及来源。
     * 上游为 DocumentController.chunks。
     *
     * @param documentId 文档 ID
     * @param query 调用方的查询内容或查询条件
     * @return 当前范围内的分页记录和分页元数据
     */
    IPage<DocumentChunkDTO> pageChunks(Long documentId, DocumentChunkPageQueryDTO query);
}
