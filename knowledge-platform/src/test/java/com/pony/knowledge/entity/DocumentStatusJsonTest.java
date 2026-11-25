/* 核对文档状态枚举兼容既有 JSON 数字编码。 */

package com.pony.knowledge.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Document 实体会被 GET /document/list 直接序列化进响应体，所以 status 换枚举类型
 * 是一次潜在的对外契约变更：裸枚举会被 Jackson 输出成 "UPLOADED"。
 * @JsonValue 把输出钉回数字，这个测试就是这条契约的守卫。
 */
class DocumentStatusJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 通过 {@code doc.setStatus} 执行本例并核对输出或记录。 */
    @Test
    void serializesAsNumericCodeLikeBeforeTheEnumMigration() throws Exception {
        Document doc = new Document();
        doc.setStatus(DocumentStatus.COMPLETED);

        assertThat(mapper.writeValueAsString(doc)).contains("\"status\":2");
    }

    /** 通过 {@code DocumentStatus.UPLOADED.getCode} 执行本例并核对输出或记录。 */
    @Test
    void codeIsStillAvailableForIntegerComparisons() {
        assertThat(DocumentStatus.UPLOADED.getCode()).isZero();
        assertThat(DocumentStatus.FAILED.getCode()).isEqualTo(3);
    }
}
