/* 组织知识库检索与生成评测，汇总指标并执行回归对比。 */

package com.pony.knowledge.service.impl;

import com.pony.knowledge.dto.ChunkSearchResultDTO;
import com.pony.knowledge.dto.EvaluationBatchRequestDTO;
import com.pony.knowledge.dto.EvaluationBatchResultDTO;
import com.pony.knowledge.dto.EvaluationRequestDTO;
import com.pony.knowledge.dto.EvaluationResultDTO;
import com.pony.knowledge.dto.MetricAggregateDTO;
import com.pony.knowledge.dto.RegressionCompareRequestDTO;
import com.pony.knowledge.dto.RagTraceDTO;
import com.pony.knowledge.evaluation.regression.RegressionEvaluator;
import com.pony.knowledge.evaluation.retrieval.RedundancyMetric;
import com.pony.knowledge.evaluation.regression.RegressionVerdict;
import com.pony.knowledge.evaluation.retrieval.RetrievalEvaluationResult;
import com.pony.knowledge.evaluation.retrieval.RetrievalEvaluator;
import com.pony.knowledge.service.EvaluationService;
import com.pony.knowledge.service.KnowledgeBaseAccessService;
import com.pony.knowledge.utils.CurrentUserUtil;
import com.pony.knowledge.exception.ForbiddenException;
import com.pony.knowledge.service.ai.AiServiceClient;
import com.pony.knowledge.service.rag.PythonRagPipeline;
import com.pony.knowledge.service.rag.model.RagEvidence;
import com.pony.knowledge.service.rag.model.RagEvidenceDecision;
import com.pony.knowledge.service.rag.model.RagQueryOutcome;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Service
public class EvaluationServiceImpl implements EvaluationService {

    private static final int DEFAULT_TOP_K = 5;

    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final CurrentUserUtil currentUserUtil;

    private final AiServiceClient aiServiceClient;

    /** 原生检索指标计算（免 Python 往返，口径与 Python evaluator.py 对齐） */
    private final RetrievalEvaluator retrievalEvaluator = new RetrievalEvaluator();

    /** 当前检索技术路线：baseline / hybrid / hybrid-rerank / hybrid-rerank-rewrite / compression */
    private final String retrievalVersion;

    /** LLM 价格（USD / 1M tokens），用于 Cost 估算 */
    private final double llmPricePer1mTokens;

    /**
     * 「重切边界」的 python 端到端路径（决策文档 §5.12.10 第 4 步）。
     *
     * <p>评测只走这一条路径：`sample()` 一次调用 Python `/rag/query`，指标算法读回传的
     * `stages[]`/`compression`，口径与分段路径逐字对齐（只是数据来源不同）。</p>
     */
    private final PythonRagPipeline pythonRagPipeline;

    /** 下面为非 Spring 纯逻辑组件 */
    private final RegressionEvaluator regressionEvaluator = new RegressionEvaluator();

    /**
     * 保存组织知识库检索与生成评测所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param knowledgeBaseAccessService KnowledgeBaseAccessService，负责约定知识库可读范围和创建者操作的权限判定
     * @param currentUserUtil 本次操作使用的 CurrentUserUtil 组件或记录
     * @param aiServiceClient AiServiceClient，负责装配 Java 到 AI 服务的内部 HTTP 请求，承接解析、嵌入、问答与能力检查
     * @param pythonRagPipeline 本次操作使用的 PythonRagPipeline 组件或记录
     * @param retrievalVersion 需要校验或使用的版本
     * @param llmPricePer1mTokens 初始化或更新同名字段的值
     */
    public EvaluationServiceImpl(
            KnowledgeBaseAccessService knowledgeBaseAccessService,
            CurrentUserUtil currentUserUtil,
            AiServiceClient aiServiceClient,
            PythonRagPipeline pythonRagPipeline,
            @Value("${retrieval.version:baseline}") String retrievalVersion,
            @Value("${rag.llm-price-per-1m-tokens:2.0}") double llmPricePer1mTokens) {
        this.knowledgeBaseAccessService = knowledgeBaseAccessService;
        this.currentUserUtil = currentUserUtil;
        this.aiServiceClient = aiServiceClient;
        this.pythonRagPipeline = pythonRagPipeline;
        this.retrievalVersion = retrievalVersion;
        this.llmPricePer1mTokens = llmPricePer1mTokens;
    }

