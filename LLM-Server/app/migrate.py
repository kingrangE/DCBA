"""Run once before either application starts; never discard existing rows."""
import os
from pathlib import Path

import pymysql


def migrate():
    connection = pymysql.connect(
        host=os.getenv("MARIADB_HOST", "localhost"),
        port=int(os.getenv("MARIADB_PORT", "3306")),
        user=os.environ["MARIADB_USER"], password=os.environ["MARIADB_PASSWORD"],
        database=os.getenv("MARIADB_DATABASE", "dcba"), autocommit=True,
        connect_timeout=5, read_timeout=60, write_timeout=60,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT GET_LOCK('dcba:schema-migration', 60)")
            if cursor.fetchone()[0] != 1:
                raise RuntimeError("Could not acquire schema migration lock")
            cursor.execute("CREATE TABLE IF NOT EXISTS schema_migration (version INT PRIMARY KEY)")
            cursor.execute("SELECT version FROM schema_migration WHERE version = 1")
            if cursor.fetchone():
                return
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
    finally:
        connection.close()


if __name__ == "__main__":
    migrate()
