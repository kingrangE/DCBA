#!/usr/bin/env bash
set -Eeuo pipefail

mode=${1:-preflight}
[[ "$mode" == preflight || "$mode" == cutover ]] || exit 2
cd "${DCBA_DEPLOY_ROOT:-/opt/dcba}"
exec 9>deploy.lock
flock 9
[[ -r .env && -d repo/.git ]] || { echo "Existing DCBA release or configuration is missing" >&2; exit 1; }
cd repo
export DCBA_RELEASE
DCBA_RELEASE=$(git rev-parse HEAD)
base=(docker compose -p dcba --env-file ../.env -f docker-compose.yml -f docker-compose.ec2.yml)
marker=../postgres.enabled
secret_file=../postgres.env
backup_root=../backups
phase=preflight

if [[ -e "$marker" ]]; then
  active=("${base[@]}")
  if [[ -s "$secret_file" ]]; then
    active+=(--env-file "$secret_file")
  fi
  active+=(-f docker-compose.postgres.yml)
  "${active[@]}" exec -T dcba-app sh -ec '[ "$SPRING_PROFILES_ACTIVE" = prod,postgres ]'
  "${active[@]}" run --rm --no-deps -e DCBA_REQUIRE_POSTGRES_IMPORT=1 schema-migrate
  curl -fsS --max-time 10 -o /dev/null http://127.0.0.1:8080/api/auth/csrf
  echo "PostgreSQL already active and public API verified"
  exit 0
fi
[[ -f docker-compose.postgres.yml ]] || { echo "Deploy the PostgreSQL-capable release first" >&2; exit 1; }
for service in mariadb dcba-app redis frontend; do
  [[ -n $("${base[@]}" ps -q --status running "$service") ]] || { echo "Current $service container is unavailable" >&2; exit 1; }
done
curl -fsS --max-time 10 -o /dev/null http://127.0.0.1:8080/api/auth/csrf
echo "Current release $DCBA_RELEASE is serving from the MariaDB deployment"
df -Pm ..
if [[ "$mode" == preflight ]]; then
  exit 0
fi

umask 077
env_files=(--env-file ../.env)
if [[ ! -s "$secret_file" ]] &&
   ! { grep -Eq '^POSTGRES_ADMIN_PASSWORD=.+$' ../.env && grep -Eq '^POSTGRES_PASSWORD=.+$' ../.env; }; then
  admin_password=$(openssl rand -hex 32)
  app_password=$(openssl rand -hex 32)
  temporary=$(mktemp ../postgres.env.XXXXXX)
  {
    printf 'POSTGRES_ADMIN_PASSWORD=%s\n' "$admin_password"
    printf 'POSTGRES_PASSWORD=%s\n' "$app_password"
  } > "$temporary"
  mv -f "$temporary" "$secret_file"
  unset admin_password app_password
fi
if [[ -s "$secret_file" ]]; then
  env_files+=(--env-file "$secret_file")
fi
pg=(docker compose -p dcba "${env_files[@]}" -f docker-compose.yml -f docker-compose.ec2.yml -f docker-compose.postgres.yml)
migration_image="kingrange/dcba-llm-server:migration-$DCBA_RELEASE"

recover() {
  local result=$?
  if (( result == 0 )); then return; fi
  if [[ "$phase" == frozen && ! -e "$marker" ]]; then
    echo "Cutover failed before opening PostgreSQL writes. Restarting the original MariaDB release." >&2
    "${pg[@]}" stop dcba-app || true
    "${base[@]}" up -d --no-build --wait --wait-timeout 120 || true
    curl -fsS --max-time 10 -o /dev/null http://127.0.0.1:8080/api/auth/csrf || true
  elif [[ "$phase" == activated ]]; then
    echo "PostgreSQL may have accepted writes. Its activation marker and volumes are retained." >&2
    echo "Use the verified reverse transfer before restoring MariaDB." >&2
  fi
  return "$result"
}
trap recover EXIT
trap 'exit 143' TERM
trap 'exit 130' INT

