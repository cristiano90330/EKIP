-- Explicit user-owned model configuration; credentials never live in JSON config.
CREATE TABLE managed_model (
    id varchar(36) PRIMARY KEY,
    owner_id bigint NOT NULL REFERENCES sys_user(id) ON DELETE CASCADE,
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    credential_version bigint NOT NULL DEFAULT 0 CHECK (credential_version >= 0),
    semantic_fingerprint varchar(64) NOT NULL,
    config jsonb NOT NULL,
    credential_ciphertext text,
    created_at timestamp NOT NULL DEFAULT now(),
    updated_at timestamp NOT NULL DEFAULT now()
);
CREATE INDEX idx_managed_model_owner ON managed_model(owner_id);
CREATE TABLE model_purpose_binding (
    owner_id bigint NOT NULL REFERENCES sys_user(id) ON DELETE CASCADE,
    purpose varchar(32) NOT NULL CHECK (purpose IN ('generate','rewrite','agent','summarize','judge','evidence_support','embedding','rerank')),
    model_id varchar(36) NOT NULL REFERENCES managed_model(id),
    PRIMARY KEY (owner_id,purpose)
);
CREATE INDEX idx_model_purpose_model ON model_purpose_binding(model_id);
ALTER TABLE agent_profile ADD COLUMN chat_model_id varchar(36) REFERENCES managed_model(id);
