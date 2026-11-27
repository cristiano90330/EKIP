/* 组装 Python RAG 调用的授权范围、模型快照与预算，并映射结果。 */

package com.pony.knowledge.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.observability.RagTrace;
import com.pony.knowledge.observability.RagTraceContext;
import com.pony.knowledge.observability.RagTraceRecorder;
import com.pony.knowledge.observability.TraceStage;
import com.pony.knowledge.service.ai.AiServiceClient;
import com.pony.knowledge.exception.RagQueryFailureException;
import com.pony.knowledge.exception.InvalidRagPipelineException;
import com.pony.knowledge.resilience.AiServiceUnavailableException;
import com.pony.knowledge.service.rag.model.RagEvidence;
import com.pony.knowledge.service.rag.model.RagEvidenceDecision;
import com.pony.knowledge.service.rag.model.RagQueryOutcome;
import com.pony.knowledge.service.rag.model.DocumentReadWindow;
import com.pony.knowledge.service.rag.model.RagDocumentPage;
import com.pony.knowledge.service.rag.query.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 问答与检索的 Java 契约边界，接收调用方已经授权的知识库范围、问题和历史。
 * query 请求问答结果，retrieveOnly 请求候选；返回后区分原始候选与获准证据，并校验结果及实际引擎。
 * 业务阅读到此关注输入、输出和错误映射；检索与生成算法在边界外完成。
 */
@Service
public class PythonRagPipeline {

    private static final Logger log = LoggerFactory.getLogger(PythonRagPipeline.class);
    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();

    /** 与 Python 请求校验一致的检索策略取值，用于 Java 配置装配时校验。 */
    private static final Set<String> VALID_VERSIONS = Set.of(
            "baseline", "hybrid", "hybrid-rerank", "hybrid-rerank-rewrite");

    /** 与 Python 后处理一致的压缩模式取值。 */
    private static final Set<String> VALID_COMPRESSION_MODES = Set.of("none", "extractive");

    /** 支持的编排引擎，与检索策略白名单独立。 */
    private static final Set<String> VALID_PIPELINE_ENGINES = Set.of("fixed", "langchain");

    private final String defaultPipelineEngine;

    private final AiServiceClient aiServiceClient;

    private final String version;

    private final int recallN;

    /** 逐请求传给 Python 的 RRF 融合常量。 */
    private final int rrfK;

    private final boolean smallToBigEnabled;

    private final String compressionMode;

    private final int tokenBudget;

    private final boolean mmrEnabled;

    private final double mmrLambda;

    private final int queryBudgetMs;

    private final RagTraceRecorder ragTraceRecorder;
    private com.pony.knowledge.modelmanagement.ModelRegistryService modelRegistry;
    private com.pony.knowledge.modelmanagement.index.KnowledgeIndexService knowledgeIndexes;
    private com.pony.knowledge.utils.CurrentUserUtil currentUser;

    /**
     * 配置模型注册表、知识库索引解析及请求归属查询。
     *
     * @param registry 本次操作所用的 com.pony.knowledge.modelmanagement.ModelRegistryService 数据
     * @param indexes 本次操作所用的 com.pony.knowledge.modelmanagement.index.KnowledgeIndexService 数据
     * @param users 本次操作所用的 com.pony.knowledge.utils.CurrentUserUtil 数据
     */
    @Autowired
    public void setManagedModels(com.pony.knowledge.modelmanagement.ModelRegistryService registry,
                                 com.pony.knowledge.modelmanagement.index.KnowledgeIndexService indexes,
                                 com.pony.knowledge.utils.CurrentUserUtil users) {
        this.modelRegistry = registry;
        this.knowledgeIndexes = indexes;
        this.currentUser = users;
    }

    /**
     * 保存组装 Python RAG 调用的授权范围、模型快照与预算所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param aiServiceClient AiServiceClient，负责装配 Java 到 AI 服务的内部 HTTP 请求，承接解析、嵌入、问答与能力检查
     * @param pipelineEngine 问答管线名称
     * @param retrievalVersion 需要校验或使用的版本
     * @param recallN 初始化或更新同名字段的值
     * @param rrfK 初始化或更新同名字段的值
     * @param compressionMode 初始化或更新同名字段的值
     * @param tokenBudget 初始化或更新同名字段的值
     * @param mmrEnabled 是否启用相关处理
     * @param mmrLambda 初始化或更新同名字段的值
     * @param smallToBigEnabled 是否启用相关处理
     * @param queryBudgetMs 以毫秒计的时间限制
     * @param ragTraceRecorder 本次操作使用的 RagTraceRecorder 组件或记录
     */
    @Autowired
    public PythonRagPipeline(
            AiServiceClient aiServiceClient,
            @Value("${rag.pipeline.engine:fixed}") String pipelineEngine,
            @Value("${retrieval.version:baseline}") String retrievalVersion,
            @Value("${retrieval.recall-n:20}") int recallN,
            @Value("${retrieval.rrf-k:60}") int rrfK,
            @Value("${rag.compression.mode:none}") String compressionMode,
            @Value("${rag.token-budget:1500}") int tokenBudget,
            @Value("${rag.mmr.enabled:false}") boolean mmrEnabled,
            @Value("${rag.mmr.lambda:0.7}") double mmrLambda,
            @Value("${rag.small-to-big.enabled:false}") boolean smallToBigEnabled,
            @Value("${rag.query.budget-ms:270000}") int queryBudgetMs,
            RagTraceRecorder ragTraceRecorder) {
        this.aiServiceClient = aiServiceClient;
        this.defaultPipelineEngine = pipelineEngine;
        this.version = retrievalVersion;
        this.recallN = recallN;
        this.rrfK = rrfK;
        this.smallToBigEnabled = smallToBigEnabled;
        this.compressionMode = compressionMode;
        this.tokenBudget = tokenBudget;
        this.mmrEnabled = mmrEnabled;
        this.mmrLambda = mmrLambda;
        this.queryBudgetMs = queryBudgetMs;
        this.ragTraceRecorder = ragTraceRecorder;

        if (!VALID_PIPELINE_ENGINES.contains(pipelineEngine)) {
            throw new IllegalStateException("非法 rag.pipeline.engine=" + pipelineEngine
                    + "（合法值 " + VALID_PIPELINE_ENGINES + "）");
        }
        if (!VALID_VERSIONS.contains(retrievalVersion)) {
            throw new IllegalStateException(
                    "非法 retrieval.version=" + retrievalVersion
                            + "（合法值 " + VALID_VERSIONS + "）");
        }
        if (!VALID_COMPRESSION_MODES.contains(compressionMode)) {
            throw new IllegalStateException(
                    "非法 rag.compression.mode=" + compressionMode
                            + "（合法值 " + VALID_COMPRESSION_MODES + "）");
        }
    }

