/* 管理模型档案、用途绑定、凭证与执行配置的解析。 */

package com.pony.knowledge.modelmanagement;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** User overrides and immutable deployment defaults. Only internal execution resolution exposes secrets. */
@Service
public class ModelRegistryService {
    private static final String DEPLOYMENT_CHAT_ID = "00000000-0000-0000-0000-000000000101";
    private static final String DEPLOYMENT_EMBEDDING_ID = "00000000-0000-0000-0000-000000000102";
    private static final String DEPLOYMENT_RERANK_ID = "00000000-0000-0000-0000-000000000103";
    private static final Map<String,String> PURPOSES = Map.of("generate","chat","rewrite","chat","agent","chat",
            "summarize","chat","judge","chat","evidence_support","chat","embedding","embedding","rerank","rerank");
    private static final Map<String,String> DEFAULT_MODEL_BY_PURPOSE = Map.of(
            "generate", DEPLOYMENT_CHAT_ID, "rewrite", DEPLOYMENT_CHAT_ID, "agent", DEPLOYMENT_CHAT_ID,
            "summarize", DEPLOYMENT_CHAT_ID, "judge", DEPLOYMENT_CHAT_ID, "evidence_support", DEPLOYMENT_CHAT_ID,
            "embedding", DEPLOYMENT_EMBEDDING_ID, "rerank", DEPLOYMENT_RERANK_ID);
    private static final Map<String,String> PROTOCOLS = Map.of("openai_chat","chat",
            "openai_embedding","embedding","cohere_rerank","rerank","dashscope_rerank","rerank");
    private static final Set<String> FIELDS = Set.of("name","type","source","provider","protocol","model","baseUrl",
            "dimension","contextWindow","maxOutputTokens","capabilities","compat","runtime");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ModelCredentialCipher cipher;
    private final ModelRuntimeClient runtime;
    private final boolean deploymentDefaultsEnabled;
    private final String ollamaBaseUrl;
    private final String rerankBaseUrl;
    private final String rerankApiKey;
    /**
     * 用该入口的默认选项初始化 ModelRegistryService 实例。
     * 上游为 Spring 组件装配。
     *
     * @param jdbc 负责数据库 SQL 的 JDBC 组件
     * @param mapper 操作本组件所需的数据记录的组件
     * @param cipher 本次操作使用的 ModelCredentialCipher 组件或记录
     * @param runtime 本次操作使用的 ModelRuntimeClient 组件或记录
     */
    public ModelRegistryService(JdbcTemplate jdbc, ObjectMapper mapper, ModelCredentialCipher cipher, ModelRuntimeClient runtime) {
        this(jdbc,mapper,cipher,runtime,"http://localhost:11434/v1","http://localhost:8091/v1","",false);
    }

    /**
     * 用该入口的默认选项初始化 ModelRegistryService 实例。
     * 上游为 Spring 组件装配。
     *
     * @param jdbc 负责数据库 SQL 的 JDBC 组件
     * @param mapper 操作本组件所需的数据记录的组件
     * @param cipher 本次操作使用的 ModelCredentialCipher 组件或记录
     * @param runtime 本次操作使用的 ModelRuntimeClient 组件或记录
     * @param ollamaBaseUrl 下游接口地址
     * @param rerankBaseUrl 下游接口地址
     * @param rerankApiKey 初始化或更新同名字段的值
     */
    @Autowired
    public ModelRegistryService(JdbcTemplate jdbc, ObjectMapper mapper, ModelCredentialCipher cipher, ModelRuntimeClient runtime,
                                @Value("${model-management.defaults.ollama-base-url:http://localhost:11434/v1}") String ollamaBaseUrl,
                                @Value("${model-management.defaults.rerank-base-url:http://localhost:8091/v1}") String rerankBaseUrl,
                                @Value("${model-management.defaults.rerank-api-key:}") String rerankApiKey) {
        this(jdbc,mapper,cipher,runtime,ollamaBaseUrl,rerankBaseUrl,rerankApiKey,true);
    }

    /**
     * 保存管理模型档案、用途绑定、凭证与执行配置的解析所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param jdbc 负责数据库 SQL 的 JDBC 组件
     * @param mapper 操作本组件所需的数据记录的组件
     * @param cipher 本次操作使用的 ModelCredentialCipher 组件或记录
     * @param runtime 本次操作使用的 ModelRuntimeClient 组件或记录
     * @param ollamaBaseUrl 下游接口地址
     * @param rerankBaseUrl 下游接口地址
     * @param rerankApiKey 初始化或更新同名字段的值
     * @param deploymentDefaultsEnabled 是否启用相关处理
     */
    private ModelRegistryService(JdbcTemplate jdbc,ObjectMapper mapper,ModelCredentialCipher cipher,ModelRuntimeClient runtime,
                                 String ollamaBaseUrl,String rerankBaseUrl,String rerankApiKey,boolean deploymentDefaultsEnabled) {
        this.jdbc=jdbc; this.mapper=mapper; this.cipher=cipher; this.runtime=runtime;
        this.deploymentDefaultsEnabled=deploymentDefaultsEnabled;
        this.ollamaBaseUrl=ollamaBaseUrl;
        this.rerankBaseUrl=rerankBaseUrl;
        this.rerankApiKey=rerankApiKey == null ? "" : rerankApiKey.trim();
    }

    /**
     * 按部署配置更新共享默认模型档案及重排凭证。
     * 上游为 DeploymentModelInitializer.run。
     * 下游为 ModelRegistryService.deploymentModelConfig、ModelRegistryService.fingerprint。
     */
    @Transactional
    public void initializeDeploymentDefaults() {
        if (!deploymentDefaultsEnabled) return;
        Map<String,String> ids=Map.of("chat",DEPLOYMENT_CHAT_ID,"embedding",DEPLOYMENT_EMBEDDING_ID,"rerank",DEPLOYMENT_RERANK_ID);
        for (Map.Entry<String,String> item:ids.entrySet()) {
            List<Row> existing=jdbc.query("SELECT * FROM managed_model WHERE id=?",this::row,item.getValue());
            if (!existing.isEmpty()) {
                if (existing.get(0).owner()!=null) throw new IllegalStateException("Reserved deployment model ID is owned by a user");
                continue;
            }
            Map<String,Object> config=deploymentModelConfig(item.getKey(), item.getKey());
            jdbc.update("INSERT INTO managed_model(id,owner_id,revision,credential_version,semantic_fingerprint,config) VALUES(?,NULL,1,0,?,?::jsonb)",
                    item.getValue(),fingerprint(config),json(config));
        }
    }

