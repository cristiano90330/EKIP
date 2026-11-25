ALTER TABLE document_task
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN lease_deadline_at TIMESTAMPTZ,
    ADD COLUMN retry_not_before TIMESTAMPTZ,
    ADD CONSTRAINT ck_document_task_lease CHECK (
        (lease_expires_at IS NULL AND lease_deadline_at IS NULL)
        OR (status = 1 AND lease_expires_at IS NOT NULL AND lease_deadline_at IS NOT NULL
            AND lease_expires_at <= lease_deadline_at AND retry_not_before IS NULL)
    ),
    ADD CONSTRAINT ck_document_task_retry_window CHECK (retry_not_before IS NULL OR status IN (0, 1));
