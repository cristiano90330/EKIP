/* 装配 Java 到 AI 服务的内部 HTTP 请求，承接解析、嵌入、问答与能力检查。 */

package com.pony.knowledge.service.ai;

import com.pony.knowledge.request.RequestIdContext;

import com.pony.knowledge.observability.RagTrace;
import com.pony.knowledge.observability.RagTraceContext;
import com.pony.knowledge.resilience.AiAvailabilityGuard;
import com.pony.knowledge.resilience.AiFailureClassifier;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.pony.knowledge.modelmanagement.ModelRegistryService;
import com.pony.knowledge.modelmanagement.ModelExecutionContext;
import com.pony.knowledge.modelmanagement.ModelManagementException;
import com.pony.knowledge.modelmanagement.ModelHttpDeadline;
import com.pony.knowledge.utils.CurrentUserUtil;
import java.util.UUID;
import java.io.Closeable;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.web.client.ResourceAccessException;

/**
 * 访问 Python AI 服务的传输适配器，供解析、Embedding、评测和 RAG 业务调用。
 * 入库与查询分别使用读取超时配置，两个 RestClient 共用内部鉴权、请求标识和可用性治理。
 * 请求中的知识库范围和业务参数由调用方决定，本类负责 HTTP 交换与传输 DTO，不执行用户授权。
 */
