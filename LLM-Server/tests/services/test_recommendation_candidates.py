import os

import pytest

from app.repositories.exercise_repository import exercise_repository


pytestmark = pytest.mark.skipif(os.getenv("RUN_RECOMMENDATION_DB_TEST") != "true",
                                reason="Requires an isolated, migrated MariaDB database")


def test_explicit_objective_gets_ranked_candidates_before_generic_history():
    with exercise_repository.get_connection() as connection, connection.cursor() as cursor:
        connection.begin()
        try:
            cursor.execute("SELECT id FROM exercise LIMIT 1")
            assert cursor.fetchone() is None, "Use an empty migrated test database"
            cursor.execute("INSERT INTO user (name, password) VALUES ('goal-test', 'unused')")
            user_id = cursor.lastrowid
            insert = "INSERT INTO exercise (subject, level, question, answer) VALUES (%s, %s, %s, '해설')"
            history = ("INSERT INTO exercise_generation_history VALUES "
                       "(%s, 'DATABASE', 'HARD', '팬텀 리드', %s, '원인 진단', '1')")
            objective = "격리 수준별 발생 조건 판단"
            cursor.execute(insert, ("DATABASE", "HARD", "팬텀 리드가 발생하는 격리 수준을 판단하세요."))
            legacy = cursor.lastrowid
            cursor.execute(insert, ("DATABASE", "HARD", "반복 조회에서 관측되는 행의 차이를 비교하세요."))
            exact = cursor.lastrowid
            cursor.execute(history, (exact, objective))
            definitions = []
            for _ in range(3):
                cursor.execute(insert, ("DATABASE", "HARD", "팬텀 리드의 정의를 설명하세요."))
                definitions.append(cursor.lastrowid)
                cursor.execute(history, (cursor.lastrowid, "정의 설명"))

            def result(goal=None):
                return [row["id"] for row in exercise_repository.recommendation_candidates(
                    "DATABASE", "HARD", user_id, "팬텀 리드", [legacy, exact, definitions[0]],
                    connection, objective=goal)]

            assert result(objective) == [exact, legacy, definitions[0]]
            assert result("격리 수준 구분") == [legacy, exact, definitions[0]]
            assert result() == definitions[::-1]
            cursor.execute("INSERT INTO banned_exercise (user_id, exercise_id) VALUES (%s, %s)", (user_id, exact))
            cursor.execute("DELETE FROM exercise WHERE id = %s", (legacy,))
            assert set(result(objective)) == set(definitions)
        finally:
            connection.rollback()


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


def test_matching_only_the_sampled_grams_does_not_hide_an_old_full_phrase():
    with exercise_repository.get_connection() as connection, connection.cursor() as cursor:
        connection.begin()
        try:
            cursor.execute("SELECT id FROM exercise LIMIT 1")
            assert cursor.fetchone() is None, "Use an empty migrated test database"
            cursor.execute("INSERT INTO user (name, password) VALUES ('gram-test', 'unused')")
            user_id = cursor.lastrowid
            concept = "멀티 버전 동시성 제어"
            middle = (len(concept) - 3) // 2
            partial = "사례 / ".join((concept[:3], concept[middle:middle + 3], concept[-3:])) + " 사례"
            assert concept not in partial
            insert = "INSERT INTO exercise (subject, level, question, answer) VALUES ('DATABASE', 'HARD', %s, '해설')"
            cursor.execute(insert, (f"{concept}를 쓰는 이유를 설명하세요.",))
            exact_id = cursor.lastrowid
            cursor.executemany(insert, [(partial,)] * 128)

            def result():
                return [row["id"] for row in exercise_repository.recommendation_candidates(
                    "DATABASE", "HARD", user_id, concept, [], connection)]

            assert result() == [exact_id]
            cursor.execute("DELETE FROM exercise WHERE id = %s", (exact_id,))
            assert result() == []
        finally:
            connection.rollback()
