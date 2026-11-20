import os

import pytest

from app.repositories.exercise_repository import exercise_repository


@pytest.mark.skipif(os.getenv("RUN_RECOMMENDATION_DB_TEST") != "true",
                    reason="Requires an isolated, migrated MariaDB database")
def test_current_exact_match_precedes_newer_partial_and_vector_matches():
    with exercise_repository.get_connection() as connection, connection.cursor() as cursor:
        connection.begin()
        try:
            cursor.execute("SELECT id FROM exercise LIMIT 1")
            assert cursor.fetchone() is None, "Use an empty migrated test database"
            cursor.execute("INSERT INTO user (name, password) VALUES ('rc-test', 'unused')")
            user_id = cursor.lastrowid
            insert = "INSERT INTO exercise (subject, level, question, answer) VALUES (%s, %s, %s, '해설')"
            cursor.execute(insert, ("DATABASE", "HARD", "팬텀 리드 발생 조건"))
            exact_id = cursor.lastrowid
            cursor.execute(insert, ("DATABASE", "HARD", "트랜잭션 커밋 순서"))
            vector_id = cursor.lastrowid
            cursor.executemany(insert, [("DATABASE", "HARD", "팬텀 리딩 발생 조건")] * 128)
            cursor.executemany(insert, [("DATABASE", "MEDIUM", "팬텀 리드 발생 조건")] * 128)
            cursor.execute(insert, ("NETWORK", "HARD", "팬텀 리드 발생 조건"))
            wrong_subject_id = cursor.lastrowid

            def result():
                rows = exercise_repository.recommendation_candidates(
                    "DATABASE", "HARD", user_id, "팬텀 리드", [vector_id, exact_id, wrong_subject_id], connection)
                return [row["id"] for row in rows]

            assert result() == [exact_id, vector_id]
            cursor.execute("INSERT INTO exercise_generation_history VALUES "
                           "(%s, 'DATABASE', 'HARD', '팬텀 리드', '발생 조건', '원인 진단', '1')", (exact_id,))
            assert result() == [exact_id, vector_id]
            cursor.execute("INSERT INTO banned_exercise (user_id, exercise_id) VALUES (%s, %s)", (user_id, exact_id))
            assert result() == [vector_id]
            cursor.execute("DELETE FROM banned_exercise WHERE user_id = %s AND exercise_id = %s", (user_id, exact_id))
            cursor.execute("DELETE FROM exercise WHERE id = %s", (exact_id,))
            assert result() == [vector_id]
        finally:
            connection.rollback()
