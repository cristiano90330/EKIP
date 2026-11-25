/* 检查问答来源 DTO 的标题路径保留行为。 */

package com.pony.knowledge.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SourceDTO 透出 P2a 入库元数据：headingPath / pageNo / sourceType。
 * 仅做 get/set 断言，验证字段已暴露且可正常读写。
 */
class SourceMetadataTest {

    /** 核对 {@code s.getHeadingPath()} 与预期 {@code "3.1"} 一致。调用路径经过 {@code s.setHeadingPath}。 */
    @Test
    void sourceCarriesHeadingPath() {
        SourceDTO s = new SourceDTO();
        s.setHeadingPath("3.1");
        s.setPageNo(2);
        s.setSourceType("table");
        assertEquals("3.1", s.getHeadingPath());
        assertEquals(2, s.getPageNo());
        assertEquals("table", s.getSourceType());
    }
}