    /**
     * 一份评测样本：**评测数学只认 `chunks`**（命中集/指标全由它算），其余是生成侧与观测侧的附加数据。
     *
     * <p>把「数据从哪来」收敛到 {@link #sample} 一个地方，两条评测入口（单条 / 批量）
     * 的指标算法一行都不改——数据来源恒为一次 Python `/rag/query`，口径不变。</p>
     */
    private record EvalSample(List<ChunkSearchResultDTO> retrievedChunks,
                              List<ChunkSearchResultDTO> evidenceChunks,
                              String answer, int generationTokens,
                              long retrievalMs, long generationMs,
                              List<RagQueryOutcome.StageTiming> stages, String rewrittenQuery,
                              RagQueryOutcome.CompressionInfo compression,
                              String outcome, String reasonCode, String failedStage, String requestId,
                              RagEvidenceDecision evidenceDecision) {
    }

    private static final List<String> RETRIEVAL_STAGES =
            List.of("rewrite", "vector_retrieval", "keyword_retrieval", "fusion", "rerank");

    /**
     * 在评测前检查调用者拥有目标知识库。
     * 上游为 EvaluationServiceImpl.evaluateRetrieval、EvaluationServiceImpl.evaluateBatch。
     * 下游为 KnowledgeBaseAccessService.canEvaluate。
     *
     * @param knowledgeBaseId 知识库 ID
     */
    private void requireEvaluationAccess(Long knowledgeBaseId) {
        if (!knowledgeBaseAccessService.canEvaluate(currentUserUtil.getUserId(), knowledgeBaseId)) {
            throw new ForbiddenException("无权操作该知识库");
        }
    }

    /**
     * 累计指定评测阶段记录的耗时。
     * 上游为 EvaluationServiceImpl.sample、EvaluationServiceImpl.applyCompressionMetrics。
     *
     * @param stages 需要本方法按序读取或筛选的 List&lt;RagQueryOutcome.StageTiming&gt; 数据
     * @param name 记录对外显示的名称
     * @return 本次计算或查询得到的 long 结果
     */
    private static long stageMs(List<RagQueryOutcome.StageTiming> stages, String name) {
        return stages.stream().filter(s -> name.equals(s.getStage()))
                .mapToLong(RagQueryOutcome.StageTiming::getDurationMs).findFirst().orElse(0L);
    }

    /**
     * 累计指定评测阶段记录的模型令牌用量。
     * 上游为 EvaluationServiceImpl.applyRewriteMetrics。
     *
     * @param stages 需要本方法按序读取或筛选的 List&lt;RagQueryOutcome.StageTiming&gt; 数据
     * @param name 记录对外显示的名称
     * @return 本次计算或查询得到的 long 结果
     */
    private static long stageTokens(List<RagQueryOutcome.StageTiming> stages, String name) {
        return stages.stream().filter(s -> name.equals(s.getStage()))
                .map(RagQueryOutcome.StageTiming::getTotalTokens)
                .filter(java.util.Objects::nonNull).mapToLong(Integer::longValue).findFirst().orElse(0L);
    }

