"""Copy a stopped DCBA database into an empty, migrated destination."""

import argparse
import hashlib
import json
import os
from contextlib import closing

import pymysql
from psycopg.types.json import set_json_loads

from app.postgres import connect as connect_postgres

TABLES = {
    "user": ("id", "name", "password", "slack_id", "created_at"),
    "exercise": ("id", "subject", "level", "question", "answer", "created_at"),
    "selected_exercise": ("id", "user_id", "exercise_id", "custom_answer", "review_again"),
    "banned_exercise": ("id", "user_id", "exercise_id"),
    "generation_task": ("task_id", "exercise_id"),
    "deleted_exercise": ("exercise_id",),
    "exercise_feedback_rubric": ("exercise_id", "criteria_json"),
}
IDENTITIES = ("user", "exercise", "selected_exercise", "banned_exercise")


def connect(engine):
    if engine == "postgres":
        connection = connect_postgres()
        # Keep the original JSON text when copying and comparing.
        set_json_loads(lambda data: data.decode("utf-8"), connection)
        return connection
    return pymysql.connect(
        host=os.getenv("MARIADB_HOST", "localhost"),
        port=int(os.getenv("MARIADB_PORT", "3306")),
        user=os.environ["MARIADB_USER"],
        password=os.environ["MARIADB_PASSWORD"],
        database=os.getenv("MARIADB_DATABASE", "dcba"),
        connect_timeout=5,
    )


def quoted(engine, name):
    mark = '"' if engine == "postgres" else "`"
    return f"{mark}{name}{mark}"


def next_identity(cursor, engine, table):
    if engine == "postgres":
        cursor.execute("SELECT pg_get_serial_sequence(%s, 'id')", (quoted(engine, table),))
        sequence = cursor.fetchone()[0]
        cursor.execute(f"SELECT last_value, is_called FROM {sequence}")
        last, called = cursor.fetchone()
        return last + int(called)
    cursor.execute(
        "SELECT AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=%s",
        (table,),
    )
    return int(cursor.fetchone()[0])


def schema_check(cursor, engine):
    for table, columns in TABLES.items():
        cursor.execute(f"SELECT * FROM {quoted(engine, table)} LIMIT 0")
        if set(column[0] for column in cursor.description) != set(columns):
            raise RuntimeError(f"Unexpected schema in {table}; review columns before copying")


def rows(connection, engine, table):
    columns = TABLES[table]
    names = ", ".join(quoted(engine, name) for name in columns)
    query = f"SELECT {names} FROM {quoted(engine, table)} ORDER BY {quoted(engine, columns[0])}"
    cursor = connection.cursor(name=f"dcba_transfer_{table}") if engine == "postgres" else connection.cursor(pymysql.cursors.SSCursor)
    with cursor:
        cursor.execute(query)
        while batch := cursor.fetchmany(500):
            yield batch


def normalized(table, row):
    if table == "user":
        return (row[0], row[1].rstrip(" "), *row[2:])
    if table == "selected_exercise":
        return (*row[:-1], bool(row[-1]))
    return tuple(row)


def digest_rows(digest, batch):
    for row in batch:
        digest.update(json.dumps(row, ensure_ascii=False, default=str).encode("utf-8"))
        digest.update(b"\n")


def fingerprint(connection, engine, table):
    digest = hashlib.sha256()
    count = 0
    for batch in rows(connection, engine, table):
        digest_rows(digest, [normalized(table, row) for row in batch])
        count += len(batch)
    return count, digest.hexdigest()


def transfer(source_engine, target_engine):
    with closing(connect(source_engine)) as source, closing(connect(target_engine)) as target:
        try:
            with source.cursor() as src, target.cursor() as dst:
                src.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY" if source_engine == "postgres"
                            else "START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY")
                if target_engine == "postgres":
                    dst.execute("SET LOCAL statement_timeout = '15min'")
                    lock_tables = ", ".join(quoted(target_engine, table) for table in (*TABLES, "dcba_cutover"))
                    dst.execute(f"LOCK TABLE {lock_tables} IN ACCESS EXCLUSIVE MODE")
                    dst.execute("SELECT 1 FROM dcba_cutover LIMIT 1")
                    if dst.fetchone():
                        raise RuntimeError("Destination already has an import marker")
                schema_check(src, source_engine)
                schema_check(dst, target_engine)
                for table in TABLES:
                    dst.execute(f"SELECT 1 FROM {quoted(target_engine, table)} LIMIT 1")
                    if dst.fetchone():
                        raise RuntimeError(f"Destination table {table} is populated; use a fresh database")
                identities = {table: next_identity(src, source_engine, table) for table in IDENTITIES}
                for table, next_id in identities.items():
                    quoted_table = quoted(target_engine, table)
                    if target_engine == "postgres":
                        dst.execute(f"ALTER TABLE {quoted_table} ALTER COLUMN id RESTART WITH {int(next_id)}")
                    else:
                        # MariaDB commits ALTER TABLE. This runs before any destination rows are copied.
                        dst.execute(f"ALTER TABLE {quoted_table} AUTO_INCREMENT = {int(next_id)}")
                for table, columns in TABLES.items():
                    names = ", ".join(quoted(target_engine, name) for name in columns)
                    slots = ", ".join("%s::json" if target_engine == "postgres" and name == "criteria_json" else "%s"
                                      for name in columns)
                    insert = f"INSERT INTO {quoted(target_engine, table)} ({names}) VALUES ({slots})"
                    expected = hashlib.sha256()
                    count = 0
                    for batch in rows(source, source_engine, table):
                        batch = [normalized(table, row) for row in batch]
                        digest_rows(expected, batch)
                        dst.executemany(insert, batch)
                        count += len(batch)
                    if fingerprint(target, target_engine, table) != (count, expected.hexdigest()):
                        raise RuntimeError(f"Verification failed for {table}")
                    print(f"Verified {table}: {count} rows", flush=True)
                for table, next_id in identities.items():
                    if next_identity(dst, target_engine, table) < next_id:
                        raise RuntimeError(f"Identity sequence regressed for {table}")
                if target_engine == "postgres":
                    dst.execute("INSERT INTO dcba_cutover (source_engine) VALUES ('maria')")
            target.commit()
        except BaseException:
            target.rollback()
            raise
    print(f"Committed verified copy to {target_engine}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", choices=("maria", "postgres"))
    parser.add_argument("destination", choices=("maria", "postgres"))
    parser.add_argument("--writers-stopped", action="store_true", required=True)
    args = parser.parse_args()
    if args.source == args.destination:
        parser.error("Source and destination must be different engines")
    transfer(args.source, args.destination)


if __name__ == "__main__":
    main()
