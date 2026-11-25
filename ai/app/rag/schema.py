"""定义检索问答请求、候选与证据、阶段统计及结果协议。"""

from ai.app.models.schema import ModelExecution
from pydantic import BaseModel, Field
from typing import Literal, Optional

from ai.app.query.schema import ChatMessage

VALID_COMPRESSION_MODES = ("none", "extractive")
CompressionMode = Literal["none", "extractive"]


class ContextItem(BaseModel):
    """生成输入中的一条证据内容及其可选来源信息。"""

    content: str

    # 章节路径（如 "3.1 > 3.1.2"），可为 null
    headingPath: Optional[str] = None

    # 页码（从 1 开始），可为 null
    pageNo: Optional[int] = None

    # 来源类型（paragraph / table / heading 等），可为 null
    sourceType: Optional[str] = None

    # 文档公开文件名，作为裁剪后证据的来源标识。
    sourceName: Optional[str] = None


class DocumentReadRequest(BaseModel):
    """Opt-in sequential reading; offsets count non-parent chunks and Unicode characters."""
    offset: int = 0
    contentOffset: int = 0
    endOffset: Optional[int] = None
    aroundChunkId: Optional[int] = None
    contextRadius: int = 1
    windowChars: int = 1200


class DocumentReadPage(BaseModel):
    offset: int
    contentOffset: int
    endOffset: Optional[int] = None
    nextOffset: Optional[int] = None
    nextContentOffset: Optional[int] = None
    hasMore: bool = False
    scannedChars: int = 0


class RagQueryRequest(BaseModel):
    modelExecution: ModelExecution | None = None

    """Java 已授权的查询请求；Python 执行检索与生成并返回实际执行信息。"""

    # 编排实现与检索策略分别选择；省略时保留既有固定版。
    pipelineEngine: str = "fixed"

    # Java 解析出的可访问知识库范围；空集合不触发全库检索。
    knowledgeBaseIds: list[int]

    question: str

    # Agent may supply a focused retrieval hint while question retains the user's full intent.
    retrievalQuery: Optional[str] = None

    # 对话历史（缺省空 = 单轮）；进 rewrite 与 generate 复用 query 侧的 ChatMessage。
    history: list[ChatMessage] = Field(default_factory=list)

    # 最终返回的证据条数（Java 侧 chat 默认 5）。
    topK: int = Field(default=5, strict=True)

    # 检索策略：
    #   baseline | hybrid | hybrid-rerank | hybrid-rerank-rewrite
    version: str = "hybrid-rerank-rewrite"

    # Omitted by older callers to keep the deployment policy.
    evidenceMode: Literal["score", "support"] | None = None

    # Rerank 前的召回规模 Top-N（对齐 Java retrieval.recall-n，默认 20）。
    recallN: int = 20

    # RRF 融合常量 k（score = Σ 1/(k + rank)），与 Java 配置一致。
    rrfK: int = 60

    # 父子块 small-to-big（对齐 Java rag.small-to-big.enabled，默认 false）：
    # 命中子块后回填父块内容、按父块去重（生成侧后处理，入库侧仍在 Java）。
    smallToBigEnabled: bool = False

    # 关闭生成时仍按所选检索策略返回证据。
    generate: bool = True

    # 显式 opt-in：generate=false 时继续执行证据准入与后处理，但不生成答案。
    # 未传此字段的旧 generate=false 调用方仍只拿到原始候选（evidenceDecision=bypassed）。
    admitEvidence: bool = False

    # 仅 admitEvidence=true 时有效：把检索限定到 Java 已核验归属的单篇文档。
    # 向量与关键词 SQL 都与知识库范围一起绑定该值，不做返回后过滤。
    documentId: Optional[int] = None

    # 与 documentId/admitEvidence 同用；先确定一段有界原文窗口，再执行既有重排、准入与后处理。
    documentRead: Optional[DocumentReadRequest] = None

    # Java 按请求传入后处理选项；Python 不以独立环境变量覆盖。
    # 上下文压缩档位：none / extractive。
    compressionMode: CompressionMode = "none"

    # 压缩后的上下文 token 预算（对齐 Java `rag.token-budget`）。extractive 是**硬预算**：
    # tokensAfter <= tokenBudget 是保证，不是「尽量」。
    tokenBudget: int = 1500

    # MMR 去冗余（对齐 Java `rag.mmr.enabled`）：只在 rerank 档生效（score 是 0~1 相关度）。
    mmrEnabled: bool = False

    mmrLambda: float = 0.7

    # Java supplies the query-only deadline; Python clamps it to RAG_QUERY_DEADLINE_MS.
    # Ingest/parse calls use their own budgets and never inherit this value.
    budgetMs: Optional[int] = None


