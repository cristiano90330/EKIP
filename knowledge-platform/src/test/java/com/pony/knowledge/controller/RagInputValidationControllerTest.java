/* 通过正常的 Web 绑定入口验证 RAG、账号及参数格式约束。 */

package com.pony.knowledge.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.config.SecurityConfig;
import com.pony.knowledge.dto.UserProfileDTO;
import com.pony.knowledge.observability.RagStageTracer;
import com.pony.knowledge.security.JwtAuthenticationFilter;
import com.pony.knowledge.security.LoginThrottle;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.service.UserService;
import com.pony.knowledge.service.rag.PythonRagPipeline;
import com.pony.knowledge.service.rag.RagService;
import com.pony.knowledge.service.rag.model.RagQueryOutcome;
import com.pony.knowledge.utils.CurrentUserUtil;
import com.pony.knowledge.utils.JwtUtil;
import com.pony.knowledge.entity.User;
import com.pony.knowledge.entity.UserStatus;
import com.pony.knowledge.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证真实 Security/MVC 输入边界，业务组件全部替换为无外部依赖的 mock。 */
@WebMvcTest(controllers = {
        KnowledgeChatController.class, RagController.class, KnowledgeSearchController.class, UserController.class
})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class RagInputValidationControllerTest {
    private static final String TOKEN = "valid-input-test-token";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockitoBean
    private RagService service;
    @MockitoBean
    private PythonRagPipeline pipeline;
    @MockitoBean
    private KnowledgeBaseAccessService access;
    @MockitoBean
    private CurrentUserUtil user;
    @MockitoBean
    private RagStageTracer tracer;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private LoginThrottle loginThrottle;
    @MockitoBean
    private JwtUtil jwtUtil;
    @MockitoBean
    private UserMapper users;
    @MockitoBean
    private UserDetailsService userDetailsService;
    @MockitoBean
    private PasswordEncoder passwordEncoder;

    /** 比较未认证和已认证的个人资料请求。断言匿名请求被拒绝，已登录请求按认证身份返回资料且忽略查询参数指定的身份。 */
    @Test
    void profileRequiresAuthenticationAndUsesOnlyTheAuthenticatedUser() throws Exception {
        doAnswer(invocation -> new CurrentUserUtil().getUserId()).when(user).getUserId();
        when(userService.profile(42L)).thenReturn(new UserProfileDTO(42L, "current-user", "研发"));

        mvc.perform(get("/user/me")).andExpect(status().isUnauthorized());
        verifyNoInteractions(userService);

        mvc.perform(get("/user/me").param("userId", "99").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(42))
                .andExpect(jsonPath("$.data.username").value("current-user"))
                .andExpect(jsonPath("$.data.department").value("研发"))
                .andExpect(jsonPath("$.data.password").doesNotExist());
        verify(userService).profile(42L);
    }

    /** 固定测试账号、可读库范围和检索替身，便于断言 MVC 验证后的下游调用。 */
    @BeforeEach
    void configureMocks() {
        when(jwtUtil.parseAccessToken(TOKEN)).thenReturn(new JwtUtil.AccessTokenInfo(42L, 0));
        User account = new User();
        account.setStatus(UserStatus.NORMAL);
        when(users.selectAccountState(42L)).thenReturn(account);
        when(user.getUserId()).thenReturn(42L);
        when(access.resolveReadableIds(42L)).thenReturn(Set.of(7L));
        when(tracer.observeRoot(eq("search"), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        RagQueryOutcome result = RagQueryOutcome.builder().sources(List.of()).build();
        when(pipeline.retrieveOnly(anyList(), anyString(), anyList(), anyInt())).thenReturn(result);
        when(pipeline.retrieveOnly(anyList(), anyString(), anyList(), anyInt(), anyString())).thenReturn(result);
    }

    /** 向公开问答路由提交缺少内容且未认证的请求，核对认证拒绝先于正文参数校验。 */
    @Test
    void authenticationRejectsBeforeInvalidInputIsValidated() throws Exception {
        mvc.perform(post("/knowledge/ask").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/rag/chat").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/knowledge/search"))
                .andExpect(status().isUnauthorized());
        verifyNoBusinessExecution();
    }

    /** 核对 HTTP 400。调用路径经过 {@code mvc.perform}。 */
    @Test
    void missingNullEmptyAndWhitespaceQuestionsAre400ForAllEntries() throws Exception {
        for (String question : new String[]{null, "", " \t\n", "\u0085\u00a0\u3000"}) {
            mvc.perform(json("/knowledge/ask", input(question))).andExpect(status().isBadRequest());
            mvc.perform(json("/rag/chat", chatInput(question))).andExpect(status().isBadRequest());
            if (question == null) {
                mvc.perform(authenticated(get("/knowledge/search"))).andExpect(status().isBadRequest());
            } else {
                mvc.perform(authenticated(get("/knowledge/search").param("query", question)))
                        .andExpect(status().isBadRequest());
            }
        }
        mvc.perform(json("/knowledge/ask", Map.of())).andExpect(status().isBadRequest());
        Map<String, Object> chat = chatInput("q");
        chat.remove("question");
        mvc.perform(json("/rag/chat", chat)).andExpect(status().isBadRequest());
        verifyNoBusinessExecution();
    }

    /** 核对 HTTP 400。调用路径经过 {@code mvc.perform}。 */
    @Test
    void overlongQuestionsAre400BeforeAnyBusinessExecution() throws Exception {
        for (String question : List.of("q".repeat(4001), "😀".repeat(4001))) {
            mvc.perform(json("/knowledge/ask", input(question))).andExpect(status().isBadRequest());
            mvc.perform(json("/rag/chat", chatInput(question))).andExpect(status().isBadRequest());
            mvc.perform(authenticated(get("/knowledge/search").param("query", question)))
                    .andExpect(status().isBadRequest());
        }
        verifyNoBusinessExecution();
    }

    /** 用 Unicode 空白和组合字符检查归一化后的问题长度，以代码点为单位断言边界。 */
    @Test
    void questionBoundaryCountsCodePointsAfterPythonWhitespaceStripping() throws Exception {
        String question = "\u0085\u00a0 " + "😀".repeat(4000) + " \u202f\u3000";
        mvc.perform(json("/knowledge/ask", input(question))).andExpect(status().isOk());
        mvc.perform(json("/rag/chat", chatInput(question))).andExpect(status().isOk());
        mvc.perform(authenticated(get("/knowledge/search").param("query", question)))
                .andExpect(status().isOk());
        verify(service).ask(question, 5);
        verify(service).chat(7L, "c1", question, 5);
        verify(pipeline).retrieveOnly(List.of(7L), question, List.of(), 5);
    }

    /** 给各公开问答入口提交超出范围的 topK，确认统一返回 400。 */
    @Test
    void topKOutsidePublicRangeIs400ForAllEntries() throws Exception {
        for (int topK : new int[]{0, -1, 21}) {
            Map<String, Object> ask = input("q");
            ask.put("topK", topK);
            mvc.perform(json("/knowledge/ask", ask)).andExpect(status().isBadRequest());
            Map<String, Object> chat = chatInput("q");
            chat.put("topK", topK);
            mvc.perform(json("/rag/chat", chat)).andExpect(status().isBadRequest());
            mvc.perform(authenticated(get("/knowledge/search").param("query", "q")
                            .param("topK", String.valueOf(topK))))
                    .andExpect(status().isBadRequest());
        }
        verifyNoBusinessExecution();
    }

    /** 向 RAG 入口提交损坏的 JSON 和不合规数值，核对 HTTP 400 且未调用业务组件。 */
    @Test
    void malformedNumbersAndJsonAre400BeforeAnyBusinessExecution() throws Exception {
        for (String path : List.of("/knowledge/ask", "/rag/chat")) {
            mvc.perform(authenticated(post(path)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"q\",\"topK\":\"oops\"}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(authenticated(post(path)).contentType(MediaType.APPLICATION_JSON).content("{"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(authenticated(get("/knowledge/search").param("query", "q").param("topK", "oops")))
                .andExpect(status().isBadRequest());
        verifyNoBusinessExecution();
    }

    /** 用非整数和溢出值作为 topK，确认绑定失败发生在业务调用之前。 */
    @Test
    void topKRejectsNonIntegerJsonTypesAndOverflowBeforeAnyBusinessExecution() throws Exception {
        for (String topK : List.of("1.5", "1.0", "1e0", "true", "false", "\"1\"", "\"\"",
                "[1]", "{}", "2147483648")) {
            for (String path : List.of("/knowledge/ask", "/rag/chat")) {
                mvc.perform(authenticated(post(path)).contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"q\",\"knowledgeBaseId\":7,"
                                        + "\"conversationId\":\"c1\",\"topK\":" + topK + "}"))
                        .andExpect(status().isBadRequest());
            }
        }
        verifyNoBusinessExecution();
    }

    /** 用非整数和溢出值作为库 ID，确认绑定失败时未执行检索。 */
    @Test
    void knowledgeBaseIdRejectsNonIntegerJsonTypesAndOverflowBeforeAnyBusinessExecution() throws Exception {
        for (String knowledgeBaseId : List.of("1.5", "1.0", "1e0", "true", "false", "\"7\"", "\"\"",
                "[7]", "{}", "9223372036854775808")) {
            mvc.perform(authenticated(post("/rag/chat")).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"q\",\"conversationId\":\"c1\","
                                    + "\"knowledgeBaseId\":" + knowledgeBaseId + "}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoBusinessExecution();
    }

    /** 核对 HTTP 200。调用路径经过 {@code chat.put}。 */
    @Test
    void knowledgeBaseIdRetainsLongIntegerRange() throws Exception {
        Map<String, Object> chat = chatInput("q");
        chat.put("knowledgeBaseId", Long.MAX_VALUE);
        mvc.perform(json("/rag/chat", chat)).andExpect(status().isOk());
        verify(service).chat(Long.MAX_VALUE, "c1", "q", 5);
    }

    /** 显式提交空 topK，断言两个 JSON 问答入口都向下游传递默认值 5。 */
    @Test
    void explicitNullTopKUsesFiveForBothJsonEntries() throws Exception {
        Map<String, Object> ask = input("q");
        ask.put("topK", null);
        mvc.perform(json("/knowledge/ask", ask)).andExpect(status().isOk());
        Map<String, Object> chat = chatInput("q");
        chat.put("topK", null);
        mvc.perform(json("/rag/chat", chat)).andExpect(status().isOk());
        verify(service).ask("q", 5);
        verify(service).chat(7L, "c1", "q", 5);
    }

    /** 核对 HTTP 400。调用路径经过 {@code ask.put}。 */
    @Test
    void stringFieldsRejectOtherJsonTypesBeforeAnyBusinessExecution() throws Exception {
        for (Object value : List.of(123, 1.5, true, false, Map.of("value", "q"), List.of("q"))) {
            for (String field : List.of("question", "pipelineEngine")) {
                Map<String, Object> ask = input("q");
                ask.put(field, value);
                mvc.perform(json("/knowledge/ask", ask)).andExpect(status().isBadRequest());
            }
            for (String field : List.of("question", "conversationId", "pipelineEngine")) {
                Map<String, Object> chat = chatInput("q");
                chat.put(field, value);
                mvc.perform(json("/rag/chat", chat)).andExpect(status().isBadRequest());
            }
        }
        verifyNoBusinessExecution();
    }

    /** 核对 HTTP 400。调用路径经过 {@code mvc.perform}。 */
    @Test
    void duplicateJsonFieldsAreRejectedBeforeAnyBusinessExecution() throws Exception {
        mvc.perform(authenticated(post("/knowledge/ask"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"first\",\"question\":\"second\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(authenticated(post("/rag/chat"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"knowledgeBaseId\":7,\"question\":\"first\","
                                + "\"question\":\"second\",\"conversationId\":\"c1\"}"))
                .andExpect(status().isBadRequest());

        verifyNoBusinessExecution();
    }

    /** 核对 HTTP 200。调用路径经过 {@code registration.put}。 */
    @Test
    void registrationAcceptsDatabaseColumnLengthBoundaries() throws Exception {
        Map<String, Object> registration = new LinkedHashMap<>();
        registration.put("username", "u".repeat(50));
        registration.put("password", "secret1");
        registration.put("email", "a".repeat(50) + "@" + "b".repeat(45) + ".com");

        mvc.perform(post("/user/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(registration)))
                .andExpect(status().isOk());

        verify(userService).register(any());
    }

    /** 核对 HTTP 400。调用路径经过 {@code usernameTooLong.put}。 */
    @Test
    void registrationRejectsValuesBeyondDatabaseColumnLengthsBeforeServiceCall() throws Exception {
        Map<String, Object> usernameTooLong = new LinkedHashMap<>();
        usernameTooLong.put("username", "u".repeat(51));
        usernameTooLong.put("password", "secret1");
        usernameTooLong.put("email", "alice@example.com");

        Map<String, Object> emailTooLong = new LinkedHashMap<>();
        emailTooLong.put("username", "alice");
        emailTooLong.put("password", "secret1");
        emailTooLong.put("email", "a".repeat(50) + "@" + "b".repeat(46) + ".com");

        for (Map<String, Object> registration : List.of(usernameTooLong, emailTooLong)) {
            mvc.perform(post("/user/register").contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(registration)))
                    .andExpect(status().isBadRequest());
        }

        verifyNoInteractions(userService);
    }

    /** 核对 HTTP 200。调用路径经过 {@code ask.put}。 */
    @Test
    void explicitNullEngineUsesServerDefaultForBothJsonEntries() throws Exception {
        Map<String, Object> ask = input("q");
        ask.put("pipelineEngine", null);
        mvc.perform(json("/knowledge/ask", ask)).andExpect(status().isOk());
        Map<String, Object> chat = chatInput("q");
        chat.put("pipelineEngine", null);
        mvc.perform(json("/rag/chat", chat)).andExpect(status().isOk());
        verify(service).ask("q", 5);
        verify(service).chat(7L, "c1", "q", 5);
    }

    /** 核对 HTTP 400。调用路径经过 {@code ask.put}。 */
    @Test
    void explicitInvalidEnginesAre400ForAllEntries() throws Exception {
        for (String engine : List.of("", "auto", "FIXED", " fixed ")) {
            Map<String, Object> ask = input("q");
            ask.put("pipelineEngine", engine);
            mvc.perform(json("/knowledge/ask", ask)).andExpect(status().isBadRequest());
            Map<String, Object> chat = chatInput("q");
            chat.put("pipelineEngine", engine);
            mvc.perform(json("/rag/chat", chat)).andExpect(status().isBadRequest());
            mvc.perform(authenticated(get("/knowledge/search").param("query", "q")
                            .param("pipelineEngine", engine)))
                    .andExpect(status().isBadRequest());
        }
        verifyNoBusinessExecution();
    }

    /** 核对 HTTP 200。调用路径经过 {@code mvc.perform}。 */
    @Test
    void omittedTopKUsesFiveAndOmittedEngineUsesServerDefault() throws Exception {
        mvc.perform(json("/knowledge/ask", input("q"))).andExpect(status().isOk());
        mvc.perform(json("/rag/chat", chatInput("q"))).andExpect(status().isOk());
        mvc.perform(authenticated(get("/knowledge/search").param("query", "q")))
                .andExpect(status().isOk());
        verify(service).ask("q", 5);
        verify(service).chat(7L, "c1", "q", 5);
        verify(pipeline).retrieveOnly(List.of(7L), "q", List.of(), 5);
    }

    /** 核对 HTTP 200。调用路径经过 {@code ask.put}。 */
    @Test
    void topKBoundariesAndBothExplicitEnginesReachBusinessUnchanged() throws Exception {
        for (int topK : new int[]{1, 20}) {
            for (String engine : List.of("fixed", "langchain")) {
                Map<String, Object> ask = input("q");
                ask.put("topK", topK);
                ask.put("pipelineEngine", engine);
                mvc.perform(json("/knowledge/ask", ask)).andExpect(status().isOk());
                Map<String, Object> chat = chatInput("q");
                chat.put("topK", topK);
                chat.put("pipelineEngine", engine);
                mvc.perform(json("/rag/chat", chat)).andExpect(status().isOk());
                mvc.perform(authenticated(get("/knowledge/search").param("query", "q")
                                .param("topK", String.valueOf(topK)).param("pipelineEngine", engine)))
                        .andExpect(status().isOk());
                verify(service).ask("q", topK, engine);
                verify(service).chat(7L, "c1", "q", topK, engine);
                verify(pipeline).retrieveOnly(List.of(7L), "q", List.of(), topK, engine);
            }
        }
    }

    /** 核对 HTTP 400。调用路径经过 {@code chat.put}。 */
    @Test
    void existingKnowledgeBaseAndConversationIdConstraintsRemainEnforced() throws Exception {
        for (Object knowledgeBaseId : new Object[]{null, 0, -1}) {
            Map<String, Object> chat = chatInput("q");
            chat.put("knowledgeBaseId", knowledgeBaseId);
            mvc.perform(json("/rag/chat", chat)).andExpect(status().isBadRequest());
        }
        for (String conversationId : new String[]{null, "", " ", "bad/session", "c".repeat(129)}) {
            Map<String, Object> chat = chatInput("q");
            chat.put("conversationId", conversationId);
            mvc.perform(json("/rag/chat", chat)).andExpect(status().isBadRequest());
        }
        verifyNoBusinessExecution();
    }

    /**
     * 构造 {@code MockHttpServletRequestBuilder} 供测试使用，使用 {@code objectMapper.writeValueAsBytes}、{@code authenticated}。
     * 由 {@code missingNullEmptyAndWhitespaceQuestionsAre400ForAllEntries}、{@code overlongQuestionsAre400BeforeAnyBusinessExecution} 调用。
     * @param path 本用例传入的{@code path}
     * @param body 请求载荷
     * 返回 {@code MockHttpServletRequestBuilder} 结果。
     */
    private MockHttpServletRequestBuilder json(String path, Object body) throws Exception {
        return authenticated(post(path)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body));
    }

    /**
     * 构造 {@code MockHttpServletRequestBuilder} 供测试使用，使用 {@code request.header}。
     * 由 {@code missingNullEmptyAndWhitespaceQuestionsAre400ForAllEntries}、{@code overlongQuestionsAre400BeforeAnyBusinessExecution} 调用。
     * @param request MockMvc 请求构造器
     * 返回 {@code request.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)}。
     */
    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
    }

    /**
     * 构造 {@code Map<String, Object>} 供测试使用，使用 {@code input.put}。
     * 由 {@code missingNullEmptyAndWhitespaceQuestionsAre400ForAllEntries}、{@code overlongQuestionsAre400BeforeAnyBusinessExecution} 调用。
     * @param question 发给测试入口的问答文本
     * 返回 {@code input}。
     */
    private Map<String, Object> input(String question) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("question", question);
        return input;
    }

    /**
     * 构造 {@code Map<String, Object>} 供测试使用，使用 {@code input.put}、{@code input}。
     * 由 {@code missingNullEmptyAndWhitespaceQuestionsAre400ForAllEntries}、{@code overlongQuestionsAre400BeforeAnyBusinessExecution} 调用。
     * @param question 发给测试入口的问答文本
     * 返回 {@code input}。
     */
    private Map<String, Object> chatInput(String question) {
        Map<String, Object> input = input(question);
        input.put("knowledgeBaseId", 7L);
        input.put("conversationId", "c1");
        return input;
    }

    /**
     * 检查输入拒绝前的业务服务和下游均未执行。
     * 由 {@code authenticationRejectsBeforeInvalidInputIsValidated}、{@code missingNullEmptyAndWhitespaceQuestionsAre400ForAllEntries} 调用。
     */
    private void verifyNoBusinessExecution() {
        verifyNoInteractions(service, pipeline, access, user, tracer);
    }
}