    /**
     * 用该入口的默认选项初始化 PythonRagPipeline 实例。
     * 上游为 Spring 组件装配。
     *
     * @param client AiServiceClient，负责装配 Java 到 AI 服务的内部 HTTP 请求，承接解析、嵌入、问答与能力检查
     * @param version 当前需要验证、记录或使用的版本
     * @param recallN 初始化或更新同名字段的值
     * @param rrfK 初始化或更新同名字段的值
     * @param compressionMode 初始化或更新同名字段的值
     * @param tokenBudget 初始化或更新同名字段的值
     * @param mmrEnabled 是否启用相关处理
     * @param mmrLambda 初始化或更新同名字段的值
     * @param smallToBigEnabled 是否启用相关处理
     * @param queryBudgetMs 以毫秒计的时间限制
     * @param recorder 本次操作使用的 RagTraceRecorder 组件或记录
     */
    public PythonRagPipeline(AiServiceClient client, String version, int recallN, int rrfK,
                             String compressionMode, int tokenBudget, boolean mmrEnabled,
                             double mmrLambda, boolean smallToBigEnabled, int queryBudgetMs,
                             RagTraceRecorder recorder) {
        this(client, "fixed", version, recallN, rrfK, compressionMode, tokenBudget, mmrEnabled,
                mmrLambda, smallToBigEnabled, queryBudgetMs, recorder);
    }

    /**
     * 返回部署配置中的默认 RAG 引擎。
     * 上游为 RagService.copyPipeline。
     *
     * @return 部署配置中的默认 RAG 引擎
     */
    public String getDefaultPipelineEngine() { return defaultPipelineEngine; }

    /**
     * 返回服务当前配置的检索版本。
     * 上游为 RagService.copyPipeline。
     *
     * @return 服务当前配置的检索版本
     */
    public String getRetrievalVersion() { return version; }

    /**
     * 解析显式或部署默认的问答引擎并拒绝不支持的名称。
     * 上游为 PythonRagPipeline.prepareTrace、PythonRagPipeline.buildRequest。
     *
     * @param requestedEngine 调用方指定的问答引擎
     * @return 可用于本次执行的问答管线名称
     */
    public String resolvePipelineEngine(String requestedEngine) {
        String selected = requestedEngine == null ? defaultPipelineEngine : requestedEngine;
        if (!VALID_PIPELINE_ENGINES.contains(selected)) {
            throw new InvalidRagPipelineException();
        }
        return selected;
    }

    /**
     * 把请求的原始问题和执行策略关联到当前轨迹。
     * 上游为 KnowledgeSearchController.search、PythonRagPipeline.call。
     *
     * @param requestedEngine 调用方指定的问答引擎
     */
    public void prepareTrace(String requestedEngine) {
        prepareTrace(requestedEngine, RagStrategy.DEFAULT);
    }

    /**
     * 把请求的原始问题和执行策略关联到当前轨迹。
     * 上游为 KnowledgeSearchController.search、PythonRagPipeline.call。
     * 下游为 RagStrategy.versionOr。
     *
     * @param requestedEngine 调用方指定的问答引擎
     * @param strategy 显式指定或部署默认的检索和证据准入策略
     */
    public void prepareTrace(String requestedEngine, RagStrategy strategy) {
        RagTrace trace = RagTraceContext.get();
        if (trace != null) {
            trace.setRequestedPipelineEngine(requestedEngine == null ? defaultPipelineEngine : requestedEngine);
            trace.setRequestedRetrievalVersion(strategy.versionOr(version));
            trace.setPipelineEngine(null);
            trace.setRetrievalVersion(null);
            trace.setProviderUsed(null);
            trace.setEvidenceScorer(null);
            trace.setOutcome(null);
            trace.setReasonCode(null);
        }
        resolvePipelineEngine(requestedEngine);
    }

    /** 将 Python 上报的阶段短名映射为 Java Trace 枚举；未登记的名称不写入阶段记录。 */
    private static final Map<String, TraceStage> STAGE_MAP = Map.of(
            "rewrite", TraceStage.QUERY_REWRITE,
            "vector_retrieval", TraceStage.VECTOR_RETRIEVAL,
            "keyword_retrieval", TraceStage.KEYWORD_RETRIEVAL,
            "fusion", TraceStage.FUSION,
            "rerank", TraceStage.RERANK,
            "evidence_gate", TraceStage.EVIDENCE_GATE,
            "compression", TraceStage.COMPRESSION,
            "context_organize", TraceStage.COMPRESSION,
            "generate", TraceStage.LLM_GENERATION);

    /**
     * 把 AI 返回的阶段耗时和用量写入 Java 当前轨迹。
     * 上游为 PythonRagPipeline.call。
     * 下游为 RagTraceRecorder.recordExternal。
     *
     * @param resp 本次操作所用的 AiServiceClient.RagQueryResponse 数据
     */
    private void recordStages(AiServiceClient.RagQueryResponse resp) {
        RagTrace trace = RagTraceContext.get();
        if (trace == null || resp.getStages() == null) {
            return;
        }
        for (AiServiceClient.RagQueryStage s : resp.getStages()) {
            TraceStage stage = STAGE_MAP.get(s.getStage());
            if (stage == null) {
                log.debug("Python 上报了未登记的阶段名，已忽略: {}", s.getStage());
                continue;
            }
            ragTraceRecorder.recordExternal(trace, stage, s.getDurationMs(),
                    s.isSuccess(), s.getError(), s.isDegraded(), s.getTotalTokens());
        }
    }

    /**
     * 执行包含答案生成的 RAG 请求并返回服务内部结果。
     * 上游为 KnowledgeAskTool.execute、EvaluationServiceImpl.sample。
     * 下游为 PythonRagPipeline.call。
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @param question 用户问题正文
     * @param history 按时间顺序提供的对话历史
     * @param topK 保留的候选数量上限
     * @return 服务内部问答结果、候选与证据、执行信息和降级状态，以 RagQueryOutcome 返回
     */
    public RagQueryOutcome query(
            List<Long> knowledgeBaseIds, String question, List<ChatMessage> history, int topK) {
        return call(knowledgeBaseIds, question, history, topK, true, false, null, null);
    }

