-- Enforce ownership for new rows without changing historical data.
CREATE INDEX idx_document_knowledge_base_id ON document(knowledge_base_id);

ALTER TABLE document
    ADD CONSTRAINT fk_document_knowledge_base FOREIGN KEY (knowledge_base_id)
    REFERENCES knowledge_base(id) ON DELETE CASCADE NOT VALID;

ALTER TABLE knowledge_base_member
    ADD CONSTRAINT fk_knowledge_base_member_knowledge_base FOREIGN KEY (knowledge_base_id)
    REFERENCES knowledge_base(id) ON DELETE CASCADE NOT VALID;
