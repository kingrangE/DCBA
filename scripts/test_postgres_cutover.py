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
        cursor.execute("INSERT INTO generation_task VALUES ('before-migration', 300)")
        cursor.execute("INSERT INTO deleted_exercise VALUES (301)")
        cursor.execute("INSERT INTO exercise_feedback_rubric VALUES (300, %s)", ('[{"id":"test"}]',))
        cursor.execute("ALTER TABLE exercise AUTO_INCREMENT = 500")
        connection.commit()


def cutover():
    migrate_postgres(require_import=False)
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
        finally:
            process.terminate()
            process.wait(timeout=15)
    print("Authenticated PostgreSQL search, pagination, account case, review and deletion passed", flush=True)


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
