from app.migrate import migrate
import pytest


def test_completed_migration_does_not_change_schema(mocker, monkeypatch):
    monkeypatch.setenv("MARIADB_USER", "test")
    monkeypatch.setenv("MARIADB_PASSWORD", "test")
    cursor = mocker.patch("app.migrate.pymysql.connect").return_value.cursor.return_value.__enter__.return_value
    cursor.fetchone.side_effect = [(1,), (1,), (2,), (3,), (4,)]
    migrate()
    queries = [call.args[0] for call in cursor.execute.call_args_list]
    assert len(queries) == 6
    assert not any("ALTER TABLE" in query for query in queries)


def test_duplicate_preferences_stop_without_deleting_rows(mocker, monkeypatch):
    monkeypatch.setenv("MARIADB_USER", "test")
    monkeypatch.setenv("MARIADB_PASSWORD", "test")
    cursor = mocker.patch("app.migrate.pymysql.connect").return_value.cursor.return_value.__enter__.return_value
    cursor.fetchone.side_effect = [(1,), None, (1,)]
    with pytest.raises(RuntimeError, match="duplicate"):
        migrate()
    queries = [call.args[0] for call in cursor.execute.call_args_list]
    assert not any("DELETE" in query or "DROP FOREIGN KEY" in query for query in queries)


def test_existing_schema_receives_filter_indexes_without_repeating_first_migration(mocker, monkeypatch):
    monkeypatch.setenv("MARIADB_USER", "test")
    monkeypatch.setenv("MARIADB_PASSWORD", "test")
    cursor = mocker.patch("app.migrate.pymysql.connect").return_value.cursor.return_value.__enter__.return_value
    cursor.fetchone.side_effect = [(1,), (1,), None, (3,), (4,)]
    migrate()
    queries = [call.args[0] for call in cursor.execute.call_args_list]
    assert sum("CREATE INDEX IF NOT EXISTS" in query for query in queries) == 2
    assert not any("ALTER TABLE" in query or "DROP FOREIGN KEY" in query for query in queries)
    assert "INSERT INTO schema_migration (version) VALUES (2)" in queries


def test_existing_schema_adds_index_cleanup_queue(mocker, monkeypatch):
    monkeypatch.setenv("MARIADB_USER", "test")
    monkeypatch.setenv("MARIADB_PASSWORD", "test")
    cursor = mocker.patch("app.migrate.pymysql.connect").return_value.cursor.return_value.__enter__.return_value
    cursor.fetchone.side_effect = [(1,), (1,), (2,), (3,), None]
    migrate()
    queries = [call.args[0] for call in cursor.execute.call_args_list]
    assert any(query.startswith("CREATE TABLE IF NOT EXISTS deleted_exercise") for query in queries)
    assert "INSERT INTO schema_migration (version) VALUES (4)" in queries
