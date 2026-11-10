from app.migrate import migrate
import pytest


def test_completed_migration_does_not_change_schema(mocker, monkeypatch):
    monkeypatch.setenv("MARIADB_USER", "test")
    monkeypatch.setenv("MARIADB_PASSWORD", "test")
    cursor = mocker.patch("app.migrate.pymysql.connect").return_value.cursor.return_value.__enter__.return_value
    cursor.fetchone.side_effect = [(1,), (1,)]
    migrate()
    queries = [call.args[0] for call in cursor.execute.call_args_list]
    assert len(queries) == 3
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
