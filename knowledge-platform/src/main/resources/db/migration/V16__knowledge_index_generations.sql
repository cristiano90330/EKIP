-- Model provenance is explicit. Historical document_chunk.embedding stays untouched,
-- but it is not assigned to a newly configured model without rebuilding.
CREATE TABLE embedding_space (
    id varchar(64) PRIMARY KEY,
    owner_id bigint NOT NULL REFERENCES sys_user(id),
    model_id varchar(36) NOT NULL REFERENCES managed_model(id),
    semantic_fingerprint varchar(64) NOT NULL,
    dimension integer NOT NULL CHECK (dimension IN (1024, 2560)),
    config_snapshot jsonb NOT NULL,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (owner_id, model_id, semantic_fingerprint)
);

CREATE TABLE knowledge_index_generation (
    id varchar(36) PRIMARY KEY,
    knowledge_base_id bigint NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    space_id varchar(64) NOT NULL REFERENCES embedding_space(id),
    state varchar(20) NOT NULL CHECK (state IN ('active', 'building', 'retired', 'failed', 'cancelled')),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    activated_at timestamp,
    UNIQUE (id, knowledge_base_id)
);
CREATE INDEX idx_index_generation_kb ON knowledge_index_generation(knowledge_base_id);

CREATE TABLE knowledge_index_binding (
    knowledge_base_id bigint PRIMARY KEY REFERENCES knowledge_base(id) ON DELETE CASCADE,
    active_generation_id varchar(36) REFERENCES knowledge_index_generation(id) ON DELETE SET NULL,
    maintenance_task_id varchar(36),
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE knowledge_index_rebuild_task (
    id varchar(36) PRIMARY KEY,
    knowledge_base_id bigint NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    generation_id varchar(36) NOT NULL REFERENCES knowledge_index_generation(id) ON DELETE CASCADE,
    previous_generation_id varchar(36),
    status varchar(20) NOT NULL CHECK (status IN ('waiting', 'processing', 'completed', 'failed', 'cancelled')),
    run_token varchar(36),
    lease_until timestamp,
    total_chunks bigint NOT NULL DEFAULT 0,
    completed_chunks bigint NOT NULL DEFAULT 0,
    last_chunk_id bigint NOT NULL DEFAULT 0,
    source_fingerprint varchar(64) NOT NULL,
    error_code varchar(64),
    error_message varchar(500),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at timestamp
);
CREATE INDEX idx_index_rebuild_claim ON knowledge_index_rebuild_task(status, lease_until, created_at);
CREATE UNIQUE INDEX uk_index_rebuild_active_kb ON knowledge_index_rebuild_task(knowledge_base_id)
    WHERE status IN ('waiting', 'processing');

ALTER TABLE document_chunk ADD CONSTRAINT uk_document_chunk_index_scope UNIQUE (id, knowledge_base_id);

CREATE TABLE document_chunk_vector_1024 (
    generation_id varchar(36) NOT NULL,
    chunk_id bigint NOT NULL,
    knowledge_base_id bigint NOT NULL,
    embedding halfvec(1024) NOT NULL,
    PRIMARY KEY (generation_id, chunk_id),
    FOREIGN KEY (generation_id, knowledge_base_id)
        REFERENCES knowledge_index_generation(id, knowledge_base_id) ON DELETE CASCADE,
    FOREIGN KEY (chunk_id, knowledge_base_id)
        REFERENCES document_chunk(id, knowledge_base_id) ON DELETE CASCADE
);
CREATE INDEX idx_chunk_vector_1024_scope ON document_chunk_vector_1024(knowledge_base_id, generation_id);
CREATE INDEX idx_chunk_vector_1024_hnsw ON document_chunk_vector_1024 USING hnsw (embedding halfvec_cosine_ops);

CREATE TABLE document_chunk_vector_2560 (
    generation_id varchar(36) NOT NULL,
    chunk_id bigint NOT NULL,
    knowledge_base_id bigint NOT NULL,
    embedding halfvec(2560) NOT NULL,
    PRIMARY KEY (generation_id, chunk_id),
    FOREIGN KEY (generation_id, knowledge_base_id)
        REFERENCES knowledge_index_generation(id, knowledge_base_id) ON DELETE CASCADE,
    FOREIGN KEY (chunk_id, knowledge_base_id)
        REFERENCES document_chunk(id, knowledge_base_id) ON DELETE CASCADE
);
CREATE INDEX idx_chunk_vector_2560_scope ON document_chunk_vector_2560(knowledge_base_id, generation_id);
CREATE INDEX idx_chunk_vector_2560_hnsw ON document_chunk_vector_2560 USING hnsw (embedding halfvec_cosine_ops);

-- The AI identity sees only retrieval data, never model credentials or job state.
GRANT SELECT ON TABLE document_chunk_vector_1024, document_chunk_vector_2560 TO rag_readonly;