    /**
     * 执行包含答案生成的 RAG 请求并返回服务内部结果。
     * 上游为 KnowledgeAskTool.execute、EvaluationServiceImpl.sample。
     * 下游为 PythonRagPipeline.call。
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @param question 用户问题正文
     * @param history 按时间顺序提供的对话历史
     * @param topK 保留的候选数量上限
     * @param pipelineEngine 问答管线名称
     * @return 服务内部问答结果、候选与证据、执行信息和降级状态，以 RagQueryOutcome 返回
     */
    public RagQueryOutcome query(List<Long> knowledgeBaseIds, String question,
                                 List<ChatMessage> history, int topK, String pipelineEngine) {
        return call(knowledgeBaseIds, question, history, topK, true, false, null, pipelineEngine);
    }

    /**
     * 执行包含答案生成的 RAG 请求并返回服务内部结果。
     * 上游为 KnowledgeAskTool.execute、EvaluationServiceImpl.sample。
     * 下游为 PythonRagPipeline.call。
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @param question 用户问题正文
     * @param history 按时间顺序提供的对话历史
     * @param topK 保留的候选数量上限
     * @param pipelineEngine 问答管线名称
     * @param strategy 显式指定或部署默认的检索和证据准入策略
     * @return 服务内部问答结果、候选与证据、执行信息和降级状态，以 RagQueryOutcome 返回
     */
    public RagQueryOutcome query(List<Long> knowledgeBaseIds, String question, List<ChatMessage> history, int topK,
                                 String pipelineEngine, RagStrategy strategy) {
        return call(knowledgeBaseIds, question, history, topK, true, false, null, pipelineEngine,
                null, null, strategy);
    }

    /**
     * 以检索预算获取排序候选，供纯检索或评测使用。
     * 上游为 KnowledgeSearchController.search、EvaluationServiceImpl.sample。
     * 下游为 PythonRagPipeline.call。
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @param question 用户问题正文
     * @param history 按时间顺序提供的对话历史
     * @param topK 保留的候选数量上限
     * @return 服务内部问答结果、候选与证据、执行信息和降级状态，以 RagQueryOutcome 返回
     */
    public RagQueryOutcome retrieveOnly(
            List<Long> knowledgeBaseIds, String question, List<ChatMessage> history, int topK) {
        return call(knowledgeBaseIds, question, history, topK, false, false, null, null);
    }

    /**
     * 以检索预算获取排序候选，供纯检索或评测使用。
     * 上游为 KnowledgeSearchController.search、EvaluationServiceImpl.sample。
     * 下游为 PythonRagPipeline.call。
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @param question 用户问题正文
     * @param history 按时间顺序提供的对话历史
     * @param topK 保留的候选数量上限
     * @param pipelineEngine 问答管线名称
     * @return 服务内部问答结果、候选与证据、执行信息和降级状态，以 RagQueryOutcome 返回
     */
    public RagQueryOutcome retrieveOnly(List<Long> knowledgeBaseIds, String question,
                                        List<ChatMessage> history, int topK, String pipelineEngine) {
        return call(knowledgeBaseIds, question, history, topK, false, false, null, pipelineEngine);
    }

    /**
     * 只执行检索和证据准入，返回允许交给调用方的来源。
     * 上游为 KnowledgeSearchTool.execute、PythonDocumentScopedEvidenceReader.readAdmitted。
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @param question 用户问题正文
     * @param history 按时间顺序提供的对话历史
     * @param topK 保留的候选数量上限
     * @return 服务内部问答结果、候选与证据、执行信息和降级状态，以 RagQueryOutcome 返回
     */
    public RagQueryOutcome retrieveAdmitted(
            List<Long> knowledgeBaseIds, String question, List<ChatMessage> history, int topK) {
        return retrieveAdmitted(knowledgeBaseIds, question, history, topK, null);
    }

    /**
     * 只执行检索和证据准入，返回允许交给调用方的来源。
     * 上游为 KnowledgeSearchTool.execute、PythonDocumentScopedEvidenceReader.readAdmitted。
     * 下游为 PythonRagPipeline.call。
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @param question 用户问题正文
     * @param history 按时间顺序提供的对话历史
     * @param topK 保留的候选数量上限
     * @param documentId 文档 ID
     * @return 服务内部问答结果、候选与证据、执行信息和降级状态，以 RagQueryOutcome 返回
     */
    public RagQueryOutcome retrieveAdmitted(
            List<Long> knowledgeBaseIds, String question, List<ChatMessage> history, int topK,
            Long documentId) {
        return call(knowledgeBaseIds, question, history, topK, false, true, documentId, null);
    }

    /**
     * 只执行检索和证据准入，返回允许交给调用方的来源。
     * 上游为 KnowledgeSearchTool.execute、PythonDocumentScopedEvidenceReader.readAdmitted。
     * 下游为 PythonRagPipeline.call。
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @param question 用户问题正文
     * @param history 按时间顺序提供的对话历史
     * @param topK 保留的候选数量上限
     * @param documentId 文档 ID
     * @param retrievalQuery 交给召回管线的实际问题
     * @return 服务内部问答结果、候选与证据、执行信息和降级状态，以 RagQueryOutcome 返回
     */
    public RagQueryOutcome retrieveAdmitted(
            List<Long> knowledgeBaseIds, String question, List<ChatMessage> history, int topK,
            Long documentId, String retrievalQuery) {
        return call(knowledgeBaseIds, question, history, topK, false, true, documentId,
                null, null, retrievalQuery);
    }

    /**
     * 按 Agent 工具请求的文档和阅读范围检索，再核对证据准入。
     * 上游为 PythonDocumentScopedEvidenceReader.readPage。
     * 下游为 PythonRagPipeline.call。
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @param documentId 文档 ID
     * @param question 用户问题正文
     * @param history 已授权的历史问答，用于证据审阅
     * @param retrievalQuery 用于检索与重排的工具短查询
     * @param window 读取切片或保留历史的窗口约束
     * @return 服务内部问答结果、候选与证据、执行信息和降级状态，以 RagQueryOutcome 返回
     */
    public RagQueryOutcome retrieveDocumentPage(List<Long> knowledgeBaseIds, Long documentId,
                                               String question, List<ChatMessage> history, String retrievalQuery,
                                               DocumentReadWindow window) {
        return call(knowledgeBaseIds, question, history, 1, false, true,
                documentId, null, window, retrievalQuery);
    }

