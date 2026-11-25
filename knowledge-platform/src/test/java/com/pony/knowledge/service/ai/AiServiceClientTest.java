/* 验证 Java AI 客户端将解析响应转换成带来源元数据的切片结果。 */

package com.pony.knowledge.service.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiServiceClientTest {
    /** 构造输入与依赖返回值，实际结果与固定预期一致。 */
    @Test void chunkResult_unmarshalsMetadata() {
        AiServiceClient.ChunkResult c = new AiServiceClient.ChunkResult();
        c.setHeadingPath("3.1 > 3.1.2"); c.setSourceType("table"); c.setPageNo(3); c.setRowIndex(2);
        assertEquals("table", c.getSourceType()); assertEquals("3.1 > 3.1.2", c.getHeadingPath());
    }
}
