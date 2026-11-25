-- 父子块 small-to-big（决策文档 §4 第 3 项）：命中子块、回填父块。
--
-- 设计（加法式，不改动既有子块的切块方式，已有文本锚点不受影响）：
--   子块照旧：有 embedding + search_vector，被向量/关键词检索命中；
--   父块新增：同一标题节内若干相邻子块的拼接，作为独立行存于 document_chunk，
--     source_type='parent'、embedding 与 search_vector 均为 NULL（故不会被检索命中），
--     子块经 parent_chunk_id 回指父块。生成侧据此「命中子块、回填父块」，
--     在同样的 token 预算里装进更完整的信息。
-- 开关：rag.small-to-big.enabled（默认 false）；父块尺寸上限 rag.small-to-big.parent-max-chars（默认 4096）。
ALTER TABLE document_chunk ADD COLUMN parent_chunk_id bigint;
CREATE INDEX idx_document_chunk_parent ON document_chunk (parent_chunk_id);