    /**
     * 为指定模式、授权范围和历史构建 AI 请求，携带实际预算与模型执行快照。
     * 上游为 KnowledgeChatStreamController.askStream、KnowledgeChatStreamController.streamSession。
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @param question 用户问题正文
     * @param history 按时间顺序提供的对话历史
     * @param topK 保留的候选数量上限
     * @param generate 是否执行答案生成
     * @param requestedEngine 调用方指定的问答引擎
     * @return 携带授权库范围、策略、候选预算和模型快照的 AI 请求
     */
    public AiServiceClient.RagQueryRequest buildRequest(
            List<Long> knowledgeBaseIds, String question, List<ChatMessage> history, int topK,
            boolean generate, String requestedEngine) {
        return buildRequest(knowledgeBaseIds, question, history, topK, generate,
                false, null, requestedEngine);
    }

    /**
     * 为指定模式、授权范围和历史构建 AI 请求，携带实际预算与模型执行快照。
     * 上游为 KnowledgeChatStreamController.askStream、KnowledgeChatStreamController.streamSession。
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @param question 用户问题正文
     * @param history 按时间顺序提供的对话历史
     * @param topK 保留的候选数量上限
     * @param requestedEngine 调用方指定的问答引擎
     * @param strategy 显式指定或部署默认的检索和证据准入策略
     * @return 携带授权库范围、策略、候选预算和模型快照的 AI 请求
     */
    public AiServiceClient.RagQueryRequest buildRequest(
            List<Long> knowledgeBaseIds, String question, List<ChatMessage> history, int topK,
            String requestedEngine, RagStrategy strategy) {
        return buildRequest(knowledgeBaseIds, question, history, topK, true, false, null,
                requestedEngine, null, strategy);
    }

    /**
     * 为指定模式、授权范围和历史构建 AI 请求，携带实际预算与模型执行快照。
     * 上游为 KnowledgeChatStreamController.askStream、KnowledgeChatStreamController.streamSession。
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @param question 用户问题正文
     * @param history 按时间顺序提供的对话历史
     * @param topK 保留的候选数量上限
     * @param generate 是否执行答案生成
     * @param admitEvidence 是否执行证据准入
     * @param documentId 文档 ID
     * @param requestedEngine 调用方指定的问答引擎
     * @return 携带授权库范围、策略、候选预算和模型快照的 AI 请求
     */
    public AiServiceClient.RagQueryRequest buildRequest(
            List<Long> knowledgeBaseIds, String question, List<ChatMessage> history, int topK,
            boolean generate, boolean admitEvidence, Long documentId, String requestedEngine) {
        return buildRequest(knowledgeBaseIds, question, history, topK, generate,
                admitEvidence, documentId, requestedEngine, null, RagStrategy.DEFAULT);
    }

    /**
     * 为指定模式、授权范围和历史构建 AI 请求，携带实际预算与模型执行快照。
     * 上游为 KnowledgeChatStreamController.askStream、KnowledgeChatStreamController.streamSession。
     * 下游为 RagStrategy.versionOr、RagStrategy.evidenceMode。
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @param question 用户问题正文
     * @param history 按时间顺序提供的对话历史
     * @param topK 保留的候选数量上限
     * @param generate 是否执行答案生成
     * @param admitEvidence 是否执行证据准入
     * @param documentId 文档 ID
     * @param requestedEngine 调用方指定的问答引擎
     * @param window 读取切片或保留历史的窗口约束
     * @param strategy 显式指定或部署默认的检索和证据准入策略
     * @return 携带授权库范围、策略、候选预算和模型快照的 AI 请求
     */
    private AiServiceClient.RagQueryRequest buildRequest(
            List<Long> knowledgeBaseIds, String question, List<ChatMessage> history, int topK,
            boolean generate, boolean admitEvidence, Long documentId, String requestedEngine,
            DocumentReadWindow window, RagStrategy strategy) {
        String selectedEngine = resolvePipelineEngine(requestedEngine);
        String selectedVersion = strategy.versionOr(version);
        List<AiServiceClient.RewriteMessage> messages = history == null ? List.of()
                : history.stream().map(h -> {
                    AiServiceClient.RewriteMessage m = new AiServiceClient.RewriteMessage();
                    m.setRole(h.getRole());
                    m.setContent(h.getContent());
                    return m;
                }).toList();

        AiServiceClient.RagQueryRequest req = new AiServiceClient.RagQueryRequest();
        req.setPipelineEngine(selectedEngine);
        req.setKnowledgeBaseIds(knowledgeBaseIds == null ? List.of() : knowledgeBaseIds);
        req.setQuestion(question);
        req.setHistory(messages);
        req.setTopK(topK);
        // Search and Agent knowledge tools retain the shorter retrieval budget.
        int requestBudgetMs = generate ? queryBudgetMs : Math.min(queryBudgetMs, 35_000);
        req.setBudgetMs(requestBudgetMs);
        req.setVersion(selectedVersion);
        req.setEvidenceMode(strategy.evidenceMode());
        req.setRecallN(recallN);
        req.setRrfK(rrfK);
        req.setSmallToBigEnabled(smallToBigEnabled);
        req.setGenerate(generate);
        req.setAdmitEvidence(admitEvidence);
        req.setDocumentId(documentId);
        req.setCompressionMode(compressionMode);
        req.setTokenBudget(tokenBudget);
        req.setMmrEnabled(mmrEnabled);
        req.setMmrLambda(mmrLambda);
        if (window != null) {
            AiServiceClient.DocumentReadRequest reading = new AiServiceClient.DocumentReadRequest();
            reading.setOffset(window.offset());
            reading.setContentOffset(window.contentOffset());
            reading.setEndOffset(window.endOffset());
            reading.setAroundChunkId(window.aroundChunkId());
            reading.setContextRadius(window.contextRadius());
            reading.setWindowChars(window.windowChars());
            req.setDocumentRead(reading);
            // 阅读窗口已先于准入确定；不让父块、去重或压缩把游标之外的内容混入/裁掉。
            req.setSmallToBigEnabled(false);
            req.setMmrEnabled(false);
            req.setCompressionMode("none");
        }

        try {
            // 只解析本次管线所需的模型用途，并将索引范围和执行预算一起冻结在请求中。
            if (modelRegistry != null) {
                Map<String,Object> execution = new java.util.LinkedHashMap<>();
                execution.put("schemaVersion", 1);
                execution.put("budgetMs", requestBudgetMs);
                execution.put("bindings", Map.of());
                execution.put("indexScopes", List.of());
                if (!req.getKnowledgeBaseIds().isEmpty()) {
                    Long owner = com.pony.knowledge.modelmanagement.ModelExecutionContext.currentOwnerId();
                    if (owner == null) owner = currentUser.getUserId();
                    Set<String> purposes = new java.util.LinkedHashSet<>();
                    if (generate) purposes.add("generate");
                    if ("hybrid-rerank-rewrite".equals(selectedVersion) && !messages.isEmpty()) purposes.add("rewrite");
                    if (selectedVersion.contains("rerank") || window != null) purposes.add("rerank");
                    if ((generate || admitEvidence) && !"score".equals(req.getEvidenceMode())) purposes.add("evidence_support");
                    execution = modelRegistry.executionForUser(owner,requestBudgetMs,purposes);
                    execution.put("indexScopes", knowledgeIndexes.resolveIndexScopes(req.getKnowledgeBaseIds()));
                }
                req.setModelExecution(execution);
            }
        } catch (com.pony.knowledge.modelmanagement.ModelManagementException error) {
            if (error.getStatus()<500) throw error;
            throw failure(HttpStatus.SERVICE_UNAVAILABLE,"dependency_unavailable",error.getReasonCode(),
                    "model_resolution",requestId(),null);
        } catch (com.pony.knowledge.modelmanagement.index.IndexLifecycleException error) {
            if (error.getStatus().value()<500) throw error;
            throw failure(HttpStatus.SERVICE_UNAVAILABLE,"dependency_unavailable",error.getReasonCode(),
                    "index_resolution",requestId(),null);
        }
        return req;
    }

