CREATE TABLE IF NOT EXISTS exercise_search_gram (
    gram VARCHAR(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
    exercise_id BIGINT NOT NULL,
    PRIMARY KEY (gram, exercise_id),
    KEY idx_search_gram_exercise (exercise_id),
    CONSTRAINT fk_search_gram_exercise FOREIGN KEY (exercise_id)
        REFERENCES exercise(id) ON DELETE CASCADE
) ENGINE=InnoDB
