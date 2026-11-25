/* 检验文档任务 SSE 对未认证用户、库成员与库所有者的访问规则。 */

package com.pony.knowledge.controller;

import com.pony.knowledge.entity.Document;
import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.entity.DocumentStatus;
import com.pony.knowledge.entity.DocumentTaskStatus;
import com.pony.knowledge.entity.KnowledgeBase;
import com.pony.knowledge.entity.KnowledgeBaseMember;
import com.pony.knowledge.mapper.DocumentMapper;
import com.pony.knowledge.mapper.DocumentTaskMapper;
import com.pony.knowledge.mapper.KnowledgeBaseMapper;
import com.pony.knowledge.mapper.KnowledgeBaseMemberMapper;
import com.pony.knowledge.utils.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "task.reliability.recovery-enabled=false")
@AutoConfigureMockMvc
@Transactional
class DocumentTaskStreamControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private KnowledgeBaseMapper kbMapper;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private DocumentTaskMapper taskMapper;

    @Autowired
    private KnowledgeBaseMemberMapper kbMemberMapper;

    private Long ownerDocId;
    private Long kbId;
    private String ownerToken;

    /** 每个用例前准备夹具，调用 {@code kb.setName}、{@code kb.setCreatorId}。 */
    @BeforeEach
    void setUp() {
        long ownerId = 999_901L;
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName("p2b-sse-" + ownerId);
        kb.setCreatorId(ownerId);
        kbMapper.insert(kb);
        kbId = kb.getId();

        Document doc = new Document();
        doc.setKnowledgeBaseId(kb.getId());
        doc.setFileName("a.pdf");
        doc.setFileType("application/pdf");
        doc.setFilePath("a.pdf");
        doc.setFileSize(100L);
        doc.setUploaderId(ownerId);
        doc.setStatus(DocumentStatus.UPLOADED);
        documentMapper.insert(doc);
        ownerDocId = doc.getId();

        DocumentTask task = new DocumentTask();
        task.setDocumentId(ownerDocId);
        task.setStatus(DocumentTaskStatus.WAITING);
        task.setAutoRetryCount(0);
        task.setManualRetryCount(0);
        taskMapper.insert(task);

        ownerToken = jwtUtil.createAccessToken(ownerId);
    }

    /** 核对 HTTP 401。 */
    @Test
    void unauthenticatedRequestIs401() throws Exception {
        mockMvc.perform(get("/document/{id}/task/stream", ownerDocId))
                .andExpect(status().isUnauthorized());
    }

    /** 核对 HTTP 403。调用路径经过 {@code jwtUtil.createAccessToken}。 */
    @Test
    void nonOwnerIs403() throws Exception {
        String strangerToken = jwtUtil.createAccessToken(999_902L);
        mockMvc.perform(get("/document/{id}/task/stream", ownerDocId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + strangerToken))
                .andExpect(status().isForbidden());
    }

    /** 核对 HTTP 200。调用路径经过 {@code member.setKnowledgeBaseId}。 */
    @Test
    void kbMemberCanStreamTask() throws Exception {
        // P1-9（2026-09-18）：任务状态/SSE 是读操作，放开给 KB 成员（creator ∪ member，与 document/list 同口径）
        long memberId = 999_903L;
        KnowledgeBaseMember member = new KnowledgeBaseMember();
        member.setKnowledgeBaseId(kbId);
        member.setUserId(memberId);
        kbMemberMapper.insert(member);

        String memberToken = jwtUtil.createAccessToken(memberId);
        mockMvc.perform(get("/document/{id}/task/stream", ownerDocId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }

    /** 核对 HTTP 200。 */
    @Test
    void ownerGetsSseStreamWithSnapshotEvent() throws Exception {
        mockMvc.perform(get("/document/{id}/task/stream", ownerDocId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }
}