@Component
public class AiServiceClient {
    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);

    /** 供 Python 日志和响应关联请求的标识头。 */
    private static final String REQUEST_ID_HEADER = "X-Request-ID";

    /** Python 内部调用鉴权使用的请求头。 */
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final RestClient restClient;
    private final RestClient queryClient;
    private final RestClient embeddingClient;
    private final int queryReadTimeoutMs;
    private ModelRegistryService modelRegistry;
    private CurrentUserUtil currentUser;
    private final ThreadLocal<AtomicReference<Closeable>> activeResponse = new ThreadLocal<>();
    private final ThreadLocal<DocumentParseCancellation> activeParse = new ThreadLocal<>();

    /**
     * 注入用于解析默认模型与当前用户执行配置的服务。
     *
     * @param registry ModelRegistryService，负责管理模型档案、用途绑定、凭证与执行配置的解析
     * @param users 本次操作使用的 CurrentUserUtil 组件或记录
     */
    @org.springframework.beans.factory.annotation.Autowired
    public void setModelRegistry(ModelRegistryService registry, CurrentUserUtil users) {
        this.modelRegistry = registry;
        this.currentUser = users;
    }
    private final Tracer tracer;
    private final String internalToken;

    /** 与 AgentGatewayClient 共享的 AI 可用性状态。 */
    private final AiAvailabilityGuard guard;

    /**
     * 保存装配 Java 到 AI 服务的内部 HTTP 请求所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     * 下游为 AiServiceClient.configure。
     *
     * @param builder 本次操作所用的 RestClient.Builder 数据
     * @param baseUrl 下游接口地址
     * @param connectTimeoutMs 以毫秒计的时间限制
     * @param ingestReadTimeoutMs 以毫秒计的时间限制
     * @param queryReadTimeoutMs 以毫秒计的时间限制
     * @param internalToken 待传递或核对的令牌
     * @param tracer 本次操作所用的 Tracer 数据
     * @param guard 本次操作使用的 AiAvailabilityGuard 组件或记录
     */
    public AiServiceClient(
            RestClient.Builder builder,
            @Value("${ai.client.base-url:http://127.0.0.1:8000}") String baseUrl,
            @Value("${ai.client.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${ai.client.ingest-read-timeout-ms:${ai.client.read-timeout-ms}}") int ingestReadTimeoutMs,
            @Value("${ai.client.query-read-timeout-ms:${ai.client.read-timeout-ms}}") int queryReadTimeoutMs,
            @Value("${ai.client.internal-token}") String internalToken,
            Tracer tracer,
            AiAvailabilityGuard guard){
        this.tracer = tracer;
        this.internalToken = internalToken;
        this.guard = guard;
        this.restClient = configure(builder.clone(), baseUrl, connectTimeoutMs, ingestReadTimeoutMs).build();
        this.queryReadTimeoutMs = Math.max(queryReadTimeoutMs, 40_000);
        this.queryClient = configure(builder.clone(), baseUrl, connectTimeoutMs, this.queryReadTimeoutMs).build();
        this.embeddingClient = configure(builder.clone(), baseUrl, connectTimeoutMs, Math.max(ingestReadTimeoutMs, 125000)).build();
    }

    /**
     * 按不同 AI 调用时限创建 RestClient 并注册请求头和日志处理。
     * 下游为 AiServiceClient.jdkRequestFactory。
     *
     * @param builder 本次操作所用的 RestClient.Builder 数据
     * @param baseUrl 下游接口地址
     * @param connectTimeoutMs 以毫秒计的时间限制
     * @param readTimeoutMs 以毫秒计的时间限制
     * @return 本次计算或查询得到的 RestClient.Builder 结果
     */
    private RestClient.Builder configure(RestClient.Builder builder, String baseUrl,
                                         int connectTimeoutMs, int readTimeoutMs) {
        return builder
                .requestFactory(jdkRequestFactory(connectTimeoutMs, readTimeoutMs))
                .requestInterceptor(this::logAndPropagateRequestId)
                .baseUrl(baseUrl);
    }

    /**
     * 将请求标识与内部令牌传给 AI，并记录请求状态和耗时。
     * 下游为 AiAvailabilityGuard.acquire、AiFailureClassifier.isUnreachable。
     *
     * @param request 当前调用的请求参数
     * @param body 调用方传入的请求体
     * @param execution 本次操作所用的 ClientHttpRequestExecution 数据
     * @throws IOException 底层流或响应无法读取或写入时
     * @return 本次计算或查询得到的 ClientHttpResponse 结果
     */
    private ClientHttpResponse logAndPropagateRequestId(HttpRequest request, byte[] body,
                                                        ClientHttpRequestExecution execution) throws IOException {
        // 拒绝发送的调用不进入传输异常分支，避免重复统计失败。
        AiAvailabilityGuard.Call circuitCall = guard.acquire();

        String rid = currentRequestId();
        request.getHeaders().set(REQUEST_ID_HEADER, rid);
        request.getHeaders().set(INTERNAL_TOKEN_HEADER, internalToken);

        String target = request.getMethod() + " " + request.getURI();
        long startedAt = System.nanoTime();
        DocumentParseCancellation parse = activeParse.get();

        try {
            ClientHttpResponse response = execution.execute(request, body);
            AtomicReference<Closeable> closing = activeResponse.get();
            if (closing != null) closing.set(response::close);
            if (parse != null && !parse.trackResponse(response)) {
                DocumentParseCancellation.abortResponse(response);
                throw new java.util.concurrent.CancellationException("文档解析尝试已取消");
            }
            if (Thread.currentThread().isInterrupted()) {
                response.close();
                throw new java.net.http.HttpTimeoutException("模型请求已取消");
            }
            long elapsedMs = elapsedMillis(startedAt);
            int code = rawStatusCode(response);
            String status = String.valueOf(code);
            // 2xx/3xx 记成功，5xx 记失败，其他状态记中性。
            if (code >= 200 && code < 400) {
                circuitCall.success();
            } else if (code >= 500) {
                circuitCall.failure();
            } else {
                circuitCall.ignore();
            }
            if (code >= 400) {
                log.warn("← Python {} 耗时={}ms status={} requestId={}", target, elapsedMs, status, rid);
            } else {
                log.info("← Python {} 耗时={}ms status={} requestId={}", target, elapsedMs, status, rid);
            }
            return response;
        } catch (IOException | RuntimeException e) {
            log.warn("← Python {} 失败 耗时={}ms requestId={} err={}",
                    target, elapsedMillis(startedAt), rid, e.getClass().getSimpleName());
            // 连接不可达记失败；读取超时等其他异常按分类器规则记中性。
            if ((parse == null || !parse.isCancelled()) && AiFailureClassifier.isUnreachable(e)) {
                circuitCall.failure();
            } else {
                circuitCall.ignore();
            }
            throw e;
        } finally {
            circuitCall.close();
        }
    }

    /**
     * 读取上游响应的实际状态码并处理读取失败。
     * 上游为 AiServiceClient.logAndPropagateRequestId。
     *
     * @param response 当前调用对应的响应对象
     * @return 本次计算或查询得到的 int 结果
     */
    private static int rawStatusCode(ClientHttpResponse response) {
        try {
            return response.getStatusCode().value();
        } catch (IOException ignored) {
            return 0;
        }
    }

    /**
     * 将单调时钟的经过时间转换为毫秒。
     * 上游为 AiServiceClient.logAndPropagateRequestId。
     *
     * @param startedAtNanos 本方法计算或判断使用的 startedAtNanos 值
     * @return 从起始单调时钟到当前的耗时毫秒数
     */
    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    /**
     * 从当前请求或日志上下文获得跨服务关联标识。
     * 上游为 AiServiceClient.logAndPropagateRequestId、AiServiceClient.governed。
     * 下游为 RequestIdContext.current。
     *
     * @return 本次计算或查询得到的 String 结果
     */
    private String currentRequestId() {
        String requestId = RequestIdContext.current();
        if (requestId != null) {
            return requestId;
        }
        RagTrace trace = RagTraceContext.get();
        if (trace != null && trace.getTraceId() != null
                && !trace.getTraceId().isBlank() && !"unknown".equals(trace.getTraceId())) {
            return trace.getTraceId();
        }
        if (tracer != null) {
            Span span = tracer.currentSpan();
            if (span != null && span.context() != null && span.context().traceId() != null) {
                return span.context().traceId();
            }
        }
        return UUID.randomUUID().toString();
    }

    /**
     * 创建使用底层 JDK 客户端及读超时的请求工厂。
     * 上游为 AiServiceClient.configure。
     * 下游为 HttpClient.newBuilder。
     *
     * @param connectTimeoutMs 以毫秒计的时间限制
     * @param readTimeoutMs 以毫秒计的时间限制
     * @return 本次计算或查询得到的 ClientHttpRequestFactory 结果
     */
    private ClientHttpRequestFactory jdkRequestFactory(int connectTimeoutMs, int readTimeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return factory;
    }

    /**
     * 传递原文件位置及类型到 Python 解析路由，并返回切片及来源信息。
     * 上游为 DocumentProcessServiceImpl.processAttempt。
     * 下游为 Python ai/app/parser/router.py 的 POST /parser/object。
     *
     * @param documentId 文档 ID
     * @param objectName 原文件在桶内的对象键
     * @param bucketName 对象存储桶名
     * @param fileExtension 原文件扩展名
     * @return AI 返回的文档标识、切片正文和切片来源
     */
    public ParseResult parseDocument(
            Long documentId,
            String objectName,
            String bucketName,
            String fileExtension
    ){
        ParseRequest request = new ParseRequest();

        request.setDocumentId(documentId);
        request.setObjectName(objectName);
        request.setBucketName(bucketName);
        request.setFileExtension(fileExtension);

        log.info("调用AI解析服务, documentId={}, objectName={}, bucketName={}, fileExtension={}",
                documentId, objectName, bucketName, fileExtension);

        return restClient
                .post()
                .uri("/parser/object")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ParseResult.class);

    }

    /**
     * 在文档尝试的取消作用域内执行对象解析，复用普通解析的请求体、鉴权和超时。
     * 上游为 DocumentProcessServiceImpl，响应体由内部 HTTP 拦截器登记。
     *
     * @param documentId 文档 ID
     * @param objectName 原文件对象键
     * @param bucketName 对象桶名
     * @param fileExtension 原文件扩展名
     * @param cancellation 当前文档尝试的取消信号
     * @return Python 返回的解析结果
     */
    public ParseResult parseDocument(Long documentId, String objectName, String bucketName, String fileExtension,
                                     DocumentParseCancellation cancellation) {
        cancellation.start();
        activeParse.set(cancellation);
        try {
            return parseDocument(documentId, objectName, bucketName, fileExtension);
        } finally {
            activeParse.remove();
            cancellation.finish();
        }
    }

    /** 对象解析请求，由 Python 按桶和对象名读取原文件。 */
    @Data
    public static class ParseRequest{

        private Long documentId;

        private String objectName;

        private String bucketName;

        private String fileExtension;

    }

    /** 对象解析结果，供 Java 文档处理流程继续生成向量和写入 chunk。 */
    @Data
    public static class ParseResult{
        private Long documentId;

        private List<ChunkResult> chunks;
    }

    /** 解析块的正文、预分词文本和来源信息，供 DocumentChunkServiceImpl 持久化。 */
    @Data
    public static class ChunkResult{
        private Integer chunkIndex;

        private String content;

        /** 空格连接的 jieba 分词文本，用于生成中文全文检索字段。 */
        private String searchText;

        /** 解析来源类型，例如 text、table 或 image。 */
        private String sourceType;

        /** 来源页码，从 1 开始，可为空。 */
        private Integer pageNo;

        /** 块所属的标题路径，可为空。 */
        private String headingPath;

        /** 表格来源标识，可为空。 */
        private String tableId;

        /** 表格数据行号，从 1 开始，可为空。 */
        private Integer rowIndex;
    }

    /** 按输入顺序提交的待向量化文本。 */
    @Data
    public static class EmbeddingRequest{

        private List<String> texts;
        private Map<String,Object> modelExecution;
    }

    /** 与输入文本顺序对应的向量列表。 */
    @Data
    public static class EmbeddingResponse{

        private List<List<Double>> embeddings;
    }

    /**
     * 向 AI 提交一批正文和嵌入配置，返回与输入顺序一致的向量结果。
     * 上游为 KnowledgeIndexService.runClaim、DocumentChunkServiceImpl.embedChunkContents。
     * 下游为 ModelManagementException.unavailable。
     *
     * @param texts 需要本方法按序读取或筛选的 List&lt;String&gt; 数据
     * @return 按输入顺序排列的嵌入向量和相关用量
     */
    public EmbeddingResponse embed(List<String> texts) {
        throw ModelManagementException.unavailable("model_not_configured: 向量调用必须提供知识库索引快照");
    }

    /**
     * 向 AI 提交一批正文和嵌入配置，返回与输入顺序一致的向量结果。
     * 上游为 KnowledgeIndexService.runClaim、DocumentChunkServiceImpl.embedChunkContents。
     * 下游为 ModelManagementException.unavailable。
     *
     * @param texts 需要本方法按序读取或筛选的 List&lt;String&gt; 数据
     * @param modelExecution 含当前操作所需属性的映射
     * @return 按输入顺序排列的嵌入向量和相关用量
     */
    public EmbeddingResponse embed(List<String> texts, Map<String,Object> modelExecution) {
        if (modelExecution == null) throw ModelManagementException.unavailable("model_not_configured: 缺少向量模型快照");
        EmbeddingRequest request = new EmbeddingRequest();
        request.setTexts(texts);
        request.setModelExecution(modelExecution);
        return governed(modelExecution,125000, () -> embeddingClient.post().uri("/embedding").contentType(MediaType.APPLICATION_JSON)
                .body(request).retrieve().body(EmbeddingResponse.class));
    }

    /**
     * 把评测生成请求交给 AI 并读取答案与裁判结果。
     * 上游为 EvaluationServiceImpl.evaluateRetrieval、EvaluationServiceImpl.evaluateBatch。
     * 下游为 ModelExecutionContext.currentOwnerId、ModelRegistryService.executionForUser。
     *
     * @param question 用户问题正文
     * @param answer 生成的答案
     * @param contexts 需要本方法按序读取或筛选的 List&lt;String&gt; 数据
     * @param groundTruth 本方法计算或判断使用的 groundTruth 值
     * @return 答案、评估分数、失败信息和用量
     */
    public JudgeResponse evaluateGenerate(
            String question,
            String answer,
            List<String> contexts,
            String groundTruth
    ){
        JudgeRequest request = new JudgeRequest();
        request.setQuestion(question == null ? "" : question);
        request.setAnswer(answer);
        request.setContexts(contexts);
        request.setGroundTruth(groundTruth == null ? "" : groundTruth);
        if (modelRegistry != null) {
            Long owner = ModelExecutionContext.currentOwnerId();
            if (owner == null) owner = currentUser.getUserId();
            request.setModelExecution(modelRegistry.executionForUser(owner, 30000, Set.of("judge")));
        }

        return governed(request.getModelExecution(),35000, () -> queryClient.post()
                .uri("/evaluation/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JudgeResponse.class));
    }

    /** 模型裁判使用的问题、答案、上下文和参考答案。 */
    @Data
    public static class JudgeRequest{
        private Map<String,Object> modelExecution;
        private String question;

        private String answer;

        private List<String> contexts;

        private String groundTruth;
    }

    /** 模型裁判评分和 token 用量，缺失评分保持 null。 */
    @Data
    public static class JudgeResponse{
        private Double faithfulness;

        private Double correctness;

        private Double relevance;

        /** 上下文相关度，没有可评判上下文时可为空。 */
        private Double contextRelevance;

        private Integer tokenCost;
    }

    /** 传给 Python 的会话消息，用于按需改写当前问题。 */
    @Data
    public static class RewriteMessage{
        private String role;

        private String content;
    }

    /**
     * 在问答专用超时预算内执行 AI RAG 并接收结构化结果。
     * 上游为 PythonRagPipeline.call。
     * 下游为 AiServiceClient.governed、RagQueryRequest.getModelExecution。
     *
     * @param request 当前调用的请求参数
     * @return 执行策略、候选、公开证据、答案或失败终态
     */
    public RagQueryResponse ragQuery(RagQueryRequest request) {
        return governed(request.getModelExecution(), queryReadTimeoutMs, () -> queryClient.post()
                .uri("/rag/query")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(RagQueryResponse.class));
    }

    /**
     * 取得依赖断路器许可后调用 AI，并按失败分类更新令牌状态。
     * 上游为 AiServiceClient.embed、AiServiceClient.evaluateGenerate。
     * 下游为 RequestIdContext.open、ModelHttpDeadline.run。
     *
     * @param execution 含当前操作所需属性的映射
     * @param fallbackMs 以毫秒计的时间限制
     * @param operation 由此处在对应上下文或作用域内执行的 Callable&lt;T&gt; 回调
     * @return 业务回调实际返回的结果
     */
    private <T> T governed(Map<String,Object> execution,long fallbackMs,Callable<T> operation) {
        AtomicReference<Closeable> closing = new AtomicReference<>();
        try (RequestIdContext.Scope ignored = RequestIdContext.open(currentRequestId())) {
            return ModelHttpDeadline.run(ModelHttpDeadline.outerTimeout(execution,fallbackMs), () -> {
                activeResponse.set(closing);
                try { return operation.call(); } finally { activeResponse.remove(); }
            }, closing);
        } catch (RuntimeException e) { throw e; }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResourceAccessException("模型请求已取消",new IOException("request cancelled"));
        } catch (Exception e) {
            throw new ResourceAccessException("模型服务传输失败",e instanceof IOException io ? io : new IOException("model transport failed"));
        }
    }

    /**
     * 读取 AI 服务当前依赖能力的准备状态。
     * 上游为 CapabilityReadinessService.refresh。
     *
     * @return AI 服务准备情况和分项依赖状态
     */
    public AiReadinessResponse readiness() {
        return queryClient.get().uri("/ready").retrieve().body(AiReadinessResponse.class);
    }

    /** Python readiness 的总体状态与逐项能力快照。 */
    @Data
    public static class AiReadinessResponse {
        private String status;
        private java.util.Map<String, AiCapability> capabilities;
    }

    /** 单项依赖能力的就绪状态及可用诊断元数据。 */
    @Data
    public static class AiCapability {
        private String status;
        private Boolean ready;
        private String reasonCode;
        private String policyVersion;
        private String scorer;
    }

    /**
     * Java 与 Python 的查询请求契约。调用方提供已授权知识库范围、问题、历史和运行参数，
     * Python 按 pipelineEngine 选择编排，按 version 选择检索策略，并在共享预算内执行。
     */
    @Data
    public static class RagQueryRequest {
        private Map<String,Object> modelExecution;
        /** 编排引擎，与检索策略 version 独立。 */
        private String pipelineEngine = "fixed";

        /** Java 已授权的知识库范围，空列表不允许扩大为全库检索。 */
        private List<Long> knowledgeBaseIds;

        private String question;

        /** Optional retrieval hint, separate from the user question reviewed for evidence. */
        private String retrievalQuery;

        private List<RewriteMessage> history;

        private Integer topK;

        /** Python 整条查询共用的截止预算，单位为毫秒。 */
        private Integer budgetMs;

        /** 检索策略：baseline、hybrid、hybrid-rerank 或 hybrid-rerank-rewrite。 */
        private String version;

        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String evidenceMode;

        /** 重排前的召回规模。 */
        private Integer recallN;

        /** 本次请求的 RRF 常量，融合分为各路 1/(k+rank) 之和。 */
        private Integer rrfK = 60;

        /** 是否按已入库的父子关系扩展上下文。 */
        private boolean smallToBigEnabled;

        /** true 执行证据准入和生成；false 跳过二者，但仍可能改写、Embedding 和重排。 */
        private boolean generate = true;

        /**
         * 显式 opt-in：generate=false 时仍执行证据准入与后处理，但不生成答案。
         * 不得与 generate=true 同用；旧 generate=false 调用不传此字段，继续返回原始候选。
         */
        private boolean admitEvidence;

        /** 仅 admitEvidence=true 时使用；把检索限定到 Java 已核验归属的单篇文档。 */
        private Long documentId;

        /** 文档连续阅读选项，仅与 documentId 和显式准入路径一起使用。 */
        private DocumentReadRequest documentRead;

        /** 上下文压缩模式，仅支持 none 或 extractive。 */
        private String compressionMode = "none";

        /** 上下文处理使用的 token 预算。 */
        private Integer tokenBudget = 1500;

        /** 是否启用 MMR 去冗余。 */
        private boolean mmrEnabled;

        /** MMR 在相关度与多样性之间的权重。 */
        private Double mmrLambda = 0.7;
    }

    @Data
    public static class DocumentReadRequest {
        private int offset;
        private int contentOffset;
        private Integer endOffset;
        private Long aroundChunkId;
        private int contextRadius;
        private int windowChars;
    }

    @Data
    public static class DocumentReadPage {
        private Integer offset;
        private Integer contentOffset;
        private Integer endOffset;
        private Integer nextOffset;
        private Integer nextContentOffset;
        private Boolean hasMore;
        private Integer scannedChars;
    }

    /** 上下文压缩前后的 token 数与比例。 */
    @Data
    public static class RagQueryCompression {
        private String mode;

        private int tokensBefore;

        private int tokensAfter;

        private double ratio;
    }

    /** 传输候选或证据的公共结构，是否获准由其所在响应列表和门禁结果区分。 */
    @Data
    public static class RagQuerySource {
        private Long chunkId;

        private Long documentId;

        private Long knowledgeBaseId;

        private Integer chunkIndex;

        private Integer contentOffset;

        private String content;

        private Double score;

        private Double distance;

        private String headingPath;

        private Integer pageNo;

        private String sourceType;
    }

    /** Python 上报的阶段耗时、执行结果、降级和可选 token 用量，供 Java 回填 Trace。 */
    @Data
    public static class RagQueryStage {
        /** 与 Java Trace 映射表对应的 Python 阶段短名。 */
        private String stage;

        private long durationMs;

        private boolean success;

        private String error;

        private boolean degraded;

        private Integer totalTokens;
    }

    /**
     * Python 查询响应。实际引擎与策略用于回显和契约核对，sources 保存排序候选，
     * evidenceSources 保存门禁准入内容；Java 公开问答来源使用后者，并结合四态结果处理失败。
     */
    @Data
    public static class RagQueryResponse {
        private String pipelineEngine;

        private String retrievalVersion;

        private String evidenceMode;

        private String answer;

        /** 结果状态：success、no_evidence、dependency_unavailable 或 timeout。 */
        private String outcome;

        private String reasonCode;

        private String failedStage;

        private String requestId;

        private String retrievalStatus;

        private String originalQuery;

        private String rewrittenQuery;

        /** 原始排序候选，不代表获准参与生成。 */
        private List<RagQuerySource> sources;

        /** 门禁准入内容，用于生成和 Java 对外问答来源。 */
        private List<RagQuerySource> evidenceSources;

        private RagEvidenceDecision evidenceDecision;

        private DocumentReadPage documentPage;

        private boolean degraded;

        /** Python 内部降级诊断，公开响应由业务层按稳定原因码映射。 */
        private String degradeReason;

        private String providerUsed;

        private Integer tokenCost;

        /** Java 回填阶段观测所需的 Python 执行记录。 */
        private List<RagQueryStage> stages;

        /** 可选的上下文压缩统计。 */
        private RagQueryCompression compression;
    }

    /** 证据准入决策，保留策略、scorer、阈值、候选计数及原因码。 */
    @Data
    public static class RagEvidenceDecision {
        private String state;
        private String policyVersion;
        private String scorer;
        private Double threshold;
        private Integer retrievedCount;
        private Integer acceptedCount;
        private Integer rejectedCount;
        private String reasonCode;
    }
}

