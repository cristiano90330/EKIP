/* 验证模型注册、模型修订与可用执行配置解析。 */

package com.pony.knowledge.modelmanagement;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelRegistryServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final ModelRuntimeClient runtime=mock(ModelRuntimeClient.class);
    private final ModelRegistryService models=new ModelRegistryService(jdbc,new ObjectMapper(),new ModelCredentialCipher(""),runtime);

    /** 断言抛出 {@code ModelManagementException}。调用路径经过 {@code models.create}。 */
    @Test void draftRejectsSecretsCalibrationAndIncompatibleTypeBeforePersistence() {
        for (Map<String,Object> invalid : List.of(
                Map.<String,Object>of("apiKey","must-never-be-a-config-field"),
                Map.<String,Object>of("calibration",Map.of("threshold",0.1)),
                Map.<String,Object>of("name","x","type","embedding","protocol","openai_chat"))) {
            assertEquals(400,assertThrows(ModelManagementException.class,() -> models.create(1,invalid)).getStatus());
        }
        verifyNoInteractions(jdbc,runtime);
    }

    /** 核对 {@code models.fingerprint(second)} 与预期 {@code models.fingerprint(first)} 一致。调用路径经过 {@code second.put}。 */
    @Test void semanticFingerprintIgnoresMetadataRuntimeAndSecretsButIncludesSemanticParameters() {
        Map<String,Object> first=new LinkedHashMap<>(Map.of("type","embedding","provider","ollama","protocol","ollama_embedding",
                "model","bge","baseUrl","http://127.0.0.1:11434","dimension",1024,
                "compat",Map.of("normalize",true),"capabilities",Map.of()));
        Map<String,Object> second=new LinkedHashMap<>(first);
        second.put("name","renamed"); second.put("runtime",Map.of("timeoutMs",90000)); second.put("apiKey","rotated");
        second.put("calibration",Map.of("threshold",0.3));
        assertEquals(models.fingerprint(first),models.fingerprint(second));
        second.put("dimension",2560);
        assertNotEquals(models.fingerprint(first),models.fingerprint(second));
    }

    /** 断言抛出 {@code ModelManagementException}。调用路径经过 {@code ModelRegistryService.expected}。 */
    @Test void revisionMustBePositiveIntegralAndFinite() {
        for (Object invalid : List.of(0,-1,1.5,Double.NaN,Double.POSITIVE_INFINITY,"1"))
            assertThrows(ModelManagementException.class,() -> ModelRegistryService.expected(invalid));
        assertEquals(2,ModelRegistryService.expected(2));
    }

    /** 断言抛出 {@code ModelManagementException}。调用路径经过 {@code protocol.endsWith}。 */
    @Test void retiredNativeProtocolsAreRejectedBeforeAnyExternalCall() {
        for (String protocol : List.of("ollama_chat", "ollama_embedding")) {
            Map<String,Object> draft=new LinkedHashMap<>(Map.of("name","legacy","model","example",
                    "type",protocol.endsWith("chat") ? "chat" : "embedding", "protocol",protocol));
            assertEquals(400,assertThrows(ModelManagementException.class,() -> models.create(1,draft)).getStatus());
        }
        verifyNoInteractions(jdbc,runtime);
    }

    /** 核对 {@code registry.list(1).isEmpty()} 为真。 */
    @Test void retiredRecordIsHiddenAndCannotBeManagedOrExecuted() throws Exception {
        stubStoredModel("ollama_chat");
        String id="00000000-0000-0000-0000-000000000001";
        ModelCredentialCipher credentials=mock(ModelCredentialCipher.class);
        ModelRegistryService registry=new ModelRegistryService(jdbc,new ObjectMapper(),credentials,runtime);
        assertTrue(registry.list(1).isEmpty());
        assertEquals(404,assertThrows(ModelManagementException.class,() -> registry.testSaved(1,id,null)).getStatus());
        assertEquals(404,assertThrows(ModelManagementException.class,() -> registry.delete(1,id,1)).getStatus());
        assertEquals(404,assertThrows(ModelManagementException.class,() -> registry.credentials(1,id,1,"fixture")).getStatus());
        assertEquals(503,assertThrows(ModelManagementException.class,() -> registry.resolveOwned(1,id)).getStatus());
        assertEquals(503,assertThrows(ModelManagementException.class,() -> registry.agentExecution(1,id)).getStatus());
        verifyNoInteractions(credentials,runtime);
    }

    /** 核对 {@code models.list(1).get(0).get("protocol")} 与预期 {@code "openai_chat"} 一致。 */
    @Test void executionSnapshotDoesNotContainPublicMigrationMetadata() throws Exception {
        stubStoredModel("openai_chat");
        assertEquals("openai_chat",models.list(1).get(0).get("protocol"));
        Map<String,Object> execution=models.resolveOwned(1,"00000000-0000-0000-0000-000000000001");
        assertFalse(execution.containsKey("executionSupported"));
        assertFalse(execution.containsKey("configurationWarning"));
        assertEquals("openai_chat",execution.get("protocol"));
    }

    /**
     * 为外部调用设置响应，使用 {@code row.getString}、{@code row.getObject}。
     * 由 {@code retiredRecordIsHiddenAndCannotBeManagedOrExecuted}、{@code executionSnapshotDoesNotContainPublicMigrationMetadata} 调用。
     * @param protocol 本用例传入的{@code protocol}
     */
    @SuppressWarnings("unchecked")
    private void stubStoredModel(String protocol) throws Exception {
        java.sql.ResultSet row=mock(java.sql.ResultSet.class);
        when(row.getString("id")).thenReturn("00000000-0000-0000-0000-000000000001");
        when(row.getObject("owner_id",Long.class)).thenReturn(1L);
        when(row.getLong("revision")).thenReturn(1L);
        when(row.getString("semantic_fingerprint")).thenReturn("unchanged-fingerprint");
        when(row.getString("config")).thenReturn(new ObjectMapper().writeValueAsString(
                Map.of("name","saved","type","chat","protocol",protocol,"model","example")));
        org.mockito.stubbing.Answer<List<?>> answer=invocation -> List.of(
                ((org.springframework.jdbc.core.RowMapper<?>)invocation.getArgument(1)).mapRow(row,0));
        when(jdbc.query(anyString(),any(org.springframework.jdbc.core.RowMapper.class),eq(1L))).thenAnswer(answer);
        when(jdbc.query(anyString(),any(org.springframework.jdbc.core.RowMapper.class),
                eq("00000000-0000-0000-0000-000000000001"),eq(1L))).thenAnswer(answer);
    }

}
