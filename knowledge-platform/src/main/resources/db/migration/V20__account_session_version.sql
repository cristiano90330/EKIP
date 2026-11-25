ALTER TABLE sys_user
    ADD COLUMN session_version bigint NOT NULL DEFAULT 0;
