ALTER TABLE agent_profile
    ADD COLUMN memory_read_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN memory_write_enabled BOOLEAN NOT NULL DEFAULT TRUE;
