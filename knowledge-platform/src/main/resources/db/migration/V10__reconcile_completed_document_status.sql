-- One-time reconciliation for the pre-atomic completion path: task SUCCESS committed
-- before document COMPLETED, and a failed second write left document PROCESSING.
-- Freeze both tables for the migration so a retry/claim cannot race this decision.
LOCK TABLE document, document_task IN SHARE ROW EXCLUSIVE MODE;

UPDATE document d
SET status = 2
WHERE d.status = 1
  -- The API treats the highest task id as the current task. An older success must
  -- not overwrite the result of a later failed or active task.
  AND EXISTS (
      SELECT 1
      FROM document_task t
      WHERE t.document_id = d.id
        AND t.status = 2
        AND NOT EXISTS (
            SELECT 1 FROM document_task newer
            WHERE newer.document_id = d.id AND newer.id > t.id
        )
  )
  -- Historical multiple-task anomalies with any active worker remain recoverable.
  AND NOT EXISTS (
      SELECT 1 FROM document_task active
      WHERE active.document_id = d.id AND active.status IN (0, 1)
  );
