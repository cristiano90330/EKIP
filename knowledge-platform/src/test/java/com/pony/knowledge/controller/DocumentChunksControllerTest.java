/* 覆盖文档切片 API 的分页校验、正文和来源元数据。 */

package com.pony.knowledge.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pony.knowledge.dto.DocumentChunkDTO;
import com.pony.knowledge.dto.DocumentChunkPageQueryDTO;
import com.pony.knowledge.service.DocumentService;
import com.pony.knowledge.service.DocumentTaskService;
import com.pony.knowledge.service.task.TaskStatusEventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocumentChunksControllerTest {
    private final DocumentService documents = mock(DocumentService.class);
    private MockMvc mvc;

    /** 每个用例前准备夹具，调用 {@code MockMvcBuilders.standaloneSetup}。 */
    @BeforeEach void controller() {
        mvc = MockMvcBuilders.standaloneSetup(new DocumentController(documents,
                mock(DocumentTaskService.class), mock(TaskStatusEventBus.class))).build();
    }

    /**
     * 核对 HTTP 200，并确认{@code $.data.total} 为 {@code 1}、{@code $.data.total} 为 {@code 1}。
     * 调用路径经过 {@code chunk.setId}。
     */
    @Test void returnsPagedTextMetadata() throws Exception {
        DocumentChunkDTO chunk = new DocumentChunkDTO();
        chunk.setId(10L); chunk.setDocumentId(11L); chunk.setKnowledgeBaseId(7L);
        chunk.setChunkIndex(0); chunk.setContent("正文"); chunk.setCharCount(2);
        Page<DocumentChunkDTO> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(chunk));
        when(documents.pageChunks(eq(11L), any())).thenAnswer(call -> {
            DocumentChunkPageQueryDTO query = call.getArgument(1);
            org.junit.jupiter.api.Assertions.assertEquals(1, query.getPage());
            org.junit.jupiter.api.Assertions.assertEquals(20, query.getSize());
            return page;
        });
        mvc.perform(get("/document/11/chunks"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].content").value("正文"))
                .andExpect(jsonPath("$.data.records[0].embedding").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].searchVector").doesNotExist());
    }

    /** 核对 HTTP 400。调用路径经过 {@code mvc.perform}。 */
    @Test void rejectsInvalidPaginationBeforeCallingTheService() throws Exception {
        for (String query : List.of("page=0", "page=-1", "size=0", "size=101", "page=2147483648")) {
            mvc.perform(get("/document/11/chunks?" + query)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(documents);
    }
}
