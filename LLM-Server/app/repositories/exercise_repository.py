import os
import pymysql
from app.models.exercise import Exercise, ExerciseRequest
from contextlib import contextmanager

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
                SELECT e.* FROM exercise e JOIN generation_task t ON t.exercise_id = e.id
                WHERE t.task_id = %s
            """, (task_id,))
            row = cursor.fetchone()
            return Exercise(**row) if row else None

    def save_exercise(self, subject: str, level: str, question: str, answer: str,
                      task_id=None, connection=None) -> Exercise:
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
                    cursor.execute("INSERT INTO generation_task (task_id, exercise_id) VALUES (%s, %s)", (task_id, exercise_id))
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
