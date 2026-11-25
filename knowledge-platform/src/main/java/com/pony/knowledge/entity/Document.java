/* 映射知识库文档的对象位置、元数据和处理状态。 */

package com.pony.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档记录：关联知识库、上传者、原始文件对象 key，以及整份文档的处理状态。
 * 与 DocumentTask 分别表达文档状态和处理尝试；上传受理后仍需等待任务成功才表示正常处理完成。
 */
@Data
@TableName("document")
public class Document {
    private Long id;

    private Long knowledgeBaseId;

    private String fileName;

    private String fileType;

    private String filePath;

    private Long fileSize;

    private Long uploaderId;

    private DocumentStatus status;

    private LocalDateTime createTime;

}
