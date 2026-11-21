import os
import socket
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import httpx
from psycopg.errors import UniqueViolation

from app.migrate import migrate as migrate_maria
from app.migrate_postgres import migrate as migrate_postgres
from app.repositories.exercise_repository import ExerciseRepository
from app.transfer_database import TABLES, connect, fingerprint, transfer


def fixture():
    migrate_maria()
    with connect("maria") as connection, connection.cursor() as cursor:
        cursor.execute(
            "INSERT INTO user (id, name, password) VALUES (7, 'Owner', %s)",
            ("$2a$04$nxW2QK3GPWGG1zfgcr7WZ.RD6R.IXydUIiVVWj79ja9mg.P62O902",),
        )
        cursor.executemany(
            "INSERT INTO exercise (id, subject, level, question, answer) VALUES (%s,'DATABASE','HARD',%s,'해설')",
            [(i, "오래된 희귀문항" if i == 1 else "교착 상태 %_\\ 사례") for i in range(1, 302)],
        )
        cursor.execute("DELETE FROM exercise WHERE id = 301")
        cursor.execute("INSERT INTO selected_exercise VALUES (51, 7, 300, '답안', TRUE)")
        cursor.execute("INSERT INTO banned_exercise VALUES (61, 7, 299)")
        cursor.execute("INSERT INTO generation_task (task_id, exercise_id) VALUES ('before-migration', 300)")
        cursor.execute("INSERT INTO deleted_exercise VALUES (301)")
        cursor.execute("INSERT INTO exercise_feedback_rubric VALUES (300, %s)", ('[{"id":"test"}]',))
        cursor.execute("ALTER TABLE exercise AUTO_INCREMENT = 500")
        connection.commit()


def cutover():
    with connect("postgres") as connection, connection.cursor() as cursor:
        cursor.execute("CREATE TABLE schema_migration (version int PRIMARY KEY)")
        cursor.execute(Path("LLM-Server/migrations/postgres/001_schema.sql").read_text())
        cursor.execute("INSERT INTO schema_migration VALUES (1)")
        cursor.execute("INSERT INTO generation_task VALUES ('legacy-before-schema-upgrade', 8)")
    migrate_postgres(require_import=False)
    with connect("postgres") as connection, connection.cursor() as cursor:
        cursor.execute("DELETE FROM generation_task WHERE task_id='legacy-before-schema-upgrade' "
                       "RETURNING exercise_id, requester_id, completed_at, completion_type")
        assert cursor.fetchone() == (8, None, None, "created")
    try:
        migrate_postgres(require_import=True)
    except RuntimeError as failure:
        assert "verify" in str(failure)
    else:
        raise AssertionError("Production import gate accepted a blank PostgreSQL database")
    transfer("maria", "postgres")
    migrate_postgres(require_import=True)
    try:
        transfer("maria", "postgres")
    except RuntimeError as failure:
        assert "marker" in str(failure)
    else:
        raise AssertionError("Transfer accepted a populated destination")


def writer():
    os.environ["DB_ENGINE"] = "postgres"
    repo = ExerciseRepository()

    def deliver():
        with repo.generation_lock() as connection:
            prior = repo.find_task("retry-fixture", connection)
            if prior:
                return prior["id"]
            return repo.save_exercise("DATABASE", "HARD", "전환 뒤 문항", "정답", "retry-fixture", connection).id

    with ThreadPoolExecutor(max_workers=2) as pool:
        assert list(pool.map(lambda _: deliver(), range(2))) == [500, 500]
    with repo.generation_lock() as connection:
        assert repo.find_task("before-migration", connection)["id"] == 300
        assert repo.find_deleted_ids(connection) == [301]
        try:
            repo.save_exercise("DATABASE", "HARD", "롤백 확인", "정답", "retry-fixture", connection)
        except UniqueViolation:
            pass
        else:
            raise AssertionError("Duplicate task did not abort its exercise write")
        with connection.cursor() as cursor:
            cursor.execute("SELECT COUNT(*) AS count FROM exercise WHERE id > 300")
            assert cursor.fetchone()["count"] == 1
    del os.environ["DB_ENGINE"]


