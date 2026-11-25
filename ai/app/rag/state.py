"""定义贯穿改写、检索、准入、后处理和生成各阶段的共享查询状态。"""

from dataclasses import dataclass, field

from ai.app.query.schema import ChatMessage
from ai.app.rag.postprocess import CompressionStats
from ai.app.rag.evidence_support import EvidenceSupportReview
from ai.app.rag.schema import DocumentReadPage, RagEvidenceDecision, RagQueryRequest, RagQueryResponse, RagQuerySource
from ai.app.retrieval.retriever import Candidate


@dataclass(frozen=True)
class QueryState:
    request: RagQueryRequest
    question: str
    query: str
    history: list[ChatMessage] = field(default_factory=list)
    candidates: list[Candidate] = field(default_factory=list)
    admitted: list[Candidate] = field(default_factory=list)
    sources: list[RagQuerySource] = field(default_factory=list)
    evidence_sources: list[RagQuerySource] = field(default_factory=list)
    decision: RagEvidenceDecision | None = None
    support_review: EvidenceSupportReview | None = None
    compression: CompressionStats | None = None
    retrieval_status: str = "complete"
    reason_code: str = "none"
    response: RagQueryResponse | None = None
    document_page: DocumentReadPage | None = None
