-- Track actual processing claims across manual retry cycles. Historical claims lost when
-- auto_retry_count was reset cannot be reconstructed; preserve the count still recorded today.
ALTER TABLE document_task
    ADD COLUMN total_attempt_count INTEGER NOT NULL DEFAULT 0;

UPDATE document_task
SET total_attempt_count = COALESCE(auto_retry_count, 0);
