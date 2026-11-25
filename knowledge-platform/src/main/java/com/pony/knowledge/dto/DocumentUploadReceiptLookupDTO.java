/* 区分上传回执未命中和命中后返回实际回执。 */

package com.pony.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 上传回执查询结果；未找到时只返回 found，找到时附带原受理回执。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentUploadReceiptLookupDTO {
    private boolean found;
    private DocumentUploadReceiptDTO receipt;
}
