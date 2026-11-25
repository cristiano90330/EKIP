-- A document owns its chunks and processing tasks. Without these constraints, deleting a
-- document leaves retrievable content behind and an in-flight parser can recreate orphans.
-- Keep cleanup and constraint installation in the same Flyway transaction. Block writers
-- during this upgrade so a concurrent delete/insert cannot race with orphan cleanup.
LOCK TABLE document, document_task, document_chunk IN SHARE ROW EXCLUSIVE MODE;

DELETE FROM document_chunk c
WHERE NOT EXISTS (SELECT 1 FROM document d WHERE d.id = c.document_id);

DELETE FROM document_task t
WHERE NOT EXISTS (SELECT 1 FROM document d WHERE d.id = t.document_id);

ALTER TABLE document_chunk
    ADD CONSTRAINT fk_document_chunk_document
    FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE;

ALTER TABLE document_task
    ADD CONSTRAINT fk_document_task_document
    FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE;

-- The chunk-side document_id index already exists in V1. The task index prevents each
-- document delete from scanning the entire task table while checking/cascading the FK.
CREATE INDEX idx_document_task_document_id ON document_task(document_id);
