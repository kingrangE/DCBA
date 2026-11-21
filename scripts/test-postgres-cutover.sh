#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_python=python3
if (($#)); then test_python=$1; fi
work=$(mktemp -d)
maria=""
postgres=""
redis=""
cleanup() {
  for container in "$maria" "$postgres" "$redis"; do
    [[ -z "$container" ]] || docker stop "$container" >/dev/null
  done
  rm -rf "$work"
}
trap cleanup EXIT
maria=$(docker run -d --rm -p 127.0.0.1::3306 -e MARIADB_ROOT_PASSWORD=fixture -e MARIADB_DATABASE=dcba mariadb:10.11 --collation-server=utf8mb4_general_ci)
postgres=$(docker run -d --rm -p 127.0.0.1::5432 -e POSTGRES_PASSWORD=fixture-admin -e POSTGRES_DB=dcba -e POSTGRES_APP_USER=dcba -e POSTGRES_APP_PASSWORD=fixture -v "$PWD/scripts/postgres-init.sql:/docker-entrypoint-initdb.d/001-dcba-role.sql:ro" postgres:17-bookworm)
redis=$(docker run -d --rm -p 127.0.0.1::6379 redis:7-alpine)
for _ in {1..40}; do
  if docker exec "$maria" healthcheck.sh --connect --innodb_initialized >/dev/null 2>&1 &&
     docker exec "$postgres" pg_isready -h 127.0.0.1 -U postgres -d dcba >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
export MARIADB_PORT=$(docker port "$maria" 3306/tcp | awk -F: '{print $NF}')
export POSTGRES_PORT=$(docker port "$postgres" 5432/tcp | awk -F: '{print $NF}')
export REDIS_PORT=$(docker port "$redis" 6379/tcp | awk -F: '{print $NF}')
export MARIADB_HOST=127.0.0.1 MARIADB_USER=root MARIADB_PASSWORD=fixture MARIADB_DATABASE=dcba
export POSTGRES_HOST=127.0.0.1 POSTGRES_USER=dcba POSTGRES_PASSWORD=fixture POSTGRES_DB=dcba
export PYTHONPATH="$PWD/LLM-Server"
docker exec -e MYSQL_PWD=fixture "$maria" mariadb -uroot -e 'CREATE DATABASE dcba_rollback CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci'
"$test_python" scripts/test_postgres_cutover.py "$work"