    /**
     * 提取需要纳入评测聚合的阶段数值。
     * 上游为 EvaluationServiceImpl.evaluateRetrieval、EvaluationServiceImpl.evaluateBatch。
     * 下游为 PythonRagPipeline.query、PythonRagPipeline.retrieveOnly。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param question 用户问题正文
     * @param history 按时间顺序提供的对话历史
     * @param topK 保留的候选数量上限
     * @param withGeneration 本方法计算或判断使用的 withGeneration 值
     * @return 本次计算或查询得到的 EvalSample 结果
     */
    private EvalSample sample(Long knowledgeBaseId, String question,
                              List<com.pony.knowledge.service.rag.query.ChatMessage> history,
                              int topK, boolean withGeneration) {
        List<com.pony.knowledge.service.rag.query.ChatMessage> hist = history == null ? List.of() : history;

        RagQueryOutcome out = withGeneration
                ? pythonRagPipeline.query(List.of(knowledgeBaseId), question, hist, topK)
                : pythonRagPipeline.retrieveOnly(List.of(knowledgeBaseId), question, hist, topK);
        List<RagQueryOutcome.StageTiming> stages =
                out.getStages() == null ? List.of() : out.getStages();
        long retrievalMs = RETRIEVAL_STAGES.stream().mapToLong(s -> stageMs(stages, s)).sum();
        List<ChunkSearchResultDTO> retrievedChunks = out.getSources() == null ? List.of()
                : out.getSources().stream().map(EvaluationServiceImpl::toDto).toList();
        List<ChunkSearchResultDTO> evidenceChunks = out.getEvidences() == null ? List.of()
                : out.getEvidences().stream().map(EvaluationServiceImpl::toDto).toList();
        return new EvalSample(retrievedChunks, evidenceChunks,
                out.getAnswer(),
                out.getTokenCost() == null ? 0 : out.getTokenCost(),
                retrievalMs, stageMs(stages, "generate"), stages, out.getRewrittenQuery(),
                out.getCompression(), out.getOutcome(), out.getReasonCode(), out.getFailedStage(),
                out.getRequestId(), out.getEvidenceDecision());
    }

    /**
     * 从上游问答结果填充压缩结构、耗时与令牌数据。
     * 上游为 EvaluationServiceImpl.evaluateRetrieval、EvaluationServiceImpl.evaluateBatch。
     * 下游为 EvalSample.compression、EvalSample.stages。
     *
     * @param s 本次操作使用的 EvalSample 组件或记录
     * @param r 携带当前操作参数的 EvaluationResultDTO 数据
     */
    private void applyCompressionMetrics(EvalSample s, EvaluationResultDTO r) {
        RagQueryOutcome.CompressionInfo c = s.compression();
        if (c != null) {
            r.setCompressionMode(c.getMode());
            r.setTokensBefore(c.getTokensBefore());
            r.setTokensAfter(c.getTokensAfter());
            r.setCompressionRatio(c.getRatio());
        }
        r.setCompressionLatencyMs(stageMs(s.stages(), "compression"));
        r.setCompressionTokenCost(0);      // extractive 纯本地抽取，不花 LLM token
    }

    /**
     * 从上游阶段记录填充改写耗时、用量和改写结果。
     * 上游为 EvaluationServiceImpl.evaluateRetrieval、EvaluationServiceImpl.evaluateBatch。
     * 下游为 EvalSample.stages、EvalSample.rewrittenQuery。
     *
     * @param s 本次操作使用的 EvalSample 组件或记录
     * @param r 携带当前操作参数的 EvaluationResultDTO 数据
     * @param question 用户问题正文
     * @return 取得的 String 结果，未找到有效值时可能为空
     */
    private String applyRewriteMetrics(EvalSample s, EvaluationResultDTO r, String question) {
        r.setRewriteLatencyMs(stageMs(s.stages(), "rewrite"));
        r.setRewriteTokenCost((int) stageTokens(s.stages(), "rewrite"));
        r.setRewrittenQuery(s.rewrittenQuery() == null ? question : s.rewrittenQuery());
        return null;
    }

    /**
     * 将上游执行状态、请求关联和准入策略复制给评测报告。
     * 上游为 EvaluationServiceImpl.evaluateRetrieval、EvaluationServiceImpl.evaluateBatch。
     * 下游为 EvalSample.outcome、EvalSample.reasonCode。
     *
     * @param sample 本次操作使用的 EvalSample 组件或记录
     * @param result 供当前流程映射、写入或判断的结果
     */
    private static void applyOutcomeAndEvidence(EvalSample sample, EvaluationResultDTO result) {
        result.setOutcome(sample.outcome());
        result.setReasonCode(sample.reasonCode());
        result.setFailedStage(sample.failedStage());
        result.setRequestId(sample.requestId());
        result.setEvidenceDecision(sample.evidenceDecision());
        result.setEvidencePolicyVersion(sample.evidenceDecision() == null
                ? null : sample.evidenceDecision().getPolicyVersion());
    }

    /**
     * 按统计键累加本次评测事件。
     * 上游为 EvaluationServiceImpl.incrementDecision、EvaluationServiceImpl.aggregate。
     *
     * @param counts 含当前操作所需属性的映射
     * @param value 待校验或转换的值
     */
    private static void increment(Map<String, Integer> counts, String value) {
        if (value != null && !value.isBlank()) {
            counts.merge(value, 1, Integer::sum);
        }
    }