class RagQuerySource(BaseModel):
    """返回给 Java 的一条证据，字段对齐 Java RagEvidence / SourceDTO。"""
    chunkId: int
    documentId: int
    knowledgeBaseId: Optional[int] = None
    chunkIndex: int
    content: str
    score: Optional[float] = None
    scoreSource: str = "none"
    scorer: str = "none"
    distance: Optional[float] = None
    headingPath: Optional[str] = None
    pageNo: Optional[int] = None
    sourceType: Optional[str] = None

    # 仅连续阅读窗口有值，定位本段在原 chunk 中的起始 Unicode 字符。
    contentOffset: Optional[int] = None


class RagQueryStage(BaseModel):
    """RAG 阶段的状态、耗时和 token 用量，字段由 Java 写入查询 Trace。"""
    # 阶段短名：rewrite / vector_retrieval / keyword_retrieval / fusion / rerank / compression / generate
    stage: str

    durationMs: int

    success: bool = True

    error: str = ""

    # 该阶段是否降级（如某路召回超时按空结果参与 RRF）
    degraded: bool = False

    # LLM 阶段的 token 用量（rewrite / generate）；非 LLM 阶段为空
    totalTokens: Optional[int] = None


class RagQueryCompression(BaseModel):
    """后处理前后的 token 数和压缩比例。"""
    mode: str
    tokensBefore: int
    tokensAfter: int
    ratio: float


class RagEvidenceDecision(BaseModel):
    """Separate raw retrieval from the evidence that is allowed into generation."""

    state: str
    policyVersion: str
    scorer: str
    threshold: Optional[float] = None
    retrievedCount: int = 0
    acceptedCount: int = 0
    rejectedCount: int = 0
    reasonCode: str = "none"


class RagQueryResponse(BaseModel):
    """查询结果及实际执行策略、证据准入和阶段观测信息。"""

    # 实际执行版本，不能以调用方选择或 UI 当前值代替。
    pipelineEngine: str = "fixed"
    retrievalVersion: str = "hybrid-rerank-rewrite"
    evidenceMode: Literal["score", "support"] | None = None

    answer: str

    originalQuery: str

    rewrittenQuery: str

    # Raw, ranked retrievals remain the retrieval-evaluation contract.
    sources: list[RagQuerySource] = Field(default_factory=list)

    # Only these sources may enter generate. Empty when evidence is absent or unverified.
    evidenceSources: list[RagQuerySource] = Field(default_factory=list)

    # Explicit outcome prevents retrieval failure or an uncalibrated gate from looking like
    # a successful "no information found" answer.
    outcome: str = "success"
    reasonCode: str = "none"
    failedStage: Optional[str] = None
    requestId: str = ""
    retrievalStatus: str = "complete"
    evidenceDecision: Optional[RagEvidenceDecision] = None

    # 仅已完成的扫描页返回；故障没有新游标，调用方应重试原页。
    documentPage: Optional[DocumentReadPage] = None

    # 生成降级时 answer 为空，由 Java 负责面向用户的兜底文案。
    degraded: bool = False

    degradeReason: str = ""

    providerUsed: str = ""

    # 本次生成的 LLM token 用量；检索、改写和重排的 token 另行记录。
    tokenCost: int = 0

    # 各阶段耗时、token 与降级状态，由 Java 写入 RagTrace。
    stages: list[RagQueryStage] = []

    # 压缩统计（仅 compressionMode != none 时有值）：Java 侧填评测报告的压缩三列。
    compression: Optional[RagQueryCompression] = None
