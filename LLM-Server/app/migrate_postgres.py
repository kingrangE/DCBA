"""Apply the PostgreSQL schema and check the production import gate."""

import os
from pathlib import Path

from app.postgres import connect


def migrate(*, require_import=None):
    if require_import is None:
        require_import = os.getenv("DCBA_REQUIRE_POSTGRES_IMPORT") == "1"
    with connect() as connection, connection.cursor() as cursor:
        cursor.execute("SET LOCAL statement_timeout = '5min'")
        cursor.execute("SELECT pg_advisory_xact_lock(hashtext('dcba:schema-migration'))")
        cursor.execute("CREATE TABLE IF NOT EXISTS schema_migration (version INT PRIMARY KEY)")
        migrations = Path(__file__).resolve().parent.parent / "migrations/postgres"
        for path in sorted(migrations.glob("*.sql")):
            version = int(path.name.split("_", 1)[0])
            cursor.execute("SELECT 1 FROM schema_migration WHERE version = %s", (version,))
            if not cursor.fetchone():
                cursor.execute(path.read_text())
                cursor.execute("INSERT INTO schema_migration (version) VALUES (%s)", (version,))
        if require_import:
            cursor.execute("SELECT 1 FROM dcba_cutover WHERE source_engine = 'maria'")
            if not cursor.fetchone():
                raise RuntimeError("Complete and verify the MariaDB import before enabling PostgreSQL")
