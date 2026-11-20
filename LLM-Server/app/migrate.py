"""Apply required schema changes or backfill the optional search index."""
import argparse
import os
from pathlib import Path

import pymysql


def index_exercise_questions(cursor):
    cursor.execute("""
        SELECT COLLATION_NAME FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exercise' AND COLUMN_NAME = 'question'
    """)
    if cursor.fetchone() != ("utf8mb4_general_ci",):
        return
    sql = Path(__file__).resolve().parent.parent / "migrations/006_exercise_search_grams.sql"
    cursor.execute(sql.read_text())
    grams = """
        INSERT INTO exercise_search_gram (gram, exercise_id)
        SELECT DISTINCT SUBSTRING(LOWER(NEW.question), seq, 3), NEW.id
        FROM seq_1_to_65535 WHERE seq <= CHAR_LENGTH(NEW.question) - 2
        ON DUPLICATE KEY UPDATE gram = VALUES(gram)
    """
    cursor.execute(f"""
        CREATE TRIGGER IF NOT EXISTS exercise_search_insert AFTER INSERT ON exercise
        FOR EACH ROW {grams}
    """)
    cursor.execute(f"""
        CREATE TRIGGER IF NOT EXISTS exercise_search_update AFTER UPDATE ON exercise
        FOR EACH ROW
        BEGIN
            IF BINARY OLD.question <> BINARY NEW.question THEN
                DELETE FROM exercise_search_gram WHERE exercise_id = NEW.id;
                {grams};
            END IF;
        END
    """)
    after_id = 0
    while True:
        cursor.execute("""
            SELECT MAX(id), MAX(CHAR_LENGTH(question)) FROM (
                SELECT id, question FROM exercise WHERE id > %s ORDER BY id LIMIT 250
            ) batch
        """, (after_id,))
        last_id, max_chars = cursor.fetchone()
        if last_id is None:
            break
        if max_chars >= 3:
            cursor.execute(f"""
                INSERT INTO exercise_search_gram (gram, exercise_id)
                SELECT DISTINCT SUBSTRING(LOWER(e.question), seq, 3), e.id
                FROM exercise e JOIN seq_1_to_{int(max_chars) - 2} ON seq <= CHAR_LENGTH(e.question) - 2
                WHERE e.id > %s AND e.id <= %s
                ON DUPLICATE KEY UPDATE gram = VALUES(gram)
            """, (after_id, last_id))
        after_id = last_id