    /**
     * 发送完整 AI 请求并转换成功、拒答或上游失败。
     * 上游为 PythonRagPipeline.query、PythonRagPipeline.retrieveOnly。
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @param question 用户问题正文
     * @param history 按时间顺序提供的对话历史
     * @param topK 保留的候选数量上限
     * @param generate 是否执行答案生成
     * @param admitEvidence 是否执行证据准入
     * @param documentId 文档 ID
     * @param requestedEngine 调用方指定的问答引擎
     * @return 服务内部问答结果、候选与证据、执行信息和降级状态，以 RagQueryOutcome 返回
     */
    private RagQueryOutcome call(
            List<Long> knowledgeBaseIds, String question, List<ChatMessage> history, int topK,
            boolean generate, boolean admitEvidence, Long documentId, String requestedEngine) {
        return call(knowledgeBaseIds, question, history, topK, generate, admitEvidence,
                documentId, requestedEngine, null);
    }

    /**
     * 发送完整 AI 请求并转换成功、拒答或上游失败。
     * 上游为 PythonRagPipeline.query、PythonRagPipeline.retrieveOnly。
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @param question 用户问题正文
     * @param history 按时间顺序提供的对话历史
     * @param topK 保留的候选数量上限
     * @param generate 是否执行答案生成
     * @param admitEvidence 是否执行证据准入
     * @param documentId 文档 ID
     * @param requestedEngine 调用方指定的问答引擎
     * @param window 读取切片或保留历史的窗口约束
     * @return 服务内部问答结果、候选与证据、执行信息和降级状态，以 RagQueryOutcome 返回
     */
    private RagQueryOutcome call(
            List<Long> knowledgeBaseIds, String question, List<ChatMessage> history, int topK,
            boolean generate, boolean admitEvidence, Long documentId, String requestedEngine,
            DocumentReadWindow window) {
        return call(knowledgeBaseIds, question, history, topK, generate, admitEvidence,
                documentId, requestedEngine, window, null);
    }

    /**
     * 发送完整 AI 请求并转换成功、拒答或上游失败。
     * 上游为 PythonRagPipeline.query、PythonRagPipeline.retrieveOnly。
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @param question 用户问题正文
     * @param history 按时间顺序提供的对话历史
     * @param topK 保留的候选数量上限
     * @param generate 是否执行答案生成
     * @param admitEvidence 是否执行证据准入
     * @param documentId 文档 ID
     * @param requestedEngine 调用方指定的问答引擎
     * @param window 读取切片或保留历史的窗口约束
     * @param retrievalQuery 交给召回管线的实际问题
     * @return 服务内部问答结果、候选与证据、执行信息和降级状态，以 RagQueryOutcome 返回
     */
    private RagQueryOutcome call(
            List<Long> knowledgeBaseIds, String question, List<ChatMessage> history, int topK,
            boolean generate, boolean admitEvidence, Long documentId, String requestedEngine,
            DocumentReadWindow window, String retrievalQuery) {
        return call(knowledgeBaseIds, question, history, topK, generate, admitEvidence, documentId,
                requestedEngine, window, retrievalQuery, RagStrategy.DEFAULT);
    }

