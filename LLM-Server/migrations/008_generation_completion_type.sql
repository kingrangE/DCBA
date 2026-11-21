ALTER TABLE generation_task
    ADD COLUMN IF NOT EXISTS completion_type VARCHAR(24) NOT NULL DEFAULT 'created';