    /**
     * 按准入状态累计证据决策结果。
     * 上游为 EvaluationServiceImpl.aggregate。
     * 下游为 EvaluationServiceImpl.increment、RagEvidenceDecision.getState。
     *
     * @param counts 含当前操作所需属性的映射
     * @param decision 本次操作使用的 RagEvidenceDecision 组件或记录
     */
    private static void incrementDecision(Map<String, Integer> counts, RagEvidenceDecision decision) {
        if (decision != null) {
            increment(counts, decision.getState());
        }
    }

    /**
     * 仅把有效的非空策略值加入聚合集合。
     * 上游为 EvaluationServiceImpl.aggregate。
     *
     * @param values 需要本方法按序读取或筛选的 Set&lt;String&gt; 数据
     * @param value 待校验或转换的值
     */
    private static void addNonBlank(Set<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
    }

    /**
     * 归集单题检索、证据、生成信息与指标，输出评测接口视图。
     * 下游为 RagEvidence.getChunkId、RagEvidence.getDocumentId。
     *
     * @param e 本次执行捕获的异常
     * @return 纯检索返回的切片、知识库、位置与距离信息，以 ChunkSearchResultDTO 返回
     */
    private static ChunkSearchResultDTO toDto(RagEvidence e) {
        ChunkSearchResultDTO dto = new ChunkSearchResultDTO();
        dto.setId(e.getChunkId());
        dto.setDocumentId(e.getDocumentId());
        dto.setKnowledgeBaseId(e.getKnowledgeBaseId());
        dto.setChunkIndex(e.getChunkIndex());
        dto.setContent(e.getContent());
        dto.setDistance(e.getDistance());
        dto.setHeadingPath(e.getHeadingPath());
        dto.setPageNo(e.getPageNo());
        dto.setSourceType(e.getSourceType());
        return dto;
    }

    /**
     * 基于标注相关 ID 评估检索候选并记录证据和耗时。
     * 上游为 EvaluationController.evaluateRetrieval。
     * 下游为 RetrievalEvaluator.evaluate、AiServiceClient.evaluateGenerate。
     *
     * @param request 当前调用的请求参数
     * @return 单题检索、证据、生成评测指标及运行用量，以 EvaluationResultDTO 返回
     */
    @Override
    public EvaluationResultDTO evaluateRetrieval(EvaluationRequestDTO request) {
        Long knowledgeBaseId = request.getKnowledgeBaseId();

        // 评测只允许知识库创建者，读取或问答权限不能替代评测授权。
        requireEvaluationAccess(knowledgeBaseId);

        // 2. 取样本（检索 + 生成）：一次调用 Python /rag/query（生成侧也由 Python 完成）
        EvalSample sample = sample(knowledgeBaseId, request.getQuestion(),
                request.getHistory(), DEFAULT_TOP_K, true);
        List<ChunkSearchResultDTO> results = sample.retrievedChunks();
        List<ChunkSearchResultDTO> evidenceChunks = sample.evidenceChunks();

        List<Long> retrievedIds = results.stream()
                .map(ChunkSearchResultDTO::getId)
                .toList();

        List<Long> relevantIds = request.getRelevantChunkIds() == null
                ? List.of()
                : request.getRelevantChunkIds();

        // 3. 原生 Java 计算 recall@k / precision@k / precision@n / mrr / hit@k（收回算术指标，不再发 Python）
        RetrievalEvaluationResult metrics =
                retrievalEvaluator.evaluate(retrievedIds, relevantIds, DEFAULT_TOP_K);

        // 4. 生成证据的纯 content 列表（留给裁判）；生成本身已由 sample() 完成
        List<String> contexts = evidenceChunks.stream()
                .map(ChunkSearchResultDTO::getContent)
                .toList();

        // 5. 调 Python LLM 裁判：faithfulness / correctness / relevance（含裁判 token 数）
        AiServiceClient.JudgeResponse judge =
                aiServiceClient.evaluateGenerate(request.getQuestion(), sample.answer(), contexts,
                        request.getGroundTruth());

        // 6. 组装结果
        EvaluationResultDTO result = new EvaluationResultDTO();
        result.setVersion(retrievalVersion);
        applyOutcomeAndEvidence(sample, result);
        result.setQuestion(request.getQuestion());
        result.setDifficulty(request.getDifficulty());
        result.setType(request.getType());
        result.setRecallAtK(metrics.getRecallAtK());
        result.setPrecisionAtK(metrics.getPrecisionAtK());
        result.setPrecisionAtN(metrics.getPrecisionAtN());
        result.setRedundancy(RedundancyMetric.meanPairwiseJaccard(contexts));
        result.setMrr(metrics.getMrr());
        result.setHitAtK(metrics.getHitAtK());
        result.setFaithfulness(judge.getFaithfulness());
        result.setAnswerCorrectness(judge.getCorrectness());
        result.setAnswer(sample.answer());
        result.setRetrievalLatencyMs(sample.retrievalMs());
        result.setGenerationLatencyMs(sample.generationMs());
        // rerank 耗时：从 python 回传的 stages[] 取
        result.setRerankLatencyMs(stageMs(sample.stages(), "rerank"));
        String rewriteReason = applyRewriteMetrics(sample, result, request.getQuestion());
        applyCompressionMetrics(sample, result);
        result.setLatencyMs(sample.retrievalMs() + sample.generationMs());
        fillCostAndTrace(result, sample.answer(), judge, sample.generationTokens(),
                rewriteReason, results);
        return result;
    }