    /**
     * 发送完整 AI 请求并转换成功、拒答或上游失败。
     * 上游为 PythonRagPipeline.query、PythonRagPipeline.retrieveOnly。
     * 下游为 AiServiceClient.ragQuery、RagQueryOutcome.builder。
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @param question 用户问题正文
     * @param history 按时间顺序提供的对话历史
     * @param topK 保留的候选数量上限
     * @param generate 是否执行答案生成
     * @param admitEvidence 是否执行证据准入
     * @param documentId 文档 ID
     * @param requestedEngine 调用方指定的问答引擎
     * @param window 读取切片或保留历史的窗口约束
     * @param retrievalQuery 交给召回管线的实际问题
     * @param strategy 显式指定或部署默认的检索和证据准入策略
     * @return 服务内部问答结果、候选与证据、执行信息和降级状态，以 RagQueryOutcome 返回
     */
    private RagQueryOutcome call(
            List<Long> knowledgeBaseIds, String question, List<ChatMessage> history, int topK,
            boolean generate, boolean admitEvidence, Long documentId, String requestedEngine,
            DocumentReadWindow window, String retrievalQuery, RagStrategy strategy) {
        prepareTrace(requestedEngine, strategy);
        String selectedEngine = resolvePipelineEngine(requestedEngine);
        recordOriginalQuery(question);

        // 请求参数由此处统一装配，Python 按本次请求执行；空知识库范围保持为空，不扩大检索范围。
        AiServiceClient.RagQueryRequest req = buildRequest(
                knowledgeBaseIds, question, history, topK, generate, admitEvidence, documentId,
                selectedEngine, window, strategy);
        req.setRetrievalQuery(retrievalQuery);

        AiServiceClient.RagQueryResponse resp;

        try {
            resp = aiServiceClient.ragQuery(req);
        } catch (com.pony.knowledge.modelmanagement.ModelManagementException error) {
            if (error.getStatus()<500) throw error;
            throw failure(HttpStatus.SERVICE_UNAVAILABLE,"dependency_unavailable",error.getReasonCode(),
                    "ai.query",requestId(),null);
        } catch (AiServiceUnavailableException e) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "dependency_unavailable", "ai_unavailable",
                    "ai.query", requestId(), e);
        } catch (RestClientResponseException e) {
            throw mapHttpFailure(e);
        } catch (ResourceAccessException e) {
            if (isReadTimeout(e)) {
                throw failure(HttpStatus.GATEWAY_TIMEOUT, "timeout", "query_deadline_exceeded",
                        "ai.query", requestId(), e);
            }
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "dependency_unavailable", "ai_unavailable",
                    "ai.query", requestId(), e);
        }

        /*
         * HTTP 调用完成后仍需检查响应契约。生成请求与显式准入检索都要求四态结果和
         * evidenceSources；success 还要求至少一条获准证据。这里只检查必要结构，
         * 不在 Java 重新执行证据评分。旧 retrieveOnly 保持不要求准入契约。
         */
        boolean admissionRequired = generate || admitEvidence;
        if (resp == null || (admissionRequired && (!hasAdmissionContract(resp)
                || ("success".equals(resp.getOutcome()) && resp.getEvidenceSources().isEmpty())))) {
            String responseRequestId = resp == null ? null : resp.getRequestId();
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "dependency_unavailable", "ai_contract_mismatch",
                    "ai.response_contract", firstNonBlank(responseRequestId, requestId()), null);
        }

        // 先记录响应中合法的实际元数据，再核对请求选择，契约不符时仍保留可用的诊断事实。
        recordResponseMetadata(resp,
                resp.getPipelineEngine() != null && VALID_PIPELINE_ENGINES.contains(resp.getPipelineEngine())
                        ? resp.getPipelineEngine() : null,
                resp.getRetrievalVersion() != null && VALID_VERSIONS.contains(resp.getRetrievalVersion())
                        ? resp.getRetrievalVersion() : null);
        /*
         * 兼容 fixed 响应省略执行元数据，但已返回的值必须与请求一致；langchain 必须明确回报
         * 引擎和检索策略。契约不符时报告依赖故障，避免把静默切换引擎当作用户选择已执行。
         */
        boolean engineMismatch = resp.getPipelineEngine() != null
                && !selectedEngine.equals(resp.getPipelineEngine());
        String selectedVersion = req.getVersion();

        // 显式指定策略或 LangChain 时，服务端响应必须提供足以确认实际执行策略的元数据。
        boolean strictMetadata = "langchain".equals(selectedEngine) || strategy != RagStrategy.DEFAULT;
        boolean missingExecutionMetadata = strictMetadata
                && (resp.getPipelineEngine() == null || resp.getRetrievalVersion() == null);
        boolean explicitVersionMismatch = resp.getRetrievalVersion() != null
                && !selectedVersion.equals(resp.getRetrievalVersion());
        boolean evidenceModeMismatch = req.getEvidenceMode() != null
                && !req.getEvidenceMode().equals(resp.getEvidenceMode());

        if (engineMismatch || missingExecutionMetadata || explicitVersionMismatch || evidenceModeMismatch) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "dependency_unavailable", "ai_contract_mismatch",
                    "ai.response_contract", firstNonBlank(resp.getRequestId(), requestId()), null);
        }
        String actualEngine = firstNonBlank(resp.getPipelineEngine(), selectedEngine);
        String actualVersion = firstNonBlank(resp.getRetrievalVersion(), selectedVersion);

        recordRewrittenQuery(resp.getRewrittenQuery());

        recordStages(resp);

        // sources 保留原始排序候选，evidenceSources 单独映射为可公开的获准证据。
        List<RagEvidence> sources = toEvidences(resp.getSources());
        List<RagEvidence> evidences = toEvidences(resp.getEvidenceSources());

        if (!generate && isUnavailableOutcome(resp.getOutcome())) {
            String outcome = resp.getOutcome();
            throw failure("timeout".equals(outcome) ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.SERVICE_UNAVAILABLE,
                    outcome, firstNonBlank(resp.getReasonCode(), outcome),
                    firstNonBlank(resp.getFailedStage(), "ai.query"),
                    firstNonBlank(resp.getRequestId(), requestId()), null);
        }

        return RagQueryOutcome.builder()
                .pipelineEngine(actualEngine)
                .retrievalVersion(actualVersion)
                .evidenceMode(resp.getEvidenceMode())
                .answer(resp.getAnswer())
                .outcome(resp.getOutcome())
                .reasonCode(resp.getReasonCode())
                .failedStage(resp.getFailedStage())
                .requestId(resp.getRequestId())
                .retrievalStatus(resp.getRetrievalStatus())
                .originalQuery(resp.getOriginalQuery())
                .rewrittenQuery(resp.getRewrittenQuery())
                .sources(sources)
                .evidences(evidences)
                .evidenceDecision(toEvidenceDecision(resp.getEvidenceDecision()))
                .documentPage(toDocumentPage(resp.getDocumentPage()))
                .degraded(resp.isDegraded())
                .degradeReason(resp.getDegradeReason())
                .providerUsed(resp.getProviderUsed())
                .tokenCost(resp.getTokenCost())
                .stages(toStageTimings(resp.getStages()))
                .compression(toCompressionInfo(resp.getCompression()))
                .build();
    }

    /**
     * 从 AI 响应更新当前轨迹记录的实际执行配置。
     * 上游为 PythonRagPipeline.call。
     * 下游为 RagTraceContext.get、PythonRagPipeline.firstNonBlank。
     *
     * @param response 当前调用对应的响应对象
     * @param engine 问答引擎配置值
     * @param retrievalVersion 需要校验或使用的版本
     */
    private static void recordResponseMetadata(AiServiceClient.RagQueryResponse response,
                                              String engine, String retrievalVersion) {
        RagTrace trace = RagTraceContext.get();
        if (trace != null) {
            trace.setPipelineEngine(engine);
            trace.setRetrievalVersion(retrievalVersion);
            trace.setProviderUsed(firstNonBlank(response.getProviderUsed(), null));
            trace.setEvidenceScorer(response.getEvidenceDecision() == null ? null
                    : firstNonBlank(response.getEvidenceDecision().getScorer(), null));
            trace.setOutcome(response.getOutcome());
            trace.setReasonCode(response.getReasonCode());
        }
    }

    /**
     * 将用户原始问题写入当前可用的 RAG 轨迹。
     * 上游为 PythonRagPipeline.call。
     * 下游为 RagTraceContext.get。
     *
     * @param question 用户问题正文
     */
    private static void recordOriginalQuery(String question) {
        RagTrace trace = RagTraceContext.get();
        if (trace != null) {
            trace.setOriginalQuery(question);
            trace.setRewrittenQuery(null);
        }
    }

    /**
     * 将用于召回的改写问题写入当前可用的 RAG 轨迹。
     * 上游为 PythonRagPipeline.call。
     * 下游为 RagTraceContext.get。
     *
     * @param rewrittenQuery 模型改写后的检索问题
     */
    private static void recordRewrittenQuery(String rewrittenQuery) {
        RagTrace trace = RagTraceContext.get();
        if (trace != null) {
            trace.setRewrittenQuery(rewrittenQuery);
        }
    }

    /**
     * 把 AI HTTP 异常转换为 Java 可公开的失败分类与诊断字段。
     * 上游为 PythonRagPipeline.call。
     * 下游为 PythonRagPipeline.parseError、PythonRagPipeline.firstNonBlank。
     *
     * @param error 本次执行捕获的异常
     * @return 问答失败分类、执行阶段和请求标识，以 RagQueryFailureException 返回
     */
    private RagQueryFailureException mapHttpFailure(RestClientResponseException error) {
        int status = error.getStatusCode().value();
        ErrorFields fields = parseError(error.getResponseBodyAsString());
        String requestId = firstNonBlank(fields.requestId(), requestId());

        if (status == 401 || status == 403) {
            return failure(HttpStatus.SERVICE_UNAVAILABLE, "dependency_unavailable", "ai_internal_auth",
                    firstNonBlank(fields.failedStage(), "ai_boundary"), requestId, error);
        }
        if (status == 504 || status == 408) {
            return failure(HttpStatus.GATEWAY_TIMEOUT, "timeout",
                    firstNonBlank(fields.reasonCode(), "query_deadline_exceeded"),
                    firstNonBlank(fields.failedStage(), "ai.query"), requestId, error);
        }
        if (status == 503 || status == 429) {
            return failure(HttpStatus.SERVICE_UNAVAILABLE, "dependency_unavailable",
                    firstNonBlank(fields.reasonCode(), status == 429
                            ? "query_capacity_rejected" : "retrieval_unavailable"),
                    firstNonBlank(fields.failedStage(), "ai.query"), requestId, error);
        }
        throw error;
    }

    /**
     * 构造可由统一异常处理器读取状态和诊断的问答失败。
     * 上游为 PythonRagPipeline.buildRequest、PythonRagPipeline.call。
     * 下游为 RagTraceContext.get。
     *
     * @param status 要读取或写入的状态
     * @param outcome 查询或操作的业务结果状态
     * @param reasonCode 用于区分失败或降级路径的原因码
     * @param failedStage 故障发生的执行阶段
     * @param requestId 关联客户端请求与日志的请求标识
     * @param cause 当前流程捕获的 Throwable 异常
     * @return 问答失败分类、执行阶段和请求标识，以 RagQueryFailureException 返回
     */
    private static RagQueryFailureException failure(HttpStatus status, String outcome, String reasonCode,
                                                     String failedStage, String requestId, Throwable cause) {
        RagTrace trace = RagTraceContext.get();
        if (trace != null) {
            trace.setOutcome(outcome);
            trace.setReasonCode(reasonCode);
        }
        String message = "timeout".equals(outcome) ? "问答处理超时，请稍后重试" : "问答服务暂时不可用，请稍后重试";
        return new RagQueryFailureException(status, outcome, reasonCode, failedStage, requestId, message, cause);
    }

    /**
     * 区分上游结果是否表示依赖不可用或请求超时。
     * 上游为 PythonRagPipeline.call。
     *
     * @param outcome 查询或操作的业务结果状态
     * @return 符合本方法描述的状态或完成条件时为 true
     */
    private static boolean isUnavailableOutcome(String outcome) {
        return "dependency_unavailable".equals(outcome) || "timeout".equals(outcome);
    }

    /**
     * 确认 AI 返回证据准入场景要求的状态和结果结构。
     * 上游为 PythonRagPipeline.call。
     * 下游为 RagQueryResponse.getOutcome、RagQueryResponse.getEvidenceSources。
     *
     * @param response 当前调用对应的响应对象
     * @return 结果状态受支持且返回了独立证据列表时为 true
     */
    private static boolean hasAdmissionContract(AiServiceClient.RagQueryResponse response) {
        String outcome = response.getOutcome();
        return ("success".equals(outcome) || "no_evidence".equals(outcome)
                || "dependency_unavailable".equals(outcome) || "timeout".equals(outcome))
                && response.getEvidenceSources() != null;
    }

    /**
     * 解析上游错误体中的结构化诊断，忽略无法解析的数据。
     * 上游为 PythonRagPipeline.mapHttpFailure。
     * 下游为 PythonRagPipeline.text。
     *
     * @param body 调用方传入的请求体
     * @return 上游成功提取的状态诊断字段，用于映射到 Java 错误
     */
    private static ErrorFields parseError(String body) {
        if (body == null || body.isBlank()) {
            return new ErrorFields(null, null, null);
        }
        try {
            JsonNode root = ERROR_MAPPER.readTree(body);
            JsonNode detail = root.has("detail") ? root.get("detail") : root;
            return new ErrorFields(text(detail, "code", "reasonCode", "reason_code"),
                    text(detail, "failedStage", "failed_stage"),
                    text(detail, "requestId", "request_id"));
        } catch (Exception ignored) {
            return new ErrorFields(null, null, null);
        }
    }

    /**
     * 从结构化错误节点读取可供记录的非空文本。
     * 上游为 PythonRagPipeline.parseError。
     *
     * @param node 本次操作所用的 JsonNode 数据
     * @param names 需要本方法按序读取或筛选的 String[] 数据
     * @return 错误对象指定字段的非空文本
     */
    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    /**
     * 获取失败映射采用的当前请求关联标识。
     * 上游为 PythonRagPipeline.buildRequest、PythonRagPipeline.call。
     * 下游为 RequestIdContext.current。
     *
     * @return 本次计算或查询得到的 String 结果
     */
    private static String requestId() {
        String inbound = com.pony.knowledge.request.RequestIdContext.current();
        if (inbound != null) return inbound;
        RagTrace trace = RagTraceContext.get();
        return trace == null || trace.getTraceId() == null ? null : trace.getTraceId();
    }

    /**
     * 按输入的优先顺序选择首个非空文本。
     * 上游为 PythonRagPipeline.call、PythonRagPipeline.recordResponseMetadata。
     *
     * @param value 待校验或转换的值
     * @param fallback 原始值缺失或无效时使用的备选值
     * @return 按顺序找到的首个非空文本，均无有效值时为空
     */
    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 遍历异常链检查底层是否发生读取超时。
     * 上游为 PythonRagPipeline.call。
     *
     * @param error 本次执行捕获的异常
     * @return 符合本方法描述的状态或完成条件时为 true
     */
    private static boolean isReadTimeout(Throwable error) {
        Throwable current = error;
        int depth = 0;

        while (current != null && depth++ < 20) {
            if (current instanceof HttpConnectTimeoutException) {
                return false;
            }
            if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 上游错误体中的稳定诊断字段。 */
    private record ErrorFields(String reasonCode, String failedStage, String requestId) {
    }

    /**
     * 转换 AI 的候选或获准来源，保留切片位置和正文。
     * 上游为 PythonRagPipeline.call。
     * 下游为 RagEvidence.builder。
     *
     * @param sources 检索候选或可供引用的来源
     * @return 与过滤条件匹配的 List&lt;RagEvidence&gt; 数据集合
     */
    private static List<RagEvidence> toEvidences(List<AiServiceClient.RagQuerySource> sources) {
        if (sources == null) {
            return List.of();
        }
        return sources.stream().map(s -> RagEvidence.builder()
                .chunkId(s.getChunkId())
                .documentId(s.getDocumentId())
                .knowledgeBaseId(s.getKnowledgeBaseId())
                .chunkIndex(s.getChunkIndex())
                .contentOffset(s.getContentOffset())
                .content(s.getContent())
                .score(s.getScore())
                .distance(s.getDistance())
                .headingPath(s.getHeadingPath())
                .pageNo(s.getPageNo())
                .sourceType(s.getSourceType())
                .build()).toList();
    }

    /**
     * 转换文档阅读结果中的字符窗口和下一页位置。
     * 上游为 PythonRagPipeline.call。
     * 下游为 RagDocumentPage.builder。
     *
     * @param page 要读取的页码或分页对象
     * @return 文档阅读的当前范围、下次偏移和分页状态，以 RagDocumentPage 返回
     */
    private static RagDocumentPage toDocumentPage(
            AiServiceClient.DocumentReadPage page) {
        if (page == null || page.getOffset() == null || page.getContentOffset() == null
                || page.getHasMore() == null || page.getScannedChars() == null) {
            return null;
        }
        return RagDocumentPage.builder()
                .offset(page.getOffset()).contentOffset(page.getContentOffset())
                .endOffset(page.getEndOffset()).nextOffset(page.getNextOffset())
                .nextContentOffset(page.getNextContentOffset()).hasMore(page.getHasMore())
                .scannedChars(page.getScannedChars()).build();
    }

    /**
     * 转换 AI 返回的证据策略、数量和原因信息。
     * 上游为 PythonRagPipeline.call。
     * 下游为 RagEvidenceDecision.builder。
     *
     * @param decision 本次操作所用的 AiServiceClient.RagEvidenceDecision 数据
     * @return 证据策略、数量统计和准入判断，以 RagEvidenceDecision 返回
     */
    private static RagEvidenceDecision toEvidenceDecision(AiServiceClient.RagEvidenceDecision decision) {
        if (decision == null) {
            return null;
        }
        return RagEvidenceDecision.builder()
                .state(decision.getState())
                .policyVersion(decision.getPolicyVersion())
                .scorer(decision.getScorer())
                .threshold(decision.getThreshold())
                .retrievedCount(decision.getRetrievedCount())
                .acceptedCount(decision.getAcceptedCount())
                .rejectedCount(decision.getRejectedCount())
                .reasonCode(decision.getReasonCode())
                .build();
    }

    /**
     * 转换 AI 返回的上下文压缩方式、耗时和用量。
     * 上游为 PythonRagPipeline.call。
     * 下游为 CompressionInfo.builder。
     *
     * @param c 本次操作所用的 AiServiceClient.RagQueryCompression 数据
     * @return 取得的 RagQueryOutcome.CompressionInfo 结果，未找到有效值时可能为空
     */
    private static RagQueryOutcome.CompressionInfo toCompressionInfo(
            AiServiceClient.RagQueryCompression c) {
        if (c == null) {
            return null;
        }
        return RagQueryOutcome.CompressionInfo.builder()
                .mode(c.getMode())
                .tokensBefore(c.getTokensBefore())
                .tokensAfter(c.getTokensAfter())
                .ratio(c.getRatio())
                .build();
    }

    /**
     * 归一上游阶段名并转换观测阶段、耗时与令牌统计。
     * 上游为 PythonRagPipeline.call。
     * 下游为 StageTiming.builder。
     *
     * @param stages 需要本方法按序读取或筛选的 List&lt;AiServiceClient.RagQueryStage&gt; 数据
     * @return 与过滤条件匹配的 List&lt;RagQueryOutcome.StageTiming&gt; 数据集合
     */
    private static List<RagQueryOutcome.StageTiming> toStageTimings(
            List<AiServiceClient.RagQueryStage> stages) {
        if (stages == null) {
            return List.of();
        }
        return stages.stream()
                .map(s -> RagQueryOutcome.StageTiming.builder()
                        .stage(s.getStage())
                        .durationMs(s.getDurationMs())
                        .success(s.isSuccess())
                        .degraded(s.isDegraded())
                        .totalTokens(s.getTotalTokens())
                        .build())
                .toList();
    }
}
