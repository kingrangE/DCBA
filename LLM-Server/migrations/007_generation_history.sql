ALTER TABLE generation_task
    ADD COLUMN IF NOT EXISTS requester_id BIGINT NULL,
    ADD COLUMN IF NOT EXISTS completed_at DATETIME(6) NULL,
    ADD COLUMN IF NOT EXISTS read_at DATETIME(6) NULL,
    ADD INDEX IF NOT EXISTS idx_generation_requester_completed (requester_id, completed_at, task_id);

CREATE TABLE IF NOT EXISTS exercise_generation_history (
    exercise_id BIGINT NOT NULL PRIMARY KEY,
    subject VARCHAR(30) NOT NULL,
    level VARCHAR(8) NOT NULL,
    concept VARCHAR(100) NOT NULL,
    objective VARCHAR(240) NOT NULL,
    question_type VARCHAR(40) NOT NULL,
    rules_version VARCHAR(20) NOT NULL,
    KEY idx_generation_history_scope (subject, level, exercise_id),
    FOREIGN KEY (exercise_id) REFERENCES exercise(id) ON DELETE CASCADE
) ENGINE=InnoDB;