    /**
     * 根据阶段用量计算成本并补齐可供评测查看的执行轨迹。
     * 上游为 EvaluationServiceImpl.evaluateRetrieval、EvaluationServiceImpl.evaluateBatch。
     * 下游为 EvaluationResultDTO.getRewriteTokenCost、EvaluationResultDTO.getCompressionTokenCost。
     *
     * @param r 携带当前操作参数的 EvaluationResultDTO 数据
     * @param answer 生成的答案
     * @param judge 本次操作所用的 AiServiceClient.JudgeResponse 数据
     * @param generationTokens 本方法计算或判断使用的 generationTokens 值
     * @param rewriteReason 本方法计算或判断使用的 rewriteReason 值
     * @param chunks 待处理的切片列表
     */
    private void fillCostAndTrace(EvaluationResultDTO r, String answer,
            AiServiceClient.JudgeResponse judge, int generationTokens, String rewriteReason,
            List<ChunkSearchResultDTO> chunks) {
        int rewriteTokens = r.getRewriteTokenCost() == null ? 0 : r.getRewriteTokenCost();
        int compressTokens = r.getCompressionTokenCost() == null ? 0 : r.getCompressionTokenCost();
        int judgeTokens = judge.getTokenCost() == null ? 0 : judge.getTokenCost();
        int total = rewriteTokens + compressTokens + generationTokens + judgeTokens;
        r.setGenerationTokenCost(generationTokens);
        r.setJudgeTokenCost(judgeTokens);
        r.setLlmTokensTotal(total);
        r.setCostEstimateUsd(total / 1_000_000.0 * llmPricePer1mTokens);
        r.setAnswerRelevance(judge.getRelevance());
        r.setContextRelevance(judge.getContextRelevance());

        RagTraceDTO trace = new RagTraceDTO();
        trace.setQuestion(r.getQuestion());
        trace.setRewrittenQuery(r.getRewrittenQuery());
        trace.setRewritten(!r.getQuestion().equals(r.getRewrittenQuery()));
        trace.setRewriteReason(rewriteReason);
        trace.setCompressionMode(r.getCompressionMode());
        trace.setTokensBefore(r.getTokensBefore());
        trace.setTokensAfter(r.getTokensAfter());
        trace.setCompressionRatio(r.getCompressionRatio());
        trace.setRetrievalLatencyMs(r.getRetrievalLatencyMs());
        trace.setRerankLatencyMs(r.getRerankLatencyMs());
        trace.setRewriteLatencyMs(r.getRewriteLatencyMs());
        trace.setCompressionLatencyMs(r.getCompressionLatencyMs());
        trace.setGenerationLatencyMs(r.getGenerationLatencyMs());
        trace.setRewriteTokenCost(rewriteTokens);
        trace.setGenerationTokenCost(generationTokens);
        trace.setJudgeTokenCost(judgeTokens);
        trace.setAnswer(answer);
        trace.setFaithfulness(r.getFaithfulness());
        trace.setAnswerCorrectness(r.getAnswerCorrectness());
        trace.setAnswerRelevance(judge.getRelevance());
        trace.setRetrievedChunks(chunks.stream()
                .map(c -> {
                    RagTraceDTO.TraceChunk t = new RagTraceDTO.TraceChunk();
                    t.setChunkId(c.getId());
                    t.setDocumentId(c.getDocumentId());
                    t.setKnowledgeBaseId(c.getKnowledgeBaseId());
                    t.setChunkIndex(c.getChunkIndex());
                    t.setDistance(c.getDistance());
                    return t;
                })
                .toList());
        r.setTrace(trace);
    }

