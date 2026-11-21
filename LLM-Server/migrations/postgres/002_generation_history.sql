ALTER TABLE generation_task
    ADD COLUMN IF NOT EXISTS requester_id bigint,
    ADD COLUMN IF NOT EXISTS completed_at timestamp(6),
    ADD COLUMN IF NOT EXISTS read_at timestamp(6),
    ADD COLUMN IF NOT EXISTS completion_type varchar(24) NOT NULL DEFAULT 'created';

CREATE INDEX IF NOT EXISTS idx_generation_requester_completed ON generation_task(requester_id, completed_at, task_id);

CREATE TABLE IF NOT EXISTS exercise_generation_history (
    exercise_id bigint PRIMARY KEY REFERENCES exercise(id) ON DELETE CASCADE,
    subject varchar(30) NOT NULL,
    level varchar(8) NOT NULL,
    concept varchar(100) NOT NULL,
    objective varchar(240) NOT NULL,
    question_type varchar(40) NOT NULL,
    rules_version varchar(20) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_generation_history_scope
    ON exercise_generation_history(subject, level, exercise_id);
CREATE INDEX IF NOT EXISTS idx_generation_history_concept
    ON exercise_generation_history(subject, level, concept, exercise_id);
CREATE INDEX IF NOT EXISTS idx_generation_history_objective
    ON exercise_generation_history(subject, level, concept, objective, exercise_id);
