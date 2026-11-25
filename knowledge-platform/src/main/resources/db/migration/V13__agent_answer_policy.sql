ALTER TABLE agent_profile
    ADD COLUMN answer_policy VARCHAR(16) NOT NULL DEFAULT 'grounded'
    CHECK (answer_policy IN ('grounded', 'general'));

COMMENT ON COLUMN agent_profile.answer_policy IS 'grounded requires admitted evidence consumed by the model; general permits direct answers';
