from app.migrate import migrate
import pytest


def test_completed_migration_does_not_change_schema(mocker, monkeypatch):
    monkeypatch.setenv("MARIADB_USER", "test")
    monkeypatch.setenv("MARIADB_PASSWORD", "test")
    cursor = mocker.patch("app.migrate.pymysql.connect").return_value.cursor.return_value.__enter__.return_value
    cursor.fetchone.side_effect = [(1,), (1,), (2,), (3,), (4,), (5,), (7,)]
    migrate()
    queries = [call.args[0] for call in cursor.execute.call_args_list]
    assert len(queries) == 8
    assert not any("ALTER TABLE" in query for query in queries)
    assert not any("exercise_search_gram" in query or "version = 6" in query for query in queries)


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
    cursor.fetchone.side_effect = [(1,), (1,), None, (3,), (4,), (5,), (7,)]
    migrate()
    queries = [call.args[0] for call in cursor.execute.call_args_list]
    assert sum("CREATE INDEX IF NOT EXISTS" in query for query in queries) == 2
    assert not any("ALTER TABLE" in query or "DROP FOREIGN KEY" in query for query in queries)
    assert "INSERT INTO schema_migration (version) VALUES (2)" in queries


def test_existing_schema_adds_index_cleanup_queue(mocker, monkeypatch):
    monkeypatch.setenv("MARIADB_USER", "test")
    monkeypatch.setenv("MARIADB_PASSWORD", "test")
    cursor = mocker.patch("app.migrate.pymysql.connect").return_value.cursor.return_value.__enter__.return_value
    cursor.fetchone.side_effect = [(1,), (1,), (2,), (3,), None, (5,), (7,)]
    migrate()
    queries = [call.args[0] for call in cursor.execute.call_args_list]
    assert any(query.startswith("CREATE TABLE IF NOT EXISTS deleted_exercise") for query in queries)
    assert "INSERT INTO schema_migration (version) VALUES (4)" in queries

def test_review_migration_adds_default_mark_and_cascades_rubric_on_delete(mocker, monkeypatch):
    monkeypatch.setenv("MARIADB_USER", "test")
    monkeypatch.setenv("MARIADB_PASSWORD", "test")
    cursor = mocker.patch("app.migrate.pymysql.connect").return_value.cursor.return_value.__enter__.return_value
    cursor.fetchone.side_effect = [(1,), (1,), (2,), (3,), (4,), None, (7,)]
    migrate()
    queries = [call.args[0] for call in cursor.execute.call_args_list]
    assert any("ADD COLUMN IF NOT EXISTS review_again BOOLEAN NOT NULL DEFAULT FALSE" in query for query in queries)
    assert any("exercise_feedback_rubric" in query and "ON DELETE CASCADE" in query for query in queries)
    assert "INSERT INTO schema_migration (version) VALUES (5)" in queries


def test_search_migration_backfills_and_installs_write_triggers(mocker, monkeypatch):
    monkeypatch.setenv("MARIADB_USER", "test")
    monkeypatch.setenv("MARIADB_PASSWORD", "test")
    cursor = mocker.patch("app.migrate.pymysql.connect").return_value.cursor.return_value.__enter__.return_value
    cursor.fetchone.side_effect = [(1,), (5,), None, ("utf8mb4_general_ci",), (250, 65), (None, None)]
    migrate(search_index=True)
    queries = [call.args[0] for call in cursor.execute.call_args_list]
    assert cursor.execute.call_args_list[0].args[1] == ("dcba:exercise-search-index",)
    assert any("ON DELETE CASCADE" in query for query in queries)
    assert any("CREATE TRIGGER IF NOT EXISTS exercise_search_insert" in query for query in queries)
    assert any("CREATE TRIGGER IF NOT EXISTS exercise_search_update" in query for query in queries)
    assert any("seq_1_to_63" in query for query in queries)
    assert queries[-1] == "INSERT INTO schema_migration (version) VALUES (6)"


def test_search_migration_requires_base_schema(mocker, monkeypatch):
    monkeypatch.setenv("MARIADB_USER", "test")
    monkeypatch.setenv("MARIADB_PASSWORD", "test")
    cursor = mocker.patch("app.migrate.pymysql.connect").return_value.cursor.return_value.__enter__.return_value
    cursor.fetchone.side_effect = [(1,), None]
    with pytest.raises(RuntimeError, match="required schema"):
        migrate(search_index=True)
    assert cursor.execute.call_count == 2
