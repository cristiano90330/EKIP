-- Preserve the last terminal failure while a document is in a new manual retry cycle.
ALTER TABLE document_task
    ADD COLUMN error_code VARCHAR(64),
    ADD COLUMN last_error_message VARCHAR(1000),
    ADD COLUMN last_error_code VARCHAR(64);

UPDATE document_task
SET last_error_message = error_message,
    last_error_code = CASE WHEN error_message IS NULL THEN NULL ELSE 'LEGACY_FAILURE' END
WHERE error_message IS NOT NULL;
