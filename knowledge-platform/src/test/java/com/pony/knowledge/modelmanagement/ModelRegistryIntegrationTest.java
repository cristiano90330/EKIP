/* 在隔离数据库核对模型登记、凭证与用途绑定的持久化。 */

package com.pony.knowledge.modelmanagement;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real locking, credential and FK checks in an explicitly disposable database. */
@EnabledIfEnvironmentVariable(named="EKIP_INDEX_TEST_ISOLATED",matches="true")
class ModelRegistryIntegrationTest {
    private static final AtomicLong IDS=new AtomicLong(200000);
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private ModelRegistryService models;
    private long owner,other;

    /** 初始化本组用例共用依赖，调用 {@code url.matches}、{@code jdbc.queryForObject}。 */
    @BeforeAll static void database() {
        String url=System.getenv("EKIP_INDEX_TEST_JDBC_URL");
        assertNotNull(url);
        assertTrue(url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/(ekip_model_test|ekip_index_test)[A-Za-z0-9_]*(\\?.*)?"));
        DriverManagerDataSource data=new DriverManagerDataSource(url,System.getenv("EKIP_INDEX_TEST_DB_USER"),System.getenv("EKIP_INDEX_TEST_DB_PASSWORD"));
        jdbc=new JdbcTemplate(data);
        assertTrue(jdbc.queryForObject("SELECT current_database()",String.class).matches("ekip_(model|index)_test.*"));
        Flyway.configure().dataSource(data).locations("classpath:db/migration").load().migrate();
        transactions=new TransactionTemplate(new DataSourceTransactionManager(data));
    }

    /** 每个用例前准备夹具，调用 {@code IDS.incrementAndGet}、{@code jdbc.update}。 */
    @BeforeEach void setup() {
        owner=IDS.incrementAndGet(); other=IDS.incrementAndGet();
        for (long user : List.of(owner,other)) jdbc.update("INSERT INTO sys_user(id,username,password) VALUES(?,?,?)",user,"models-test-"+user,"unused");
        models=new ModelRegistryService(jdbc,new ObjectMapper(),new ModelCredentialCipher(Base64.getEncoder().encodeToString(new byte[32])),mock(ModelRuntimeClient.class));
    }

    /**
     * 构造 {@code T} 供测试使用，使用 {@code transactions.execute}、{@code work.get}。
     * 由 {@code create}、{@code ownerIsolationAndCredentialRotationNeverExposeSecrets} 调用。
     * @param work 本用例传入的{@code work}
     * 返回 {@code transactions.execute}。
     */
    private <T> T tx(Supplier<T> work) { return transactions.execute(status -> work.get()); }

    /**
     * 组装测试数据，使用 {@code models.create}、{@code tx}。
     * 由 {@code ownerIsolationAndCredentialRotationNeverExposeSecrets}、{@code concurrentCredentialUpdatesUseRevisionCompareAndSwap} 调用。
     * @param type 本用例传入的{@code type}
     * 返回 {@code tx}。
     */
    private Map<String,Object> create(String type) { return tx(() -> models.create(owner,draft(type))); }

    /**
     * 构造 {@code Map<String, Object>} 供测试使用，使用 {@code value.put}、{@code type.equals}。
     * 由 {@code create} 调用。
     * @param type 本用例传入的{@code type}
     * 返回 {@code value}。
     */
    private static Map<String,Object> draft(String type) {
        Map<String,Object> value=new LinkedHashMap<>();
        value.put("name","test model");value.put("type",type);value.put("source","remote");value.put("provider","generic");
        value.put("protocol",type.equals("chat") ? "openai_chat" : "openai_embedding");
        value.put("model","test-model");value.put("baseUrl","http://127.0.0.1:19111/v1");
        value.put("capabilities",type.equals("chat") ? Map.of("tools",true,"json",true) : Map.of());
        value.put("compat",Map.of());value.put("runtime",Map.of());
        if (type.equals("embedding")) value.put("dimension",1024);
        return value;
    }

    /**
     * 构造 {@code Map<String, Object>} 供测试使用，使用 {@code publicModel.get}。
     * 由 {@code ownerIsolationAndCredentialRotationNeverExposeSecrets}、{@code bindingReferencesAndCalibrationFollowSemanticFingerprint} 调用。
     * @param publicModel 本用例传入的{@code publicModel}
     * 返回 {@code result}。
     */
    private Map<String,Object> edited(Map<String,Object> publicModel) {
        Map<String,Object> result=new LinkedHashMap<>(publicModel);
        for(String key : List.of("id","revision","credentialVersion","semanticFingerprint","credentialConfigured",
                "calibration","executionSupported","configurationWarning"))result.remove(key);
        result.put("expectedRevision",publicModel.get("revision"));return result;
    }

    /** 核对 {@code models.list(other).isEmpty()} 为真。调用路径经过 {@code saved.get}。 */
    @Test void ownerIsolationAndCredentialRotationNeverExposeSecrets() {
        Map<String,Object> saved=create("chat");String id=(String)saved.get("id");
        assertTrue(models.list(other).isEmpty());
        assertEquals(404,assertThrows(ModelManagementException.class,() -> models.resolveOwned(other,id)).getStatus());
        Map<String,Object> credential=tx(() -> models.credentials(owner,id,1,"local-test-secret"));
        assertEquals(2L,credential.get("revision"));assertEquals(1L,credential.get("credentialVersion"));
        assertFalse(credential.toString().contains("local-test-secret"));
        assertFalse(jdbc.queryForObject("SELECT config::text FROM managed_model WHERE id=?",String.class,id).contains("local-test-secret"));
        assertFalse(jdbc.queryForObject("SELECT credential_ciphertext FROM managed_model WHERE id=?",String.class,id).contains("local-test-secret"));
        assertEquals("local-test-secret",models.resolveOwned(owner,id).get("apiKey"));
        assertEquals(409,assertThrows(ModelManagementException.class,() -> tx(() -> models.credentials(owner,id,1,"stale-secret"))).getStatus());
        Map<String,Object> edit=edited(credential);edit.put("baseUrl","http://127.0.0.1:19112/v1");
        Map<String,Object> moved=tx(() -> models.update(owner,id,edit));
        assertEquals(false,moved.get("credentialConfigured"));assertFalse(models.resolveOwned(owner,id).containsKey("apiKey"));
        assertEquals(2L,moved.get("credentialVersion"));
    }

    /**
     * 核对 {@code models.list(owner).get(0).get("revision")} 与预期 {@code 2L} 一致。
     * 调用路径经过 {@code Executors.newFixedThreadPool}。
     */
    @Test void concurrentCredentialUpdatesUseRevisionCompareAndSwap() throws Exception {
        String id=(String)create("chat").get("id");ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<Integer> attempt=() -> {start.await();try {tx(() -> models.credentials(owner,id,1,"race-test-secret"));return 200;}catch(ModelManagementException e){return e.getStatus();}};
            Future<Integer> first=pool.submit(attempt),second=pool.submit(attempt);start.countDown();
            assertEquals(Set.of(200,409),Set.of(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS)));
            assertEquals(2L,models.list(owner).get(0).get("revision"));
        } finally {pool.shutdownNow();}
    }

    /** 断言抛出 {@code ModelManagementException}。调用路径经过 {@code saved.get}。 */
    @Test void missingMasterKeyRejectsCredentialSaveWithoutChangingTheModel() {
        Map<String,Object> saved=create("chat");String id=(String)saved.get("id");
        ModelRegistryService noKey=new ModelRegistryService(jdbc,new ObjectMapper(),new ModelCredentialCipher(""),mock(ModelRuntimeClient.class));
        assertEquals(503,assertThrows(ModelManagementException.class,() -> tx(() -> noKey.credentials(owner,id,1,"test-secret"))).getStatus());
        assertEquals(1L,models.list(owner).get(0).get("revision"));
        assertEquals(false,models.list(owner).get(0).get("credentialConfigured"));
    }

    /** 断言抛出 {@code ModelManagementException}。调用路径经过 {@code saved.get}。 */
    @Test void bindingReferencesAndCalibrationFollowSemanticFingerprint() {
        Map<String,Object> saved=create("chat");String id=(String)saved.get("id");String fp=(String)saved.get("semanticFingerprint");
        tx(() -> models.bind(owner,Map.of("bindings",Map.of("agent",id))));
        assertEquals(409,assertThrows(ModelManagementException.class,() -> tx(() -> {models.delete(owner,id,1);return null;})).getStatus());
        Map<String,Object> calibrated=tx(() -> models.calibration(owner,id,Map.of("expectedRevision",1,"semanticFingerprint",fp,"threshold",0.65,"reference","fixture-v1")));
        Map<String,Object> renamed=edited(calibrated);renamed.put("name","renamed");
        Map<String,Object> next=tx(() -> models.update(owner,id,renamed));assertNotNull(next.get("calibration"));assertEquals(fp,next.get("semanticFingerprint"));
        Map<String,Object> changed=edited(next);changed.put("model","changed-test-model");
        Map<String,Object> semantic=tx(() -> models.update(owner,id,changed));assertNull(semantic.get("calibration"));
        assertEquals(409,assertThrows(ModelManagementException.class,() -> tx(() -> models.calibration(owner,id,Map.of("expectedRevision",semantic.get("revision"),"semanticFingerprint",fp,"threshold",0.65,"reference","stale")))).getStatus());
        assertEquals(404,assertThrows(ModelManagementException.class,() -> tx(() -> models.bind(other,Map.of("bindings",Map.of("agent",id))))).getStatus());
    }

    /** 断言抛出 {@code ModelManagementException}。调用路径经过 {@code saved.get}。 */
    @Test void deletingTheLastKnowledgeBaseReleasesAnUnboundEmbeddingModel() {
        Map<String,Object> saved = create("embedding");
        String modelId = (String) saved.get("id");
        String spaceId = UUID.randomUUID().toString();
        long firstKb = IDS.incrementAndGet();
        long secondKb = IDS.incrementAndGet();
        jdbc.update("INSERT INTO embedding_space(id,owner_id,model_id,semantic_fingerprint,dimension,config_snapshot) VALUES(?,?,?,?,1024,'{}'::jsonb)",
                spaceId, owner, modelId, saved.get("semanticFingerprint"));
        for (long kb : List.of(firstKb, secondKb)) {
            jdbc.update("INSERT INTO knowledge_base(id,name,creator_id) VALUES(?,?,?)", kb, "registry fixture", owner);
            jdbc.update("INSERT INTO knowledge_index_generation(id,knowledge_base_id,space_id,state) VALUES(?,?,?,'active')",
                    UUID.randomUUID().toString(), kb, spaceId);
        }

        Runnable deleteModel = () -> tx(() -> { models.delete(owner, modelId, 1); return null; });
        jdbc.update("DELETE FROM knowledge_base WHERE id=?", firstKb);
        assertEquals(409, assertThrows(ModelManagementException.class, deleteModel::run).getStatus());

        tx(() -> models.bind(owner, Map.of("bindings", Map.of("embedding", modelId))));
        jdbc.update("DELETE FROM knowledge_base WHERE id=?", secondKb);
        assertEquals(409, assertThrows(ModelManagementException.class, deleteModel::run).getStatus());
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM embedding_space WHERE id=?", Long.class, spaceId));

        tx(() -> models.bind(owner, Map.of("bindings", Map.of())));
        assertDoesNotThrow(deleteModel::run);
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM embedding_space WHERE id=?", Long.class, spaceId));
        assertTrue(models.list(owner).isEmpty());
    }

    /** 断言抛出 {@code ModelManagementException}。调用路径经过 {@code saved.get}。 */
    @Test void referencedEmbeddingSemanticsAreImmutableButMetadataMayChange() {
        Map<String,Object> saved=create("embedding");String id=(String)saved.get("id");
        jdbc.update("INSERT INTO embedding_space(id,owner_id,model_id,semantic_fingerprint,dimension,config_snapshot) VALUES(?,?,?,?,1024,'{}'::jsonb)",UUID.randomUUID().toString(),owner,id,saved.get("semanticFingerprint"));
        Map<String,Object> semantic=edited(saved);semantic.put("model","different");
        assertEquals(409,assertThrows(ModelManagementException.class,() -> tx(() -> models.update(owner,id,semantic))).getStatus());
        Map<String,Object> metadata=edited(saved);metadata.put("runtime",Map.of("timeoutMs",100000));
        assertEquals(saved.get("semanticFingerprint"),tx(() -> models.update(owner,id,metadata)).get("semanticFingerprint"));
    }

    /** 断言抛出 {@code ModelManagementException}。调用路径经过 {@code saved.get}。 */
    @Test void boundModelCannotLoseRequiredCapabilitiesAndAnotherUsersAgentCannotSelectIt() {
        Map<String,Object> saved=create("chat");String id=(String)saved.get("id");
        tx(() -> models.bind(owner,Map.of("bindings",Map.of("agent",id))));
        Map<String,Object> invalid=edited(saved);invalid.put("capabilities",Map.of("tools",false));
        assertEquals(409,assertThrows(ModelManagementException.class,() -> tx(() -> models.update(owner,id,invalid))).getStatus());
        assertEquals(404,assertThrows(ModelManagementException.class,() -> tx(() -> {models.validateAgentModel(other,id);return null;})).getStatus());
        assertEquals(1L,models.list(owner).get(0).get("revision"));
    }
}
