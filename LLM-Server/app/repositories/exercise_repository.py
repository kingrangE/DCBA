import os
import time
import pymysql
from app.models.exercise import Exercise, ExerciseRequest
from contextlib import contextmanager
from prometheus_client import Histogram

GENERATION_LOCK_TIME = Histogram("dcba_generation_lock_seconds", "Time holding the generation writer lock")

class ExerciseRepository:
    def __init__(self):
        self.postgres = os.getenv("DB_ENGINE") == "postgres"
        self.host = os.getenv("MARIADB_HOST", "localhost")
        self.port = int(os.getenv("MARIADB_PORT", 3306))
        self.user = os.getenv("MARIADB_USER", "root")
        self.password = os.getenv("MARIADB_PASSWORD", "password")
        self.db_name = os.getenv("MARIADB_DATABASE", "dcba")


    def get_connection(self):
        if self.postgres:
            from psycopg.rows import dict_row
            from app.postgres import connect

            return connect(autocommit=True, row_factory=dict_row)
        return pymysql.connect(
            host=self.host,
            port=self.port,
            user=self.user,
            password=self.password,
            database=self.db_name,
            cursorclass=pymysql.cursors.DictCursor,
            autocommit=True,
            connect_timeout=5, read_timeout=30, write_timeout=30,
        )

    @contextmanager
    def generation_lock(self):
        connection = self.get_connection()
        acquired = False
        try:
            with connection.cursor() as cursor:
                if self.postgres:
                    deadline = time.monotonic() + 5
                    while True:
                        cursor.execute("SELECT pg_try_advisory_lock(hashtext('dcba:exercise-generation')) AS acquired")
                        acquired = cursor.fetchone()["acquired"]
                        if acquired or time.monotonic() >= deadline:
                            break
                        time.sleep(0.1)
                else:
                    cursor.execute("SELECT GET_LOCK('dcba:exercise-generation', 5) AS acquired")
                    acquired = cursor.fetchone()["acquired"] == 1
            if not acquired:
                raise RuntimeError("Another generation is still running")
            # shortcut: one generator writes at a time, use a shared vector server before scaling writers.
            with GENERATION_LOCK_TIME.time():
                yield connection
        finally:
            try:
                if acquired and not self.postgres:
                    with connection.cursor() as cursor:
                        cursor.execute("SELECT RELEASE_LOCK('dcba:exercise-generation')")
            finally:
                connection.close()

    def find_after(self, after_id, connection):
        with connection.cursor() as cursor:
            cursor.execute("SELECT * FROM exercise WHERE id > %s ORDER BY id LIMIT 100", (after_id,))
            return cursor.fetchall()

    def find_task(self, task_id, connection):
        with connection.cursor() as cursor:
            cursor.execute("""
                SELECT e.* FROM generation_task t LEFT JOIN exercise e ON t.exercise_id = e.id
                WHERE t.task_id = %s
            """, (task_id,))
            return cursor.fetchone()

    def find_deleted_ids(self, connection):
        with connection.cursor() as cursor:
            cursor.execute("SELECT exercise_id FROM deleted_exercise ORDER BY exercise_id LIMIT 100")
            return [row["exercise_id"] for row in cursor.fetchall()]

    def recent_history(self, subject, level, connection):
        with connection.cursor() as cursor:
            cursor.execute("""
                SELECT concept, objective, question_type FROM exercise_generation_history
                WHERE subject = %s AND level = %s ORDER BY exercise_id DESC LIMIT 20
            """, (subject, level))
            return cursor.fetchall()

    def recommendation_candidates(self, subject, level, user_id, concept, candidate_ids, connection, *, objective=None):
        rows = {}
        with connection.cursor() as cursor:
            if objective is not None:
                preferred = self._history_candidates(cursor, subject, level, user_id, concept, objective)
                rows.update((row["id"], row) for row in preferred)
                if len(rows) < 3:
                    for row in self._vector_candidates(cursor, subject, level, user_id, candidate_ids):
                        rows.setdefault(row["id"], row)
            else:
                history = self._history_candidates(cursor, subject, level, user_id, concept)
                rows.update((row["id"], row) for row in history)

            if len(rows) < 3:
                for row in self._keyword_candidates(cursor, subject, level, user_id, concept):
                    rows.setdefault(row["id"], row)

            if len(rows) < 3:
                fallback = (self._history_candidates(cursor, subject, level, user_id, concept) if objective is not None
                            else self._vector_candidates(cursor, subject, level, user_id, candidate_ids))
                for row in fallback:
                    rows.setdefault(row["id"], row)
        return list(rows.values())[:3]

    def _history_candidates(self, cursor, subject, level, user_id, concept, objective=None):
        objective_filter = "AND h.objective = %s" if objective is not None else ""
        args = (subject, level, concept, user_id)
        if objective is not None:
            args = (*args, objective)
        history = "h JOIN" if self.postgres else "h FORCE INDEX (idx_generation_history_concept) STRAIGHT_JOIN"
        cursor.execute(f"""
            SELECT e.id, e.question, e.answer, h.concept, h.objective
            FROM exercise_generation_history {history} exercise e ON e.id = h.exercise_id
            WHERE h.subject = %s AND h.level = %s AND h.concept = %s
              AND e.subject = h.subject AND e.level = h.level
              AND NOT EXISTS (SELECT 1 FROM banned_exercise b WHERE b.user_id = %s AND b.exercise_id = e.id)
              {objective_filter}
            ORDER BY h.exercise_id DESC LIMIT 3
        """, args)
        return cursor.fetchall()

    def _keyword_candidates(self, cursor, subject, level, user_id, concept):
        if self.postgres:
            pattern = "%" + concept.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
            cursor.execute("""
                SELECT e.id, e.question, e.answer, h.concept, h.objective
                FROM exercise e
                LEFT JOIN exercise_generation_history h ON h.exercise_id = e.id
                WHERE e.subject = %s AND e.level = %s AND LOWER(e.question) LIKE LOWER(%s) ESCAPE '!'
                  AND NOT EXISTS (SELECT 1 FROM banned_exercise b WHERE b.user_id = %s AND b.exercise_id = e.id)
                ORDER BY e.id DESC LIMIT 3
            """, (subject, level, pattern, user_id))
            return cursor.fetchall()

        cursor.execute("""
            SELECT 1 FROM information_schema.COLUMNS c JOIN schema_migration m ON m.version = 6
            WHERE c.TABLE_SCHEMA = DATABASE() AND c.TABLE_NAME = 'exercise'
              AND c.COLUMN_NAME = 'question' AND c.COLLATION_NAME = 'utf8mb4_general_ci'
              AND EXISTS (SELECT 1 FROM information_schema.TABLES t
                  WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_NAME = 'exercise_search_gram')
        """)
        indexed = bool(cursor.fetchone()) and len(concept) >= 3
        if indexed:
            middle = (len(concept) - 3) // 2
            grams = tuple(dict.fromkeys((concept[:3], concept[middle:middle + 3], concept[-3:])))
            probes = self._probe_grams(cursor, grams, 32)
            if probes[0][0] == 32:
                # Only expand when the initial sample has no selective key.
                extra = tuple(dict.fromkeys(concept[i:i + 3] for i in range(len(concept) - 2)
                                            if concept[i:i + 3] not in grams))
                if extra:
                    probes.extend(hit for hit in self._probe_grams(cursor, extra, 4) if hit[0] < 4)
                    probes.sort(key=lambda hit: hit[0])
            driver, *filters = (gram for _, gram in probes[:3])
            gram_checks = """
                STRAIGHT_JOIN exercise_search_gram AS {alias}
                    ON {alias}.exercise_id = g.exercise_id
                    AND {alias}.gram = LOWER(CONVERT(%s USING utf8mb4)) COLLATE utf8mb4_general_ci
            """
            gram_joins = "".join(gram_checks.format(alias=f"g{index}") for index in range(len(filters)))
            cursor.execute(f"""
                SELECT e.id, e.question, e.answer, h.concept, h.objective
                FROM exercise_search_gram g {gram_joins}
                STRAIGHT_JOIN exercise e ON e.id = g.exercise_id
                LEFT JOIN exercise_generation_history h ON h.exercise_id = e.id
                WHERE g.gram = LOWER(CONVERT(%s USING utf8mb4)) COLLATE utf8mb4_general_ci
                  AND e.subject = %s AND e.level = %s AND LOCATE(LOWER(%s), LOWER(e.question)) > 0
                  AND NOT EXISTS (SELECT 1 FROM banned_exercise b WHERE b.user_id = %s AND b.exercise_id = e.id)
                ORDER BY g.exercise_id DESC LIMIT 3
            """, (*filters, driver, subject, level, concept, user_id))
        else:
            cursor.execute("""
                SELECT e.id, e.question, e.answer, h.concept, h.objective
                FROM exercise e FORCE INDEX (idx_exercise_subject_level)
                LEFT JOIN exercise_generation_history h ON h.exercise_id = e.id
                WHERE e.subject = %s AND e.level = %s AND LOCATE(LOWER(%s), LOWER(e.question)) > 0
                  AND NOT EXISTS (SELECT 1 FROM banned_exercise b WHERE b.user_id = %s AND b.exercise_id = e.id)
                ORDER BY e.id DESC LIMIT 3
            """, (subject, level, concept, user_id))
        return cursor.fetchall()

    def _probe_grams(self, cursor, grams, limit):
        probe = """
            SELECT %s AS gram_index, COUNT(*) AS hits FROM (
                SELECT exercise_id FROM exercise_search_gram
                WHERE gram = LOWER(CONVERT(%s USING utf8mb4)) COLLATE utf8mb4_general_ci
                LIMIT %s
            ) sample
        """
        arguments = tuple(value for index, gram in enumerate(grams) for value in (index, gram, limit))
        cursor.execute(" UNION ALL ".join([probe] * len(grams)), arguments)
        samples = sorted(cursor.fetchall(), key=lambda row: (row["hits"], row["gram_index"]))
        return [(row["hits"], grams[row["gram_index"]]) for row in samples]

    def _vector_candidates(self, cursor, subject, level, user_id, candidate_ids):
        vector_ids = candidate_ids[:3]
        if not vector_ids:
            return []
        placeholders = ", ".join(["%s"] * len(vector_ids))
        exercise = "e" if self.postgres else "e FORCE INDEX (PRIMARY)"
        cursor.execute(f"""
            SELECT e.id, e.question, e.answer, h.concept, h.objective
            FROM exercise {exercise}
            LEFT JOIN exercise_generation_history h ON h.exercise_id = e.id
            WHERE e.id IN ({placeholders}) AND e.subject = %s AND e.level = %s
              AND NOT EXISTS (SELECT 1 FROM banned_exercise b WHERE b.user_id = %s AND b.exercise_id = e.id)
        """, (*vector_ids, subject, level, user_id))
        by_id = {row["id"]: row for row in cursor.fetchall()}
        return [by_id[value] for value in vector_ids if value in by_id]

    def save_recommendation(self, task_id, user_id, exercise_id, subject, level, connection):
        if self.postgres:
            connection.execute("BEGIN")
        else:
            connection.begin()
        try:
            with connection.cursor() as cursor:
                # Use the same lock order as preference mutations, then recheck visibility.
                user_table = '"user"' if self.postgres else "user"
                cursor.execute(f"SELECT id FROM {user_table} WHERE id = %s FOR UPDATE", (user_id,))
                if not cursor.fetchone():
                    connection.rollback()
                    return None
                cursor.execute("""
                    SELECT * FROM exercise WHERE id = %s AND subject = %s AND level = %s FOR UPDATE
                """, (exercise_id, subject, level))
                exercise = cursor.fetchone()
                cursor.execute("SELECT id FROM banned_exercise WHERE user_id = %s AND exercise_id = %s", (user_id, exercise_id))
                if not exercise or cursor.fetchone():
                    connection.rollback()
                    return None
                cursor.execute("SELECT id FROM selected_exercise WHERE user_id = %s AND exercise_id = %s",
                               (user_id, exercise_id))
                completion_type = "already_saved" if cursor.fetchone() else "recommended"
                if completion_type == "recommended":
                    cursor.execute("INSERT INTO selected_exercise (user_id, exercise_id) VALUES (%s, %s)",
                                   (user_id, exercise_id))
                cursor.execute("""
                    INSERT INTO generation_task (task_id, exercise_id, requester_id, completed_at, completion_type)
                    VALUES (%s, %s, %s, CURRENT_TIMESTAMP(6), %s)
                """, (task_id, exercise_id, user_id, completion_type))
            connection.commit()
            return Exercise(**exercise)
        except Exception:
            connection.rollback()
            raise

    def clear_deleted_ids(self, ids, connection):
        with connection.cursor() as cursor:
            cursor.executemany("DELETE FROM deleted_exercise WHERE exercise_id = %s", [(exercise_id,) for exercise_id in ids])

    def save_exercise(self, subject: str, level: str, question: str, answer: str,
                      task_id=None, connection=None, history=None, requester_id=None) -> Exercise:
        request = ExerciseRequest(subject=subject, level=level)
        if not question or not answer:
            raise ValueError("Question and answer are required")
        conn = connection or self.get_connection()
        try:
            if self.postgres:
                conn.execute("BEGIN")
            else:
                conn.begin()
            with conn.cursor() as cursor:
                sql = "INSERT INTO exercise (subject, level, question, answer, created_at) VALUES (%s, %s, %s, %s, CURRENT_TIMESTAMP)"
                cursor.execute(sql + (" RETURNING id" if self.postgres else ""), (request.subject.value, request.level.value, question, answer))
                exercise_id = cursor.fetchone()["id"] if self.postgres else cursor.lastrowid
                if task_id is not None:
                    if history is None:
                        cursor.execute("INSERT INTO generation_task (task_id, exercise_id) VALUES (%s, %s)", (task_id, exercise_id))
                    else:
                        cursor.execute("""
                            INSERT INTO generation_task (task_id, exercise_id, requester_id, completed_at)
                            VALUES (%s, %s, %s, CURRENT_TIMESTAMP(6))
                        """, (task_id, exercise_id, requester_id))
                if history is not None:
                    cursor.execute("""
                        INSERT INTO exercise_generation_history
                            (exercise_id, subject, level, concept, objective, question_type, rules_version)
                        VALUES (%s, %s, %s, %s, %s, %s, %s)
                    """, (exercise_id, subject, level, *history))
                if requester_id is not None:
                    cursor.execute("INSERT INTO selected_exercise (user_id, exercise_id) VALUES (%s, %s)",
                                   (requester_id, exercise_id))
            conn.commit()
            return Exercise(
                id=exercise_id,
                subject=subject,
                level=level,
                question=question,
                answer=answer
            )
        except Exception:
            conn.rollback()
            raise
        finally:
            if connection is None:
                conn.close()

exercise_repository = ExerciseRepository()
