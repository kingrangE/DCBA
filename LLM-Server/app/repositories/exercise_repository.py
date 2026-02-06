import os
import pymysql
from app.models.exercise import Exercise
from datetime import datetime

class ExerciseRepository:
    def __init__(self):
        self.host = os.getenv("MARIADB_HOST", "localhost")
        self.port = int(os.getenv("MARIADB_PORT", 3306))
        self.user = os.getenv("MARIADB_USER", "root")
        self.password = os.getenv("MARIADB_PASSWORD", "password")
        self.db_name = os.getenv("MARIADB_DATABASE", "dcba")

        self.initialize_db()

    def get_connection(self):
        return pymysql.connect(
            host=self.host,
            port=self.port,
            user=self.user,
            password=self.password,
            database=self.db_name,
            cursorclass=pymysql.cursors.DictCursor,
            autocommit=True
        )

    def initialize_db(self):
        conn = pymysql.connect(
            host=self.host,
            port=self.port,
            user=self.user,
            password=self.password,
            autocommit=True
        )
        try:
            with conn.cursor() as cursor:
                cursor.execute(f"CREATE DATABASE IF NOT EXISTS {self.db_name}")
        finally:
            conn.close()

        # DB 연결
        conn = self.get_connection()
        try:
            with conn.cursor() as cursor:
                # 테이블 없으면 생성
                cursor.execute("""
                    CREATE TABLE IF NOT EXISTS exercise (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        subject VARCHAR(30) NOT NULL,
                        level VARCHAR(8) NOT NULL,
                        question TEXT NOT NULL,
                        answer TEXT NOT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP  
                    )
                """)
        finally:
            conn.close()
    
    # 문제 저장
    def save_exercise(self, subject: str, level: str, question: str, answer: str) -> Exercise:
        conn = self.get_connection()
        try:
            # Insert 실행
            with conn.cursor() as cursor:
                sql = "INSERT INTO exercise (subject, level, question, answer) VALUES (%s, %s, %s, %s)"
                cursor.execute(sql, (subject.upper(), level.upper(), question, answer))
                exercise_id = cursor.lastrowid
                
            return Exercise(
                id=exercise_id,
                subject=subject,
                level=level,
                question=question,
                answer=answer
            )
        finally:
            conn.close()

exercise_repository = ExerciseRepository()