    /**
     * 按请求用例运行检索或生成评测，再输出总体和分组结果。
     * 上游为 EvaluationController.evaluateBatch。
     * 下游为 EvaluationBatchRequestDTO.isEvaluateGeneration、RetrievalEvaluator.evaluate。
     *
     * @param request 当前调用的请求参数
     * @return 本次得到的 EvaluationBatchResultDTO 实例
     */
    @Override
    public EvaluationBatchResultDTO evaluateBatch(EvaluationBatchRequestDTO request) {
        int k = request.getK() <= 0 ? DEFAULT_TOP_K : request.getK();
        List<EvaluationRequestDTO> cases = request.getCases() == null ? List.of() : request.getCases();

        // 先核对整批资源，避免后续样本越权时前面的样本已调用检索或模型。
        cases.forEach(c -> requireEvaluationAccess(c.getKnowledgeBaseId()));

        // 1. 逐条检索并计算检索指标
        List<EvaluationResultDTO> perCase = new ArrayList<>();

        for (EvaluationRequestDTO c : cases) {
            // 取样：一次调用 Python /rag/query（决策文档 §5.12.10 第 4 步）。
            // 这是本类唯一的数据来源入口——指标算法一行未改，只换取样来源。
            EvalSample s = sample(c.getKnowledgeBaseId(), c.getQuestion(), c.getHistory(), k,
                    request.isEvaluateGeneration());
            List<ChunkSearchResultDTO> results = s.retrievedChunks();
            List<ChunkSearchResultDTO> evidenceChunks = s.evidenceChunks();
            List<Long> retrievedIds = results.stream()
                    .map(ChunkSearchResultDTO::getId)
                    .toList();
            List<Long> relevantIds = c.getRelevantChunkIds() == null
                    ? List.of()
                    : c.getRelevantChunkIds();

            EvaluationResultDTO r = new EvaluationResultDTO();
            r.setQuestion(c.getQuestion());
            r.setDifficulty(c.getDifficulty());
            r.setType(c.getType());
            applyOutcomeAndEvidence(s, r);
            r.setRetrievalLatencyMs(s.retrievalMs());
            RetrievalEvaluationResult m = retrievalEvaluator.evaluate(retrievedIds, relevantIds, k);
            r.setRecallAtK(m.getRecallAtK());
            r.setPrecisionAtK(m.getPrecisionAtK());
            r.setPrecisionAtN(m.getPrecisionAtN());
            r.setRedundancy(RedundancyMetric.meanPairwiseJaccard(
                    results.stream().map(ChunkSearchResultDTO::getContent).toList()));
            r.setMrr(m.getMrr());
            r.setNdcgAtK(m.getNdcgAtK());
            r.setHitAtK(m.getHitAtK());
            String rewriteReason = applyRewriteMetrics(s, r, c.getQuestion());
            // 压缩指标：python 档读回传的 compression + stages[compression]；分段档读 ThreadLocal
            applyCompressionMetrics(s, r);

            // evaluateGeneration=true 时：生成答案 + LLM 裁判，给出 Answer Quality / Faithfulness
            // （生成本身已由 sample() 完成，这里只做裁判与回填）
            if (request.isEvaluateGeneration()) {
                List<String> contexts = evidenceChunks.stream()
                        .map(ChunkSearchResultDTO::getContent)
                        .toList();
                AiServiceClient.JudgeResponse judge = aiServiceClient.evaluateGenerate(
                        c.getQuestion(), s.answer(), contexts,
                        c.getGroundTruth() == null ? "" : c.getGroundTruth());
                r.setAnswer(s.answer());
                r.setFaithfulness(judge.getFaithfulness());
                r.setAnswerCorrectness(judge.getCorrectness());
                r.setGenerationLatencyMs(s.generationMs());
                r.setLatencyMs(s.retrievalMs() + s.generationMs());
                fillCostAndTrace(r, s.answer(), judge, s.generationTokens(), rewriteReason, results);
            }
            perCase.add(r);
        }

        // 2. 聚合：总指标 + 按难度 + 按类型
        EvaluationBatchResultDTO batch = new EvaluationBatchResultDTO();
        batch.setVersion(retrievalVersion);
        batch.setK(k);
        batch.setTotalCases(perCase.size());
        batch.setPerCase(perCase);
        batch.setTotal(aggregate(perCase));
        batch.setByDifficulty(groupAndAggregate(perCase, EvaluationResultDTO::getDifficulty));
        batch.setByType(groupAndAggregate(perCase, EvaluationResultDTO::getType));
        return batch;
    }