def http_test(log_path):
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    with log_path.open("w") as log:
        process = subprocess.Popen(
            [
                "java", "-jar", "DCBA/build/libs/app.jar", f"--server.port={port}",
                "--management.server.port=0", "--spring.profiles.active=prod,postgres", "--DCBA_ADMIN_USER_ID=7",
            ],
            stdout=log, stderr=log,
        )
        try:
            with httpx.Client(base_url=f"http://127.0.0.1:{port}", timeout=5) as client:
                deadline = time.monotonic() + 45
                while True:
                    try:
                        csrf = client.get("/api/auth/csrf")
                        if csrf.is_success:
                            break
                    except httpx.HTTPError:
                        pass
                    assert time.monotonic() < deadline and process.poll() is None, "Spring startup failed"
                    time.sleep(0.25)
                token = csrf.json()
                headers = {token["headerName"]: token["token"]}
                response = client.post("/api/auth/login", headers=headers, json={"name": "OWNER", "password": "fixture-password"})
                assert response.status_code == 200 and response.json()["id"] == 7, response.text
                token = client.get("/api/auth/csrf").json()
                headers = {token["headerName"]: token["token"]}
                duplicate = client.post("/api/auth/signup", headers=headers, json={"name": "owner", "password": "fixture-password"})
                assert duplicate.status_code == 409, duplicate.text

                def page(**params):
                    response = client.get("/api/exercises", params=params)
                    assert response.is_success, response.text
                    return response.json()

                newest = page(q="교착 상태", subject="DATABASE", level="HARD")
                ids = [row["id"] for row in newest["content"]]
                assert ids == [300, 298, 297, 296, 295, 294, 293, 292, 291] and newest["hasNext"]
                older = page(q="%_\\", before=291)
                assert [row["id"] for row in older["content"]] == list(range(290, 281, -1))
                assert [row["id"] for row in page(q="교착 상태", after=290)["content"]] == ids
                assert [row["id"] for row in page(q="희귀문항")["content"]] == [1]
                assert len(page(q="없는 문항")["content"]) == 0
                assert [row["id"] for row in page(view="selected", q="교착 상태")["content"]] == [300]
                sample = client.get("/api/exercises/review-sample", params={"mode": "marked"})
                assert sample.status_code == 200 and [row["id"] for row in sample.json()["items"]] == [300], sample.text
                assert client.put("/api/exercises/300/review-again", headers=headers, json={"enabled": False}).status_code == 204
                assert client.delete("/api/admin/exercises/300", headers=headers).status_code == 204
                assert [row["id"] for row in page(view="selected")["content"]] == []
                generation_requests(client, headers, page)
        finally:
            process.terminate()
            process.wait(timeout=15)
    print("Authenticated PostgreSQL search, pagination, account case, review and deletion passed", flush=True)


def generation_requests(client, headers, page):
    os.environ["DB_ENGINE"] = "postgres"
    repo = ExerciseRepository()

    def request(intent):
        response = client.post(
            "/api/exercises/generation-requests", headers=headers,
            json={"subject": "DATABASE", "level": "HARD", "learningIntent": intent},
        )
        assert response.status_code == 202, response.text
        request_id = response.json()["requestId"]
        state = client.get(f"/api/exercises/generation-requests/{request_id}")
        assert state.json()["status"] == "queued", state.text
        return request_id

    created = request("팬텀 리드")
    history = ("팬텀 리드", "조건을 판단한다", "개념 판별", "1")
    with repo.generation_lock() as connection:
        exercise = repo.save_exercise("DATABASE", "HARD", "팬텀 리드 %_ 관측", "격리 시험",
                                      created, connection, history, 7)
        assert repo.find_task(created, connection)["id"] == exercise.id
        assert repo.recent_history("DATABASE", "HARD", connection)[0]["concept"] == history[0]
        results = repo.recommendation_candidates("DATABASE", "HARD", 7, "팬텀 리드", [], connection,
                                                 objective=history[1])
        assert results[0]["id"] == exercise.id
        results = repo.recommendation_candidates("DATABASE", "HARD", 7, "%_", [299], connection)
        assert exercise.id in [row["id"] for row in results]
        assert 299 not in [row["id"] for row in results]

    recommended = request("희귀문항")
    with repo.generation_lock() as connection:
        candidates = repo.recommendation_candidates("DATABASE", "HARD", 7, "희귀문항", [], connection)
        assert [row["id"] for row in candidates] == [1]
        assert repo.save_recommendation(recommended, 7, 1, "DATABASE", "HARD", connection).id == 1
        assert repo.find_task(recommended, connection)["id"] == 1

    recent = client.get("/api/exercises/generation-requests/recent")
    assert recent.status_code == 200 and recent.json() == [], recent.text
    notifications_url = "/api/exercises/generation-requests/notifications"
    notifications = client.get(notifications_url)
    assert notifications.status_code == 200, notifications.text
    by_id = {note["requestId"]: note for note in notifications.json()["items"]}
    assert by_id[created]["completionType"] == "created"
    assert by_id[recommended]["completionType"] == "recommended"
    assert client.put(f"/api/exercises/generation-requests/{recommended}/read", headers=headers).status_code == 204
    after = client.get(notifications_url).json()["items"]
    assert next(note for note in after if note["requestId"] == recommended)["readAt"]
    assert {row["id"] for row in page(view="selected")["content"]} == {1, exercise.id}
    del os.environ["DB_ENGINE"]
    print("PostgreSQL agent writes, candidate ranking, Redis requests and completion notifications passed", flush=True)


def rollback():
    os.environ["MARIADB_DATABASE"] = "dcba_rollback"
    migrate_maria()
    transfer("postgres", "maria")
    with connect("maria") as maria, connect("postgres") as postgres:
        for table in TABLES:
            assert fingerprint(maria, "maria", table) == fingerprint(postgres, "postgres", table)
        with maria.cursor() as cursor:
            cursor.execute("INSERT INTO exercise (subject,level,question,answer) VALUES ('DATABASE','HARD','복구 뒤 문항','해설')")
            assert cursor.lastrowid >= 502
    print("Rollback including PostgreSQL writes and advanced ID passed", flush=True)


if __name__ == "__main__":
    log = Path(sys.argv[1]) / "spring.log"
    try:
        fixture()
        cutover()
        writer()
        http_test(log)
        rollback()
    except BaseException:
        if log.exists():
            print("\n".join(line for line in log.read_text().splitlines() if "ERROR" in line or "Caused by:" in line)[-7000:])
        raise