def migrate(*, search_index=False):
    connection = pymysql.connect(
        host=os.getenv("MARIADB_HOST", "localhost"),
        port=int(os.getenv("MARIADB_PORT", "3306")),
        user=os.environ["MARIADB_USER"], password=os.environ["MARIADB_PASSWORD"],
        database=os.getenv("MARIADB_DATABASE", "dcba"), autocommit=True,
        connect_timeout=5, read_timeout=60, write_timeout=60,
    )
    try:
        with connection.cursor() as cursor:
            lock = "dcba:exercise-search-index" if search_index else "dcba:schema-migration"
            cursor.execute("SELECT GET_LOCK(%s, 60)", (lock,))
            if cursor.fetchone()[0] != 1:
                raise RuntimeError(f"Could not acquire migration lock {lock}")
            if search_index:
                cursor.execute("SELECT version FROM schema_migration WHERE version = 5")
                if not cursor.fetchone():
                    raise RuntimeError("Apply the required schema before starting the search index")
                cursor.execute("SELECT version FROM schema_migration WHERE version = 6")
                if not cursor.fetchone():
                    index_exercise_questions(cursor)
                    cursor.execute("INSERT INTO schema_migration (version) VALUES (6)")
                return
            cursor.execute("CREATE TABLE IF NOT EXISTS schema_migration (version INT PRIMARY KEY)")
            cursor.execute("SELECT version FROM schema_migration WHERE version = 1")
            if not cursor.fetchone():
                sql = (Path(__file__).resolve().parent.parent / "migrations/001_schema.sql").read_text()
                for statement in sql.split(";"):
                    if statement.strip():
                        cursor.execute(statement)

                # MariaDB DDL commits immediately; every step must also work after an interrupted migration.
                for table in ("selected_exercise", "banned_exercise"):
                    cursor.execute(f"SELECT 1 FROM `{table}` GROUP BY user_id, exercise_id HAVING COUNT(*) > 1 LIMIT 1")
                    if cursor.fetchone():
                        raise RuntimeError(f"Resolve duplicate preferences before migrating {table}; no rows were deleted")
                    for column, parent in (("user_id", "user"), ("exercise_id", "exercise")):
                        cursor.execute(f"SELECT 1 FROM `{table}` p LEFT JOIN `{parent}` e ON p.`{column}` = e.id "
                                       f"WHERE p.`{column}` IS NOT NULL AND e.id IS NULL LIMIT 1")
                        if cursor.fetchone():
                            raise RuntimeError(f"Resolve orphaned preferences before migrating {table}")

                cursor.execute("SELECT 1 FROM exercise WHERE question IS NULL OR answer IS NULL "
                               "OR subject IS NULL OR level IS NULL LIMIT 1")
                if cursor.fetchone():
                    raise RuntimeError("Resolve incomplete exercises before migration; no rows were deleted")
                cursor.execute("SELECT TABLE_NAME, CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE "
                               "WHERE CONSTRAINT_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME = 'exercise'")
                foreign_keys = cursor.fetchall()
                if any(table not in ("selected_exercise", "banned_exercise") for table, _ in foreign_keys):
                    raise RuntimeError("Unexpected exercise references; review them before migration")
                for table, name in foreign_keys:
                    cursor.execute(f"ALTER TABLE `{table}` DROP FOREIGN KEY `{name.replace('`', '``')}`")
                cursor.execute("ALTER TABLE exercise MODIFY id BIGINT NOT NULL AUTO_INCREMENT, "
                               "MODIFY subject VARCHAR(30) NOT NULL, MODIFY level VARCHAR(8) NOT NULL, "
                               "MODIFY question TEXT NOT NULL, MODIFY answer TEXT NOT NULL")
                for table, prefix in (("selected_exercise", "selected"), ("banned_exercise", "banned")):
                    cursor.execute(f"ALTER TABLE `{table}` MODIFY exercise_id BIGINT")
                    cursor.execute(f"CREATE UNIQUE INDEX IF NOT EXISTS uk_{prefix}_user_exercise ON `{table}` (user_id, exercise_id)")
                    cursor.execute(f"ALTER TABLE `{table}` ADD CONSTRAINT fk_{prefix}_exercise "
                                   "FOREIGN KEY (exercise_id) REFERENCES exercise(id)")
                    cursor.execute("SELECT 1 FROM information_schema.KEY_COLUMN_USAGE WHERE CONSTRAINT_SCHEMA = DATABASE() "
                                   "AND TABLE_NAME = %s AND COLUMN_NAME = 'user_id' AND REFERENCED_TABLE_NAME = 'user'", (table,))
                    if not cursor.fetchone():
                        cursor.execute(f"ALTER TABLE `{table}` ADD CONSTRAINT fk_{prefix}_user FOREIGN KEY (user_id) REFERENCES user(id)")
                cursor.execute("INSERT INTO schema_migration (version) VALUES (1)")
            cursor.execute("SELECT version FROM schema_migration WHERE version = 2")
            if not cursor.fetchone():
                sql = (Path(__file__).resolve().parent.parent / "migrations/002_exercise_filter_indexes.sql").read_text()
                for statement in sql.split(";"):
                    if statement.strip():
                        cursor.execute(statement)
                cursor.execute("INSERT INTO schema_migration (version) VALUES (2)")
            cursor.execute("SELECT version FROM schema_migration WHERE version = 3")
            if not cursor.fetchone():
                cursor.execute("ALTER TABLE user ADD COLUMN IF NOT EXISTS slack_id VARCHAR(255)")
                cursor.execute("INSERT INTO schema_migration (version) VALUES (3)")
            cursor.execute("SELECT version FROM schema_migration WHERE version = 4")
            if not cursor.fetchone():
                cursor.execute("CREATE TABLE IF NOT EXISTS deleted_exercise (exercise_id BIGINT PRIMARY KEY) ENGINE=InnoDB")
                cursor.execute("INSERT INTO schema_migration (version) VALUES (4)")
            cursor.execute("SELECT version FROM schema_migration WHERE version = 5")
            if not cursor.fetchone():
                cursor.execute("ALTER TABLE selected_exercise ADD COLUMN IF NOT EXISTS review_again BOOLEAN NOT NULL DEFAULT FALSE")
                cursor.execute("""
                    CREATE TABLE IF NOT EXISTS exercise_feedback_rubric (
                        exercise_id BIGINT PRIMARY KEY,
                        criteria_json JSON NOT NULL,
                        CONSTRAINT fk_feedback_rubric_exercise FOREIGN KEY (exercise_id)
                            REFERENCES exercise(id) ON DELETE CASCADE
                    ) ENGINE=InnoDB
                """)
                cursor.execute("INSERT INTO schema_migration (version) VALUES (5)")
            cursor.execute("SELECT version FROM schema_migration WHERE version = 7")
            if not cursor.fetchone():
                sql = (Path(__file__).resolve().parent.parent / "migrations/007_generation_history.sql").read_text()
                for statement in sql.split(";"):
                    if statement.strip():
                        cursor.execute(statement)
                cursor.execute("INSERT INTO schema_migration (version) VALUES (7)")
            cursor.execute("SELECT version FROM schema_migration WHERE version = 8")
            if not cursor.fetchone():
                sql = Path(__file__).resolve().parent.parent / "migrations/008_generation_completion_type.sql"
                cursor.execute(sql.read_text())
                cursor.execute("INSERT INTO schema_migration (version) VALUES (8)")
            cursor.execute("SELECT version FROM schema_migration WHERE version = 9")
            if not cursor.fetchone():
                sql = Path(__file__).resolve().parent.parent / "migrations/009_generation_concept_index.sql"
                cursor.execute(sql.read_text())
                cursor.execute("INSERT INTO schema_migration (version) VALUES (9)")
    finally:
        connection.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--search-index", action="store_true", help="backfill the index after the required schema is ready")
    migrate(search_index=parser.parse_args().search_index)