    /**
     * 交给回归评估器计算两个批量评测结果的差异。
     * 上游为 EvaluationController.regressionCompare。
     * 下游为 RegressionEvaluator.compare。
     *
     * @param request 当前调用的请求参数
     * @return 一次评测回归的整体判定和各指标差异，以 RegressionVerdict 返回
     */
    @Override
    public RegressionVerdict regressionCompare(RegressionCompareRequestDTO request) {
        return regressionEvaluator.compare(
                request.getBaseline(),
                request.getCurrent(),
                request.getThreshold());
    }

    /**
     * 按有效用例取平均并汇总错误、准入策略和费用。
     * 上游为 EvaluationServiceImpl.evaluateBatch、EvaluationServiceImpl.groupAndAggregate。
     * 下游为 EvaluationServiceImpl.increment、EvaluationServiceImpl.incrementDecision。
     *
     * @param items 需要本方法按序读取或筛选的 List&lt;EvaluationResultDTO&gt; 数据
     * @return 评测的成功与失败分布、质量、耗时和用量汇总，以 MetricAggregateDTO 返回
     */
    static MetricAggregateDTO aggregate(List<EvaluationResultDTO> items) {
        MetricAggregateDTO agg = new MetricAggregateDTO();
        agg.setCaseCount(items.size());
        double recall = 0, precision = 0, precisionN = 0, mrr = 0, hit = 0, ndcg = 0, latencyMsSum = 0;
        double rewriteMs = 0, compressionMs = 0, ratioSum = 0, redundancySum = 0;
        double faithfulnessSum = 0, correctnessSum = 0, relevanceSum = 0, generationMsSum = 0;
        double llmTokensSum = 0, costSum = 0;
        int rewriteTokens = 0, compressionTokens = 0, compressionCount = 0;
        // 判分（judge）三项各自计数：裁判对三个 prompt 独立打分，任一项解析失败
        // 只让该项为 null，与另两项的缺失并不绑定。故均值必须按各自非空条数取，
        // 不能共用一个计数（2026-10-23 修正：correctness 原先误用 faithfulness 的计数）。
        int faithfulnessCount = 0, correctnessCount = 0, relevanceCount = 0;
        Map<String, Integer> outcomeCounts = new LinkedHashMap<>();
        Map<String, Integer> evidenceDecisionCounts = new LinkedHashMap<>();
        Set<String> evidencePolicyVersions = new LinkedHashSet<>();

        for (EvaluationResultDTO it : items) {
            increment(outcomeCounts, it.getOutcome());
            incrementDecision(evidenceDecisionCounts, it.getEvidenceDecision());
            addNonBlank(evidencePolicyVersions, it.getEvidencePolicyVersion());
            recall += it.getRecallAtK() == null ? 0 : it.getRecallAtK();
            precision += it.getPrecisionAtK() == null ? 0 : it.getPrecisionAtK();
            precisionN += it.getPrecisionAtN() == null ? 0 : it.getPrecisionAtN();
            redundancySum += it.getRedundancy() == null ? 0 : it.getRedundancy();
            mrr += it.getMrr() == null ? 0 : it.getMrr();
            hit += it.getHitAtK() == null ? 0 : it.getHitAtK();
            ndcg += it.getNdcgAtK() == null ? 0 : it.getNdcgAtK();
            latencyMsSum += it.getLatencyMs() == null ? 0 : it.getLatencyMs();
            rewriteMs += it.getRewriteLatencyMs() == null ? 0 : it.getRewriteLatencyMs();
            rewriteTokens += it.getRewriteTokenCost() == null ? 0 : it.getRewriteTokenCost();
            compressionMs += it.getCompressionLatencyMs() == null ? 0 : it.getCompressionLatencyMs();
            compressionTokens += it.getCompressionTokenCost() == null ? 0 : it.getCompressionTokenCost();
            generationMsSum += it.getGenerationLatencyMs() == null ? 0 : it.getGenerationLatencyMs();
            llmTokensSum += it.getLlmTokensTotal() == null ? 0 : it.getLlmTokensTotal();
            costSum += it.getCostEstimateUsd() == null ? 0 : it.getCostEstimateUsd();
            if (it.getCompressionRatio() != null) {
                ratioSum += it.getCompressionRatio();
                compressionCount++;
            }
            if (it.getFaithfulness() != null) {
                faithfulnessSum += it.getFaithfulness();
                faithfulnessCount++;
            }
            if (it.getAnswerCorrectness() != null) {
                correctnessSum += it.getAnswerCorrectness();
                correctnessCount++;
            }
            if (it.getAnswerRelevance() != null) {
                relevanceSum += it.getAnswerRelevance();
                relevanceCount++;
            }
        }
        int n = Math.max(items.size(), 1);
        agg.setRecallAtK(recall / n);
        agg.setPrecisionAtK(precision / n);
        agg.setPrecisionAtN(precisionN / n);
        agg.setRedundancy(redundancySum / n);
        agg.setMrr(mrr / n);
        agg.setHitAtK(hit / n);
        agg.setNdcgAtK(ndcg / n);
        agg.setAvgLatencyMs(latencyMsSum / n);
        agg.setRewriteLatencyMs(rewriteMs / n);
        agg.setRewriteTokenCost(rewriteTokens);
        agg.setCompressionLatencyMs(compressionMs / n);
        agg.setCompressionTokenCost(compressionTokens);
        agg.setCompressionRatio(compressionCount == 0 ? null : ratioSum / compressionCount);
        agg.setGenerationLatencyMs(generationMsSum / n);
        agg.setFaithfulness(faithfulnessCount == 0 ? null : faithfulnessSum / faithfulnessCount);
        agg.setAnswerCorrectness(correctnessCount == 0 ? null : correctnessSum / correctnessCount);
        agg.setAnswerRelevance(relevanceCount == 0 ? null : relevanceSum / relevanceCount);
        agg.setLlmTokensTotal(llmTokensSum / n);
        agg.setCostEstimateUsd(costSum / n);
        agg.setOutcomeCounts(outcomeCounts);
        agg.setEvidenceDecisionCounts(evidenceDecisionCounts);
        agg.setEvidencePolicyVersions(List.copyOf(evidencePolicyVersions));
        return agg;
    }

    /**
     * 按请求维度对逐题结果分组后生成每组指标。
     * 上游为 EvaluationServiceImpl.evaluateBatch。
     * 下游为 Function.apply。
     *
     * @param items 需要本方法按序读取或筛选的 List&lt;EvaluationResultDTO&gt; 数据
     * @param keyFn 由此处在对应上下文或作用域内执行的 Function&lt;EvaluationResultDTO, String&gt; 回调
     * @return 由本方法收集或转换的键值映射
     */
    private Map<String, MetricAggregateDTO> groupAndAggregate(
            List<EvaluationResultDTO> items,
            Function<EvaluationResultDTO, String> keyFn) {
        Map<String, List<EvaluationResultDTO>> groups = new LinkedHashMap<>();

        for (EvaluationResultDTO it : items) {
            String key = keyFn.apply(it);
            if (key == null || key.isBlank()) {
                key = "unknown";
            }
            groups.computeIfAbsent(key, x -> new ArrayList<>()).add(it);
        }
        Map<String, MetricAggregateDTO> result = new LinkedHashMap<>();
        groups.forEach((key, list) -> result.put(key, aggregate(list)));
        return result;
    }
}
