/* 验证文档目录仅暴露授权范围内的公开元数据和分页信息。 */

package com.pony.knowledge.agent.tool.knowledge;

import com.pony.knowledge.agent.model.AgentExecutionContext;
import com.pony.knowledge.agent.tool.input.ListDocumentsInput;
import com.pony.knowledge.agent.tool.output.ListDocumentsOutput;
import com.pony.knowledge.entity.Document;
import com.pony.knowledge.mapper.DocumentMapper;
import com.pony.knowledge.service.DocumentReadAccessService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ListDocumentsToolTest {
    /** 通过 {@code tool.execute} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void directoryBindsEffectiveScopeAndReturnsOnlyPublicMetadataWithContinuation() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        Document first = document(10L);
        first.setFilePath("private/object-key");
        first.setUploaderId(999L);
        when(mapper.selectReadableDirectory(List.of(7L), 0, 2))
                .thenReturn(List.of(first, document(11L)));
        ListDocumentsTool tool = new ListDocumentsTool(new DocumentReadAccessService(mapper));
        ListDocumentsOutput output = (ListDocumentsOutput) tool.execute(
                ListDocumentsInput.builder().limit(1).build(), AgentExecutionContext.builder()
                        .userId(1L).accessibleKnowledgeBaseIds(Set.of(7L)).build()).getData();
        assertTrue(output.isHasMore());
        assertEquals(1, output.getNextOffset());
        assertEquals(10L, output.getDocuments().get(0).getDocumentId());
        assertFalse(output.toString().contains("private/object-key"));
        verify(mapper).selectReadableDirectory(List.of(7L), 0, 2);
    }

    /** 通过 {@code tool.execute} 驱动用例，跳过受保护的调用，布尔状态符合断言。 */
    @Test
    void emptyScopeDoesNotReadDatabase() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        ListDocumentsTool tool = new ListDocumentsTool(new DocumentReadAccessService(mapper));
        ListDocumentsOutput output = (ListDocumentsOutput) tool.execute(new ListDocumentsInput(),
                AgentExecutionContext.builder().userId(1L).accessibleKnowledgeBaseIds(Set.of()).build()).getData();
        assertTrue(output.getDocuments().isEmpty());
        assertFalse(output.isHasMore());
        verifyNoInteractions(mapper);
    }

    /**
     * 返回当前计算采用的 {@code document}。
     *
     * @param id 本次调用传入的 {@code long}
     * @return 当前记录中的 {@code document}
     */
    private static Document document(long id) {
        Document document = new Document();
        document.setId(id);
        document.setKnowledgeBaseId(7L);
        document.setFileName("规则.pdf");
        return document;
    }
}
