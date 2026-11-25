-- Deployment models are shared read-only records (owner_id IS NULL).
-- User-owned model rows retain their existing ownership and cascade behavior.
ALTER TABLE managed_model ALTER COLUMN owner_id DROP NOT NULL;
