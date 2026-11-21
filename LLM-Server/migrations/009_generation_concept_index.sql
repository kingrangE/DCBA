CREATE INDEX IF NOT EXISTS idx_generation_history_concept
    ON exercise_generation_history (subject, level, concept, exercise_id);
