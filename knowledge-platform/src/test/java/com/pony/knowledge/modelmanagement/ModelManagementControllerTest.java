/* 验证模型管理入口的保存、配置检测和错误映射。 */

package com.pony.knowledge.modelmanagement;

import com.pony.knowledge.exception.GlobalExceptionHandler;
import com.pony.knowledge.utils.CurrentUserUtil;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ModelManagementControllerTest {
    private final ModelRegistryService models=mock(ModelRegistryService.class);
    private final CurrentUserUtil users=mock(CurrentUserUtil.class);

    /**
     * 构造 {@code MockMvc} 供测试使用，使用 {@code users.getUserId}、{@code MockMvcBuilders.standaloneSetup}。
     * 由 {@code publicManagementErrorsPreserveStatusAndStableReasonWithoutCredentialEcho}、{@code draftTestUsesOnlyExplicitInputCredential} 调用。
     * 返回 {@code MockMvc} 结果。
     */
    private MockMvc mvc() {
        when(users.getUserId()).thenReturn(71L);
        return MockMvcBuilders.standaloneSetup(new ModelManagementController(models,users))
                .setControllerAdvice(new GlobalExceptionHandler(),new ModelManagementExceptionHandler()).build();
    }

    /**
     * 核对 HTTP 409，并确认{@code $.data.reasonCode} 为 {@code "model_revision_conflict"}。
     * 调用路径经过 {@code models.credentials}。
     */
    @Test void publicManagementErrorsPreserveStatusAndStableReasonWithoutCredentialEcho() throws Exception {
        when(models.credentials(eq(71L),eq("model"),eq(1L),eq("do-not-return-this-key")))
                .thenThrow(ModelManagementException.conflict("请刷新模型配置"));
        mvc().perform(put("/models/model/credentials").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"do-not-return-this-key\",\"expectedRevision\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data.reasonCode").value("model_revision_conflict"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("do-not-return-this-key"))));
    }

    /** 核对 HTTP 200，并确认{@code $.data.success} 为 {@code false}。调用路径经过 {@code models.testDraft}。 */
    @Test void draftTestUsesOnlyExplicitInputCredential() throws Exception {
        when(models.testDraft(anyMap(),isNull(),isNull())).thenReturn(Map.of("success",false));
        mvc().perform(post("/models/test").contentType(MediaType.APPLICATION_JSON).content("{\"model\":{\"name\":\"draft\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.success").value(false));
        verify(models).testDraft(Map.of("name","draft"),null,null);verify(models,never()).resolveOwned(anyLong(),anyString());
    }
}
