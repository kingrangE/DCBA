import os
import pymysql
from app.models.exercise import Exercise, ExerciseRequest
from contextlib import contextmanager
from prometheus_client import Histogram

GENERATION_LOCK_TIME = Histogram("dcba_generation_lock_seconds", "Time holding the generation writer lock")

class ExerciseRepository:
    def __init__(self):
        self.host = os.getenv("MARIADB_HOST", "localhost")
        self.port = int(os.getenv("MARIADB_PORT", 3306))
        self.user = os.getenv("MARIADB_USER", "root")
        self.password = os.getenv("MARIADB_PASSWORD", "password")
        self.db_name = os.getenv("MARIADB_DATABASE", "dcba")


    def get_connection(self):
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
                cursor.execute("SELECT GET_LOCK('dcba:exercise-generation', 5) AS acquired")
                acquired = cursor.fetchone()["acquired"] == 1
            if not acquired:
                raise RuntimeError("Another generation is still running")
            # shortcut: one generator writes at a time, use a shared vector server before scaling writers.
            with GENERATION_LOCK_TIME.time():
                yield connection
        finally:
            try:
                if acquired:
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
            conn.begin()
            with conn.cursor() as cursor:
                sql = "INSERT INTO exercise (subject, level, question, answer, created_at) VALUES (%s, %s, %s, %s, CURRENT_TIMESTAMP)"
                cursor.execute(sql, (request.subject.value, request.level.value, question, answer))
                exercise_id = cursor.lastrowid
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