    /**
     * 查询当前账号拥有的非默认模型并转换成公开视图。
     * 上游为 ModelManagementController.list。
     *
     * @param owner 指明数据归属的账号 ID
     * @return 与过滤条件匹配的 List&lt;Map&lt;String, Object&gt;&gt; 数据集合
     */
    public List<Map<String,Object>> list(long owner) {
        return jdbc.query("SELECT * FROM managed_model WHERE owner_id=? ORDER BY updated_at DESC,id",this::row,owner)
                .stream().filter(this::supportsExecution).map(this::publicView).toList();
    }
    /**
     * 校验草稿配置并创建归属当前账号的新模型。
     * 上游为 ModelManagementController.create。
     * 下游为 ModelRuntimeClient.resolve。
     *
     * @param owner 指明数据归属的账号 ID
     * @param draft 含当前操作所需属性的映射
     * @return 由本方法收集或转换的键值映射
     */
    @Transactional
    public Map<String,Object> create(long owner, Map<String,Object> draft) {
        Map<String,Object> config=normalize(draft);
        runtime.resolve(config);
        String id=UUID.randomUUID().toString(), fingerprint=fingerprint(config);
        jdbc.update("INSERT INTO managed_model(id,owner_id,revision,credential_version,semantic_fingerprint,config) VALUES (?,?,1,0,?,?::jsonb)",
                id,owner,fingerprint,json(config));
        return publicView(requireOwned(owner,id,false));
    }
    /**
     * 核对模型所有者与版本后更新配置和语义指纹。
     * 上游为 ModelManagementController.update。
     * 下游为 ModelRuntimeClient.resolve、ModelManagementException.conflict。
     *
     * @param owner 指明数据归属的账号 ID
     * @param id 模型档案 ID
     * @param submitted 含当前操作所需属性的映射
     * @return 由本方法收集或转换的键值映射
     */
    @Transactional
    public Map<String,Object> update(long owner,String id,Map<String,Object> submitted) {
        Map<String,Object> draft=new LinkedHashMap<>(submitted);
        long expected=expected(draft.remove("expectedRevision"));

        // 写前锁住模型记录并核对请求的预期版本，让并发编辑能够发现旧版本。
        Row old=requireActiveOwned(owner,id,true); requireRevision(old,expected);
        Map<String,Object> config=normalize(draft); runtime.resolve(config);
        String fingerprint=fingerprint(config);
        if (!fingerprint.equals(old.fingerprint()) && hasSpaceReference(id))
            throw ModelManagementException.conflict("该模型已用于向量空间，请复制为新模型后显式重建知识库索引");
        for (String purpose : jdbc.queryForList("SELECT purpose FROM model_purpose_binding WHERE model_id=?",String.class,id))
            validateReference(config,purpose,PURPOSES.get(purpose));
        Integer agentReferences=jdbc.queryForObject("SELECT count(*) FROM agent_profile WHERE chat_model_id=?",Integer.class,id);
        if (agentReferences != null && agentReferences>0) validateReference(config,"agent","chat");

        // 模型地址或提供方变化时清除旧访问凭证，避免将原凭证发送到新的服务端点。
        boolean endpointChanged=!Objects.equals(config.get("baseUrl"),old.config().get("baseUrl"))
                || !Objects.equals(config.get("provider"),old.config().get("provider"));
        config.put("calibration",fingerprint.equals(old.fingerprint()) ? old.config().get("calibration") : null);
        jdbc.update("UPDATE managed_model SET config=?::jsonb,semantic_fingerprint=?,revision=revision+1,credential_ciphertext=?,credential_version=credential_version+?,updated_at=now() WHERE id=? AND owner_id=? AND revision=?",
                json(config),fingerprint,endpointChanged ? null : old.encrypted(),endpointChanged ? 1 : 0,id,owner,expected);
        return publicView(requireOwned(owner,id,false));
    }
    /**
     * 确认模型可删除并断开可清除的关联。
     * 上游为 ModelManagementController.delete。
     * 下游为 ModelManagementException.conflict。
     *
     * @param owner 指明数据归属的账号 ID
     * @param id 模型档案 ID
     * @param expected 调用方依赖的预期状态、值或版本
     */
    @Transactional
    public void delete(long owner,String id,long expected) {
        Row row=requireActiveOwned(owner,id,true); requireRevision(row,expected);
        Integer bindings=jdbc.queryForObject("SELECT count(*) FROM model_purpose_binding WHERE model_id=?",Integer.class,id);
        if (bindings != null && bindings > 0)
            throw ModelManagementException.conflict("模型仍有用途绑定，请在设置的「用途绑定」中解除后重试");
        Integer profiles=jdbc.queryForObject("SELECT count(*) FROM agent_profile WHERE chat_model_id=?",Integer.class,id);
        if (profiles != null && profiles > 0)
            throw ModelManagementException.conflict("模型仍被 Agent 配置引用，请更换对应 Agent 的聊天模型后重试");

        // Creating an index space takes the same model row lock. Only spaces without generations can be removed.
        jdbc.update("""
                DELETE FROM embedding_space s WHERE s.model_id=?
                  AND NOT EXISTS (SELECT 1 FROM knowledge_index_generation g WHERE g.space_id=s.id)
                """, id);
        if (hasSpaceReference(id))
            throw ModelManagementException.conflict("模型仍被知识库索引引用，不能删除");
        jdbc.update("DELETE FROM managed_model WHERE id=? AND owner_id=? AND revision=?",id,owner,expected);
    }
    /**
     * 按照预期版本写入或清除加密后的模型凭证。
     * 上游为 ModelManagementController.credential、ModelManagementController.clearCredential。
     * 下游为 ModelManagementException.badRequest、ModelCredentialCipher.encrypt。
     *
     * @param owner 指明数据归属的账号 ID
     * @param id 模型档案 ID
     * @param expected 调用方依赖的预期状态、值或版本
     * @param secret 从配置或调用凭据提供的密钥内容
     * @return 由本方法收集或转换的键值映射
     */
    @Transactional
    public Map<String,Object> credentials(long owner,String id,long expected,String secret) {
        Row row=requireActiveOwned(owner,id,true); requireRevision(row,expected);
        if (secret != null && (secret.isBlank() || secret.length()>16000))
            throw ModelManagementException.badRequest("API Key 不能为空或过长");
        String encrypted=secret == null ? null : cipher.encrypt(secret,owner,id);
        jdbc.update("UPDATE managed_model SET credential_ciphertext=?,credential_version=credential_version+1,revision=revision+1,updated_at=now() WHERE id=? AND owner_id=? AND revision=?",
                encrypted,id,owner,expected);
        return publicView(requireOwned(owner,id,false));
    }
    /**
     * 更新指定用途模型的阈值校准及其版本。
     * 上游为 ModelManagementController.calibration。
     * 下游为 ModelManagementException.badRequest、ModelManagementException.conflict。
     *
     * @param owner 指明数据归属的账号 ID
     * @param id 模型档案 ID
     * @param input 含当前操作所需属性的映射
     * @return 由本方法收集或转换的键值映射
     */
    @Transactional
    public Map<String,Object> calibration(long owner,String id,Map<String,Object> input) {
        if (!input.keySet().equals(Set.of("expectedRevision","semanticFingerprint","threshold","reference")))
            throw ModelManagementException.badRequest("校准请求字段无效");
        Row row=requireActiveOwned(owner,id,true); requireRevision(row,expected(input.get("expectedRevision")));
        if (!row.fingerprint().equals(input.get("semanticFingerprint")))
            throw ModelManagementException.conflict("模型语义已变化，请重新校准当前模型");
        Object raw=input.get("threshold");
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue()<0 || number.doubleValue()>1)
            throw ModelManagementException.badRequest("校准阈值必须在 0 到 1 之间");
        String reference=text(input.get("reference"),"reference",1000,true);
        Map<String,Object> config=new LinkedHashMap<>(row.config());
        config.put("calibration",Map.of("semanticFingerprint",row.fingerprint(),"threshold",number.doubleValue(),"reference",reference));
        jdbc.update("UPDATE managed_model SET config=?::jsonb,revision=revision+1,updated_at=now() WHERE id=? AND owner_id=?",json(config),id,owner);
        return publicView(requireOwned(owner,id,false));
    }
    /**
     * 返回当前账号各用途绑定和有效部署默认项。
     * 上游为 ModelManagementController.bindings、ModelRegistryService.bind。
     *
     * @param owner 指明数据归属的账号 ID
     * @return 由本方法收集或转换的键值映射
     */
    public Map<String,Object> bindings(long owner) {
        Map<String,String> bindings=new LinkedHashMap<>();
        jdbc.query("SELECT purpose,model_id FROM model_purpose_binding WHERE owner_id=? ORDER BY purpose",rs->{bindings.put(rs.getString(1),rs.getString(2));},owner);
        return Map.of("bindings",bindings);
    }

    /**
     * 构建能安全展示给调用方的共享默认模型视图。
     * 上游为 ModelManagementController.defaults。
     * 下游为 ModelRegistryService.deploymentDefaultModelId、ModelRegistryService.deploymentDefaultView。
     *
     * @return 由本方法收集或转换的键值映射
     */
    public Map<String,Object> deploymentDefaults() {
        Map<String,Object> result=new LinkedHashMap<>();
        for (String purpose:DEFAULT_MODEL_BY_PURPOSE.keySet()) {
            String id=deploymentDefaultModelId(purpose);
            Map<String,Object> model=id == null ? null : deploymentDefaultView(purpose);
            result.put(purpose,model);
        }
        return Map.of("defaults",result);
    }

    /**
     * 在部署默认值已开启时取得指定用途的内建模型 ID。
     * 上游为 KnowledgeIndexService.embeddingExecution、ModelRegistryService.deploymentDefaults。
     *
     * @param purpose 需要解析或配置的模型用途
     * @return 启用的对应默认模型 ID，未配置时为空
     */
    public String deploymentDefaultModelId(String purpose) {
        if (!deploymentDefaultsEnabled || !PURPOSES.containsKey(purpose)) return null;
        return DEFAULT_MODEL_BY_PURPOSE.get(purpose);
    }

    /**
     * 判断给定模型 ID 是否属于保留的部署默认模型。
     * 上游为 KnowledgeIndexService.ensureSpace、ModelRegistryService.resolveForPurpose。
     * 下游为 ModelRegistryService.deploymentDefaultModelId。
     *
     * @param purpose 需要解析或配置的模型用途
     * @param id 模型档案 ID
     * @return 条件是否成立（判断给定模型 ID 是否属于保留的部署默认模型）
     */
    public boolean isDeploymentDefaultModel(String purpose,String id) {
        return id != null && id.equals(deploymentDefaultModelId(purpose));
    }

    /**
     * 优先使用用户为用途绑定的模型，空缺时使用部署默认值。
     * 上游为 AgentService.executeTurn、ModelRegistryService.agentExecution。
     * 下游为 ModelRegistryService.boundModelId、ModelRegistryService.deploymentDefaultModelId。
     *
     * @param owner 指明数据归属的账号 ID
     * @param purpose 需要解析或配置的模型用途
     * @return 用户绑定模型 ID 或同用途部署默认模型 ID
     */
    public String effectiveModelId(long owner,String purpose) {
        String personal=boundModelId(owner,purpose);
        return personal == null ? deploymentDefaultModelId(purpose) : personal;
    }

    /**
     * 根据用户和用途解析允许执行的模型配置。
     * 上游为 GatewaySummaryService.summarize、KnowledgeIndexService.resolveSpaceModel。
     * 下游为 ModelManagementException.notFound、ModelManagementException.unavailable。
     *
     * @param owner 指明数据归属的账号 ID
     * @param purpose 需要解析或配置的模型用途
     * @param id 模型档案 ID
     * @return 由本方法收集或转换的键值映射
     */
    public Map<String,Object> resolveForPurpose(long owner,String purpose,String id) {
        if (!PURPOSES.containsKey(purpose) || id == null) throw ModelManagementException.notFound();
        Map<String,Object> result;
        if (isDeploymentDefaultModel(purpose,id)) result=resolveDeploymentDefault(purpose);
        else result=resolveOwned(owner,id);

        try { validatePurpose(result,purpose,PURPOSES.get(purpose)); }
        catch (ModelManagementException invalid) {
            throw ModelManagementException.unavailable("当前用途的模型配置无效，请检查部署默认或个人模型绑定");
        }
        return result;
    }
    /**
     * 检查模型用途与访问权后更新用户的用途绑定。
     * 上游为 ModelManagementController.bind。
     * 下游为 ModelManagementException.badRequest。
     *
     * @param owner 指明数据归属的账号 ID
     * @param submitted 含当前操作所需属性的映射
     * @return 由本方法收集或转换的键值映射
     */
    @Transactional
    public Map<String,Object> bind(long owner,Map<String,Object> submitted) {
        if (!submitted.keySet().equals(Set.of("bindings")) || !(submitted.get("bindings") instanceof Map<?,?> bindings))
            throw ModelManagementException.badRequest("用途绑定必须提供 bindings 对象");
        // Lock all mutations belonging to this user, including an initially empty binding set.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)",Object.class,-owner);
        Map<String,String> accepted=new LinkedHashMap<>();
        bindings.forEach((key,value)->{
            String purpose=String.valueOf(key),id=text(value,"modelId",36,true);
            String required=PURPOSES.get(purpose);
            if (required == null) throw ModelManagementException.badRequest("未知模型用途");
            Row model=requireActiveOwned(owner,id,true); validatePurpose(model.config(),purpose,required);
            accepted.put(purpose,id);
        });
        jdbc.update("DELETE FROM model_purpose_binding WHERE owner_id=?",owner);
        accepted.forEach((purpose,id)->jdbc.update("INSERT INTO model_purpose_binding(owner_id,purpose,model_id) VALUES(?,?,?)",owner,purpose,id));
        return bindings(owner);
    }
    /**
     * 查找用户在特定用途上显式绑定的模型 ID。
     * 上游为 KnowledgeIndexService.embeddingExecution、ModelRegistryService.effectiveModelId。
     *
     * @param owner 指明数据归属的账号 ID
     * @param purpose 需要解析或配置的模型用途
     * @return 显式绑定的模型 ID，无绑定时为空
     */
    public String boundModelId(long owner,String purpose) {
        List<String> ids=jdbc.queryForList("SELECT model_id FROM model_purpose_binding WHERE owner_id=? AND purpose=?",String.class,owner,purpose);
        return ids.isEmpty() ? null : ids.get(0);
    }
    /**
     * 解析账号拥有的模型及下游执行所需的配置。
     * 上游为 KnowledgeIndexService.ensureSpace、ModelRegistryService.resolveForPurpose。
     * 下游为 ModelManagementException.unavailable。
     *
     * @param owner 指明数据归属的账号 ID
     * @param id 模型档案 ID
     * @return 由本方法收集或转换的键值映射
     */
    public Map<String,Object> resolveOwned(long owner,String id) {
        Row row=requireOwned(owner,id,false);
        if (!supportsExecution(row))
            throw ModelManagementException.unavailable("当前版本不支持该模型协议，请重新配置当前版本模型");
        return executionSnapshot(row);
    }

    /**
     * 构建发往 AI 的模型执行快照，附带用途和当前调用预算。
     * 上游为 ModelRegistryService.resolveOwned、ModelRegistryService.testSaved。
     * 下游为 ModelCredentialCipher.decrypt、Row.encrypted。
     *
     * @param row 本次操作使用的 Row 组件或记录
     * @return 供 AI 执行所需的模型协议、档案版本和可用凭证
     */
    private Map<String,Object> executionSnapshot(Row row) {
        Map<String,Object> result=publicView(row); result.remove("credentialConfigured");
        result.remove("executionSupported"); result.remove("configurationWarning");
        String key=cipher.decrypt(row.encrypted(),row.owner(),row.id());
        if (key != null) result.put("apiKey",key);
        return result;
    }
    /**
     * 按所需用途解析用户有效模型并装配统一执行快照。
     * 上游为 ModelRegistryService.agentExecution、AiServiceClient.evaluateGenerate。
     * 下游为 ModelManagementException.badRequest。
     *
     * @param owner 指明数据归属的账号 ID
     * @param budgetMs 本次调用的可用预算（毫秒）
     * @param purposes 本次请求依赖的模型用途集合
     * @return 按用途列出的已解析模型快照及实际执行预算
     */
    public Map<String,Object> executionForUser(long owner,int budgetMs,Set<String> purposes) {
        Map<String,Object> bindings=new LinkedHashMap<>();

        for (String purpose:purposes) {
            if (!PURPOSES.containsKey(purpose)) throw ModelManagementException.badRequest("未知模型用途");
            String id=boundModelId(owner,purpose);
            if (id != null) {
                try { bindings.put(purpose,resolveOwned(owner,id)); }
                catch (ModelManagementException invalid) {
                    // An unused purpose must not prevent a request from running. An unusable
                    // credential omits the whole binding; Python fails only if that purpose executes.
                    if (invalid.getStatus()!=503) throw invalid;
                }
            } else if (deploymentDefaultModelId(purpose) != null) {
                bindings.put(purpose,resolveDeploymentDefault(purpose));
            }
        }
        Map<String,Object> execution=new LinkedHashMap<>(); execution.put("schemaVersion",1);
        execution.put("budgetMs",budgetMs); execution.put("bindings",bindings); execution.put("indexScopes",List.of());
        return execution;
    }
    /**
     * 为 Agent 执行构建所选模型及工具调用所需的快照。
     * 上游为 AgentService.runTurn。
     * 下游为 ModelManagementException.unavailable。
     *
     * @param owner 指明数据归属的账号 ID
     * @param overrideModelId 要定位的关联标识
     * @return 供 Agent 推理与证据工具共享的模型执行快照
     */
    public Map<String,Object> agentExecution(long owner,String overrideModelId) {
        Map<String,Object> execution=executionForUser(owner,30000,Set.of());
        @SuppressWarnings("unchecked") Map<String,Object> selected=(Map<String,Object>) execution.get("bindings");
        String id=overrideModelId == null ? effectiveModelId(owner,"agent") : overrideModelId;
        if (id != null) {
            if (isDeploymentDefaultModel("agent",id)) {
                selected.put("agent",resolveDeploymentDefault("agent"));
            } else {
                Row row=requireOwned(owner,id,false);
                if (!supportsExecution(row))
                    throw ModelManagementException.unavailable("Agent 模型使用已停用协议，请配置支持原生工具调用的 OpenAI 兼容模型");
                validatePurpose(row.config(),"agent","chat");
                selected.put("agent",resolveOwned(owner,id));
            }
        }
        return execution;
    }
    /**
     * 解析 Agent 模型用于会话关联的版本和语义身份。
     * 上游为 AgentService.runTurn、AgentSessionService.currentPolicy。
     * 下游为 ModelRegistryService.effectiveModelId、ModelRegistryService.isDeploymentDefaultModel。
     *
     * @param owner 指明数据归属的账号 ID
     * @param overrideModelId 要定位的关联标识
     * @return 用于识别 Agent 实际模型及配置变化的字符串
     */
    public String agentIdentity(long owner,String overrideModelId) {
        String id=overrideModelId == null ? effectiveModelId(owner,"agent") : overrideModelId;
        if (id == null) return "unconfigured";
        if (isDeploymentDefaultModel("agent",id)) {
            Map<String,Object> model=deploymentDefaultView("agent");
            return "deployment-default:"+id+":"+model.get("semanticFingerprint");
        }
        Row row=requireOwned(owner,id,false);
        return id+":"+row.fingerprint();
    }
    /**
     * 校验 Agent 请求的语言模型是否允许工具使用。
     * 上游为 AgentProfileService.applyModel。
     * 下游为 ModelRegistryService.validatePurpose、ModelRegistryService.requireOwned。
     *
     * @param owner 指明数据归属的账号 ID
     * @param id 模型档案 ID
     */
    @Transactional
    public void validateAgentModel(long owner,String id) {
        if (id != null) validatePurpose(requireOwned(owner,id,true).config(),"agent","chat");
    }
    /**
     * 规范化模型草稿后请求 AI 解析实际协议与能力。
     * 上游为 ModelManagementController.resolve。
     * 下游为 ModelRuntimeClient.resolve。
     *
     * @param input 含当前操作所需属性的映射
     * @return 由本方法收集或转换的键值映射
     */
    public Map<String,Object> preview(Map<String,Object> input) { return runtime.resolve(normalize(input)); }
    /**
     * 使用尚未保存的模型设置执行连通性测试。
     * 上游为 ModelManagementController.test。
     * 下游为 ModelRuntimeClient.test。
     *
     * @param input 含当前操作所需属性的映射
     * @param key 本方法计算或判断使用的 key 值
     * @param checks 本次操作所用的 Object 数据
     * @return 由本方法收集或转换的键值映射
     */
    public Map<String,Object> testDraft(Map<String,Object> input,String key,Object checks) {
        Map<String,Object> model=normalize(input);
        if (key != null && !key.isBlank()) model.put("apiKey",key);
        return runtime.test(model,checks);
    }
    /**
     * 使用当前用户有权使用的已保存配置执行连通性测试。
     * 上游为 ModelManagementController.testSaved。
     * 下游为 ModelRuntimeClient.test。
     *
     * @param owner 指明数据归属的账号 ID
     * @param id 模型档案 ID
     * @param checks 本次操作所用的 Object 数据
     * @return 由本方法收集或转换的键值映射
     */
    public Map<String,Object> testSaved(long owner,String id,Object checks) {
        return runtime.test(executionSnapshot(requireActiveOwned(owner,id,false)),checks);
    }
    /**
     * 从模型运行端取回可供注册选择的提供方列表。
     * 上游为 ModelManagementController.providers。
     * 下游为 ModelRuntimeClient.providers。
     *
     * @return 由本方法收集或转换的键值映射
     */
    public Map<String,Object> providers() { return runtime.providers(); }

    /**
     * 构造可公开的部署模型配置视图。
     * 上游为 ModelRegistryService.deploymentDefaults、ModelRegistryService.agentIdentity。
     * 下游为 ModelRegistryService.deploymentDefaultModelId、ModelRegistryService.deploymentModelConfig。
     *
     * @param purpose 需要解析或配置的模型用途
     * @return 由本方法收集或转换的键值映射
     */
    private Map<String,Object> deploymentDefaultView(String purpose) {
        String id=deploymentDefaultModelId(purpose);
        if (id == null) return null;
        List<Row> rows=jdbc.query("SELECT * FROM managed_model WHERE id=? AND owner_id IS NULL",this::row,id);
        if (rows.isEmpty()) return null;
        Row row=rows.get(0);
        Map<String,Object> config=deploymentModelConfig(PURPOSES.get(purpose), purpose);
        validatePurpose(config,purpose,PURPOSES.get(purpose));
        Map<String,Object> result=new LinkedHashMap<>(config);
        result.put("id",id); result.put("revision",row.revision()); result.put("credentialVersion",row.credentialVersion());
        result.put("semanticFingerprint",fingerprint(config));
        boolean credentialRequired="rerank".equals(config.get("type"));
        result.put("credentialConfigured",credentialRequired && !rerankApiKey.isBlank());
        result.put("credentialRequired",credentialRequired);
        result.put("executionSupported",true); result.put("deploymentDefault",true);
        if (credentialRequired && rerankApiKey.isBlank())
            result.put("configurationWarning","部署默认重排服务需要配置 MODEL_DEFAULT_RERANK_API_KEY");
        return result;
    }

    /**
     * 解析共享默认模型的实际配置和凭证。
     * 上游为 ModelRegistryService.resolveForPurpose、ModelRegistryService.executionForUser。
     * 下游为 ModelManagementException.unavailable。
     *
     * @param purpose 需要解析或配置的模型用途
     * @return 由本方法收集或转换的键值映射
     */
    private Map<String,Object> resolveDeploymentDefault(String purpose) {
        Map<String,Object> result=deploymentDefaultView(purpose);
        if (result == null) throw ModelManagementException.unavailable("部署默认模型记录未初始化，请检查 Flyway V19 和模型默认开关");
        result.remove("credentialConfigured"); result.remove("credentialRequired");
        result.remove("deploymentDefault"); result.remove("executionSupported"); result.remove("configurationWarning");

        if ("rerank".equals(purpose)) {
            if (rerankApiKey.isBlank()) throw ModelManagementException.unavailable("部署默认重排服务未配置 API Key，请设置 MODEL_DEFAULT_RERANK_API_KEY");
            result.put("apiKey",rerankApiKey);
        }
        return result;
    }

    /**
     * 组装默认用途模型的协议、预算和运行限制。
     * 上游为 ModelRegistryService.initializeDeploymentDefaults、ModelRegistryService.deploymentDefaultView。
     * 下游为 ModelRuntimeClient.put、ModelManagementException.badRequest。
     *
     * @param family 本方法计算或判断使用的 family 值
     * @param purpose 需要解析或配置的模型用途
     * @return 由本方法收集或转换的键值映射
     */
    private Map<String,Object> deploymentModelConfig(String family, String purpose) {
        Map<String,Object> draft=new LinkedHashMap<>();
        Map<String,Object> runtime=new LinkedHashMap<>();
        int timeoutMs = switch (purpose) {
            case "generate" -> 180_000;
            case "embedding" -> 120_000;
            case "rerank", "evidence_support" -> 60_000;
            default -> 20_000;
        };
        runtime.put("timeoutMs", timeoutMs);
        runtime.put("retries",0); runtime.put("backoffMs",500); runtime.put("maxConcurrency",1); runtime.put("resourceGroup","local-gpu");
        draft.put("source","local"); draft.put("runtime",runtime);

        if ("chat".equals(family)) {
            draft.put("name","EKIP 部署默认 Chat / Agent · Qwen3 4B / 8K"); draft.put("type","chat");
            draft.put("provider","ollama"); draft.put("protocol","openai_chat");
            draft.put("model","ekip-qwen3:4b-instruct-8k"); draft.put("baseUrl",ollamaBaseUrl);
            draft.put("dimension",null); draft.put("contextWindow",8192);
            draft.put("maxOutputTokens", "generate".equals(purpose) ? 1024 : 512);
            draft.put("capabilities",Map.of("tools",true,"streaming",true,"json",true,"thinking",false));
            draft.put("compat",Map.of());
        } else if ("embedding".equals(family)) {
            draft.put("name","EKIP 部署默认向量 · Qwen3 Embedding 0.6B"); draft.put("type","embedding");
            draft.put("provider","ollama"); draft.put("protocol","openai_embedding");
            draft.put("model","ekip-qwen3-embedding:0.6b-4k"); draft.put("baseUrl",ollamaBaseUrl);
            draft.put("dimension",1024); draft.put("contextWindow",4096); draft.put("maxOutputTokens",null);
            draft.put("capabilities",Map.of()); draft.put("compat",Map.of());
        } else if ("rerank".equals(family)) {
            draft.put("name","EKIP 部署默认重排 · Qwen3 Reranker 0.6B"); draft.put("type","rerank");
            draft.put("provider","generic"); draft.put("protocol","cohere_rerank");
            draft.put("model","ekip-qwen3-reranker-0.6b"); draft.put("baseUrl",rerankBaseUrl);
            draft.put("dimension",null); draft.put("contextWindow",2048); draft.put("maxOutputTokens",null);
            draft.put("capabilities",Map.of()); draft.put("compat",Map.of("scoreScale","probability","maxBatchSize",20));
        } else {
            throw ModelManagementException.badRequest("未知部署默认模型类型");
        }
        return normalize(draft);
    }

    /**
     * 对请求配置统一清洗并校验字段、协议、端点和模型能力。
     * 上游为 ModelRegistryService.create、ModelRegistryService.update。
     * 下游为 ModelManagementException.badRequest、ModelRuntimeClient.put。
     *
     * @param input 含当前操作所需属性的映射
     * @return 由本方法收集或转换的键值映射
     */
    private Map<String,Object> normalize(Map<String,Object> input) {
        if (input == null || !FIELDS.containsAll(input.keySet())) throw ModelManagementException.badRequest("模型包含未知字段或凭证字段");
        Map<String,Object> config=new LinkedHashMap<>();
        String type=text(input.get("type"),"type",20,true),protocol=text(input.get("protocol"),"protocol",40,true);
        if (!type.equals(PROTOCOLS.get(protocol))) throw ModelManagementException.badRequest("模型类型与协议不匹配");
        String source=input.containsKey("source") ? text(input.get("source"),"source",20,true) : "remote";
        if (!Set.of("local","remote").contains(source)) throw ModelManagementException.badRequest("模型来源无效");
        String provider=input.containsKey("provider") ? text(input.get("provider"),"provider",30,true) : "generic";
        if (!Set.of("generic","openai","aliyun","cohere","ollama").contains(provider)) throw ModelManagementException.badRequest("模型厂商无效");
        config.put("name",text(input.get("name"),"name",200,true)); config.put("type",type);
        config.put("source",source); config.put("provider",provider); config.put("protocol",protocol);
        config.put("model",text(input.get("model"),"model",256,true));
        String url=text(input.get("baseUrl"),"baseUrl",2048,false); validateUrl(url); config.put("baseUrl",url);
        config.put("dimension",nullableInt(input.get("dimension"),1,65536,"dimension"));
        if ("embedding".equals(type) && config.get("dimension") == null) throw ModelManagementException.badRequest("向量模型必须明确输出维度");
        config.put("contextWindow",nullableInt(input.get("contextWindow"),128,10000000,"contextWindow"));
        config.put("maxOutputTokens",nullableInt(input.get("maxOutputTokens"),1,1000000,"maxOutputTokens"));
        if (config.get("contextWindow") instanceof Integer window && config.get("maxOutputTokens") instanceof Integer output && output>=window)
            throw ModelManagementException.badRequest("最大输出必须小于上下文窗口");
        Map<String,Object> capabilities=map(input.get("capabilities"));
        if (!Set.of("tools","streaming","json","thinking").containsAll(capabilities.keySet())
                || capabilities.values().stream().anyMatch(v->!(v instanceof Boolean))) throw ModelManagementException.badRequest("模型能力字段无效");
        if (!"chat".equals(type) && capabilities.values().stream().anyMatch(Boolean.TRUE::equals)) throw ModelManagementException.badRequest("非聊天模型不能启用聊天能力");
        for (String capability : List.of("tools","streaming","json","thinking")) capabilities.putIfAbsent(capability,false);
        config.put("capabilities",capabilities); config.put("compat",map(input.get("compat")));
        Map<String,Object> submitted=map(input.get("runtime")), runtime=new LinkedHashMap<>();
        if (!Set.of("timeoutMs","retries","backoffMs","maxConcurrency","resourceGroup").containsAll(submitted.keySet())) throw ModelManagementException.badRequest("未知运行配置");
        runtime.put("timeoutMs",integer(submitted.getOrDefault("timeoutMs","embedding".equals(type)?120000:20000),100,600000,"timeoutMs"));
        runtime.put("retries",integer(submitted.getOrDefault("retries",0),0,3,"retries"));
        runtime.put("backoffMs",integer(submitted.getOrDefault("backoffMs",500),0,10000,"backoffMs"));
        runtime.put("maxConcurrency",integer(submitted.getOrDefault("maxConcurrency",1),1,64,"maxConcurrency"));
        runtime.put("resourceGroup",text(submitted.getOrDefault("resourceGroup","local".equals(source)?"local-gpu":""),"resourceGroup",128,false));
        config.put("runtime",runtime); config.put("calibration",null);
        return config;
    }
    /**
     * 核对所选模型类型和能力能否承担指定用途。
     * 上游为 ModelRegistryService.resolveForPurpose、ModelRegistryService.bind。
     * 下游为 ModelManagementException.badRequest。
     *
     * @param config 含当前操作所需属性的映射
     * @param purpose 需要解析或配置的模型用途
     * @param required 本方法计算或判断使用的 required 值
     */
    private void validatePurpose(Map<String,Object> config,String purpose,String required) {
        if (!PROTOCOLS.containsKey(config.get("protocol")))
            throw ModelManagementException.badRequest("模型使用已停用协议，请先配置 OpenAI 兼容接口");
        if (!required.equals(config.get("type"))) throw ModelManagementException.badRequest("模型能力类型不适用于该用途");
        Map<String,Object> capabilities=map(config.get("capabilities"));
        if ("agent".equals(purpose) && !Boolean.TRUE.equals(capabilities.get("tools"))) throw ModelManagementException.badRequest("Agent 模型必须声明工具调用能力");
        if ("evidence_support".equals(purpose) && !Boolean.TRUE.equals(capabilities.get("json"))) throw ModelManagementException.badRequest("证据审查模型必须声明 JSON 能力");
    }
    /**
     * 检查模型引用的格式或访问条件。
     * 上游为 ModelRegistryService.update。
     * 下游为 ModelManagementException.conflict。
     *
     * @param config 含当前操作所需属性的映射
     * @param purpose 需要解析或配置的模型用途
     * @param required 本方法计算或判断使用的 required 值
     */
    private void validateReference(Map<String,Object> config,String purpose,String required) {
        try { validatePurpose(config,purpose,required); }
        catch (ModelManagementException incompatible) {
            throw ModelManagementException.conflict("模型仍被现有用途或 Agent 引用，请先解除不兼容绑定");
        }
    }
    /**
     * 校验模型地址符合配置允许的 URL 条件。
     * 上游为 ModelRegistryService.normalize。
     * 下游为 ModelManagementException.badRequest。
     *
     * @param url 本方法计算或判断使用的 url 值
     */
    private void validateUrl(String url) {
        if (url.isBlank()) return;
        try {
            URI uri=URI.create(url); String host=uri.getHost();
            if (!Set.of("http","https").contains(uri.getScheme()) || host == null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
                throw new IllegalArgumentException();
            String normalized=host.replace("[","").replace("]","").toLowerCase(Locale.ROOT);
            if (normalized.startsWith("169.254.") || normalized.equals("metadata.google.internal") || normalized.equals("0.0.0.0") || normalized.startsWith("fe80:"))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw ModelManagementException.badRequest("模型地址必须是合法 HTTP(S) 地址，不能含凭证、查询或元数据服务地址"); }
    }
    /**
     * 查询指定模型并要求属于当前用户。
     * 上游为 ModelRegistryService.create、ModelRegistryService.update。
     * 下游为 ModelManagementException.notFound。
     *
     * @param owner 指明数据归属的账号 ID
     * @param id 模型档案 ID
     * @param lock 读取数据库记录时是否需要取得行锁
     * @return 本次计算或查询得到的 Row 结果
     */
    private Row requireOwned(long owner,String id,boolean lock) {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) throw ModelManagementException.notFound();
        List<Row> rows=jdbc.query("SELECT * FROM managed_model WHERE id=? AND owner_id=?"+(lock?" FOR UPDATE":""),this::row,id,owner);
        if (rows.isEmpty()) throw ModelManagementException.notFound();
        return rows.get(0);
    }
    /**
     * 要求指定模型归属当前用户且处于可用状态。
     * 上游为 ModelRegistryService.update、ModelRegistryService.delete。
     * 下游为 ModelManagementException.notFound。
     *
     * @param owner 指明数据归属的账号 ID
     * @param id 模型档案 ID
     * @param lock 读取数据库记录时是否需要取得行锁
     * @return 本次计算或查询得到的 Row 结果
     */
    private Row requireActiveOwned(long owner,String id,boolean lock) {
        Row row=requireOwned(owner,id,lock);
        if (!supportsExecution(row)) throw ModelManagementException.notFound();
        return row;
    }
    /**
     * 从模型配置确定档案能否用于实际运行。
     * 上游为 ModelRegistryService.resolveOwned、ModelRegistryService.agentExecution。
     * 下游为 Row.config。
     *
     * @param row 本次操作使用的 Row 组件或记录
     * @return 符合本方法描述的状态或完成条件时为 true
     */
    private boolean supportsExecution(Row row) {
        return row.config().get("protocol") instanceof String protocol && PROTOCOLS.containsKey(protocol);
    }
    /**
     * 按模型 ID 读取持久化注册记录。
     *
     * @param rs 本次操作所用的 java.sql.ResultSet 数据
     * @param number 本方法计算或判断使用的 number 值
     * @return 本次计算或查询得到的 Row 结果
     */
    private Row row(java.sql.ResultSet rs,int number) throws java.sql.SQLException {
        try { return new Row(rs.getString("id"),rs.getObject("owner_id",Long.class),rs.getLong("revision"),rs.getLong("credential_version"),
                rs.getString("semantic_fingerprint"),mapper.readValue(rs.getString("config"),new TypeReference<>(){}),rs.getString("credential_ciphertext")); }
        catch (Exception e) { throw new java.sql.SQLException("Invalid model record"); }
    }
    /**
     * 输出可公开的模型设置和版本信息，避免泄露凭证。
     * 上游为 ModelRegistryService.create、ModelRegistryService.update。
     * 下游为 Row.config、Row.id。
     *
     * @param row 本次操作使用的 Row 组件或记录
     * @return 可对账号公开的档案元数据，不包含明文凭证
     */
    private Map<String,Object> publicView(Row row) {
        Map<String,Object> result=new LinkedHashMap<>(row.config());
        result.put("id",row.id()); result.put("revision",row.revision()); result.put("credentialVersion",row.credentialVersion());
        result.put("semanticFingerprint",row.fingerprint()); result.put("credentialConfigured",row.encrypted()!=null);
        result.put("executionSupported",true);
        return result;
    }
    /**
     * 检查索引空间是否仍引用该模型。
     * 上游为 ModelRegistryService.update、ModelRegistryService.delete。
     *
     * @param id 模型档案 ID
     * @return 条件是否成立（检查索引空间是否仍引用该模型）
     */
    private boolean hasSpaceReference(String id) {
        Integer count=jdbc.queryForObject("SELECT count(*) FROM embedding_space WHERE model_id=?",Integer.class,id); return count != null && count>0;
    }
    /**
     * 要求变更请求的版本与当前模型版本一致。
     * 上游为 ModelRegistryService.update、ModelRegistryService.delete。
     * 下游为 ModelManagementException.badRequest、Row.revision。
     *
     * @param row 本次操作使用的 Row 组件或记录
     * @param expected 调用方依赖的预期状态、值或版本
     */
    private static void requireRevision(Row row,long expected) {
        if (expected<1) throw ModelManagementException.badRequest("必须提供正整数 expectedRevision");
        if (row.revision()!=expected) throw ModelManagementException.conflict("模型已被其他请求修改，请刷新后重试");
    }
    /**
     * 从模型操作参数中读取用于并发更新检查的预期版本。
     * 上游为 ModelManagementController.credential、ModelRegistryService.update。
     * 下游为 ModelManagementException.badRequest。
     *
     * @param raw 下游返回或调用方传入的原始值
     * @return 请求提交的预期配置版本
     */
    public static long expected(Object raw) {
        if (!(raw instanceof Number number) || number.longValue()<1 || number.doubleValue()!=number.longValue())
            throw ModelManagementException.badRequest("必须提供正整数 expectedRevision");
        return number.longValue();
    }
    /**
     * 从档案配置读取可空整数并转换为 Java 数值。
     * 上游为 ModelRegistryService.normalize。
     * 下游为 ModelRegistryService.integer。
     *
     * @param raw 下游返回或调用方传入的原始值
     * @param min 当前操作允许的下限
     * @param max 当前操作允许的上限
     * @param field 此次提取或校验的字段名
     * @return 本次计算或查询得到的 Integer 结果
     */
    private Integer nullableInt(Object raw,int min,int max,String field) { return raw == null ? null : integer(raw,min,max,field); }
    /**
     * 规范化模型执行配置中的整数选项和缺省值。
     * 上游为 ModelRegistryService.normalize、ModelRegistryService.nullableInt。
     * 下游为 ModelManagementException.badRequest。
     *
     * @param raw 下游返回或调用方传入的原始值
     * @param min 当前操作允许的下限
     * @param max 当前操作允许的上限
     * @param field 此次提取或校验的字段名
     * @return 本次计算或查询得到的 int 结果
     */
    private int integer(Object raw,int min,int max,String field) {
        if (!(raw instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()!=n.intValue() || n.intValue()<min || n.intValue()>max)
            throw ModelManagementException.badRequest(field+" 超出允许范围");
        return n.intValue();
    }
    /**
     * 规范化模型档案可选的文本字段。
     * 上游为 ModelRegistryService.calibration、ModelRegistryService.bind。
     * 下游为 ModelManagementException.badRequest。
     *
     * @param raw 下游返回或调用方传入的原始值
     * @param field 此次提取或校验的字段名
     * @param max 当前操作允许的上限
     * @param required 本方法计算或判断使用的 required 值
     * @return 本次计算或查询得到的 String 结果
     */
    private String text(Object raw,String field,int max,boolean required) {
        if (raw == null && !required) return "";
        if (!(raw instanceof String value) || value.length()>max || (required && value.isBlank())) throw ModelManagementException.badRequest(field+" 无效");
        return value.trim();
    }
    /**
     * 将对象类型的模型子配置转换为字符串键映射。
     * 上游为 ModelRegistryService.normalize、ModelRegistryService.validatePurpose。
     * 下游为 ModelManagementException.badRequest。
     *
     * @param raw 下游返回或调用方传入的原始值
     * @return 由本方法收集或转换的键值映射
     */
    @SuppressWarnings("unchecked") private Map<String,Object> map(Object raw) {
        if (raw == null) return new LinkedHashMap<>();
        if (!(raw instanceof Map<?,?> m) || m.keySet().stream().anyMatch(k->!(k instanceof String))) throw ModelManagementException.badRequest("配置必须是 JSON 对象");
        return new LinkedHashMap<>((Map<String,Object>)m);
    }
    /**
     * 将模型档案或执行快照编码为 JSON。
     * 上游为 ModelRegistryService.initializeDeploymentDefaults、ModelRegistryService.create。
     * 下游为 ModelManagementException.badRequest。
     *
     * @param value 待校验或转换的值
     * @return 本次计算或查询得到的 String 结果
     */
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw ModelManagementException.badRequest("模型配置无法序列化"); } }
    /**
     * 对规范化语义字段计算稳定模型指纹。
     * 上游为 ModelRegistryService.initializeDeploymentDefaults、ModelRegistryService.create。
     * 下游为 ModelRegistryService.json、ModelRegistryService.sorted。
     *
     * @param config 含当前操作所需属性的映射
     * @return 根据模型语义字段归一后得到的稳定摘要
     */
    public String fingerprint(Map<String,Object> config) {
        Map<String,Object> semantic=new TreeMap<>();
        for (String key:List.of("type","provider","protocol","model","baseUrl","dimension","capabilities","compat")) semantic.put(key,config.get(key));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json(sorted(semantic)).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    /**
     * 为语义指纹计算稳定排列映射键及嵌套值。
     * 上游为 ModelRegistryService.fingerprint。
     *
     * @param value 待校验或转换的值
     * @return 本次计算或查询得到的 Object 结果
     */
    private Object sorted(Object value) {
        if (value instanceof Map<?,?> map) { Map<String,Object> out=new TreeMap<>(); map.forEach((k,v)->out.put(String.valueOf(k),sorted(v))); return out; }
        if (value instanceof List<?> list) return list.stream().map(this::sorted).toList();
        return value;
    }
    private record Row(String id,Long owner,long revision,long credentialVersion,String fingerprint,Map<String,Object> config,String encrypted) { }
}