"${pg[@]}" pull postgres schema-migrate
"${pg[@]}" up -d --wait --wait-timeout 120 postgres
"${pg[@]}" run --rm --no-deps -e DCBA_REQUIRE_POSTGRES_IMPORT=0 schema-migrate
# Reject a previously imported or populated target before stopping the live writers.
"${pg[@]}" run --rm --no-deps schema-migrate python -c '
from app.transfer_database import TABLES, connect, quoted
with connect("postgres") as db, db.cursor() as cursor:
    for table in (*TABLES, "dcba_cutover"):
        name = quoted("postgres", table)
        cursor.execute(f"SELECT 1 FROM {name} LIMIT 1")
        if cursor.fetchone():
            raise SystemExit(f"Destination table {table} is populated; preserve it and use a new target")
'

mkdir -p "$backup_root"
backup=$(mktemp -d "$backup_root/postgres-cutover.XXXXXXXX")
llm_was_running=$("${base[@]}" --profile llm ps -q --status running llm-server)
phase=frozen
"${base[@]}" stop frontend dcba-app llm-server
echo "Application writers stopped. Backup identifier: $(basename "$backup")"
"${base[@]}" exec -T mariadb sh -ec '
  export MYSQL_PWD="$MARIADB_ROOT_PASSWORD"
  exec mariadb-dump -uroot --single-transaction --quick --routines --events --triggers "$MARIADB_DATABASE"
' | gzip > "$backup/mariadb.sql.gz"
gzip -t "$backup/mariadb.sql.gz"
test -s "$backup/mariadb.sql.gz"

"${base[@]}" exec -T redis redis-cli SAVE | grep -qx OK
"${base[@]}" exec -T redis redis-check-rdb /data/dump.rdb >/dev/null
"${base[@]}" cp redis:/data/dump.rdb "$backup/redis.rdb"
test -s "$backup/redis.rdb"
if docker volume inspect dcba_chroma_data >/dev/null 2>&1; then
  docker run --rm --network none -v dcba_chroma_data:/snapshot:ro --entrypoint tar \
    "$migration_image" -czf - -C /snapshot . > "$backup/chroma.tar.gz"
  tar -tzf "$backup/chroma.tar.gz" >/dev/null
fi
echo "MariaDB, Redis and any present Chroma backup passed archive checks"

"${pg[@]}" run --rm --no-deps schema-migrate python -m app.transfer_database maria postgres --writers-stopped
export DCBA_REQUIRE_POSTGRES_IMPORT=1
"${pg[@]}" run --rm --no-deps schema-migrate
"${pg[@]}" up -d --no-build --wait --wait-timeout 120 dcba-app
"${pg[@]}" exec -T dcba-app sh -ec '[ "$SPRING_PROFILES_ACTIVE" = prod,postgres ]'
# The API port is internal and the public frontend remains stopped here.
"${pg[@]}" run --rm --no-deps schema-migrate python -c '
from urllib.request import urlopen
import time
for attempt in range(25):
    try:
        with urlopen("http://dcba-app:8080/api/auth/csrf", timeout=3) as response:
            if response.status == 200:
                break
    except OSError:
        time.sleep(2)
else:
    raise SystemExit("Internal PostgreSQL application did not become ready")
'

touch "$marker"
phase=activated
"${pg[@]}" up -d --no-build --wait --wait-timeout 120
if [[ -n "$llm_was_running" ]]; then
  "${pg[@]}" exec -T llm-server sh -ec '[ "$DB_ENGINE" = postgres ]'
fi
curl -fsS --retry 15 --retry-all-errors --retry-delay 2 --max-time 60 \
  -o /dev/null http://127.0.0.1:8080/api/auth/csrf
echo "PostgreSQL activated for release $DCBA_RELEASE and public API verified"
