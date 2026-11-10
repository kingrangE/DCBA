-- Apply before deploying against an existing database. Duplicate rows cause a safe failure.
-- Review conflicting custom answers before merging existing duplicates; do not delete blindly.
SELECT user_id, exercise_id, COUNT(*) AS duplicates
FROM selected_exercise GROUP BY user_id, exercise_id HAVING COUNT(*) > 1;
SELECT user_id, exercise_id, COUNT(*) AS duplicates
FROM banned_exercise GROUP BY user_id, exercise_id HAVING COUNT(*) > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uk_selected_user_exercise ON selected_exercise (user_id, exercise_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_banned_user_exercise ON banned_exercise (user_id, exercise_id);
