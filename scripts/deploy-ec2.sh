#!/usr/bin/env bash
set -euo pipefail

release=${1:?Provide the release commit SHA}
[[ "$release" =~ ^[0-9a-f]{40}$ ]] || { echo "Invalid release SHA" >&2; exit 1; }

cd /opt/dcba
exec 9>deploy.lock
flock 9
[[ -r .env ]] || { echo "Missing /opt/dcba/.env" >&2; exit 1; }

if [[ ! -d repo/.git ]]; then
  git clone --no-checkout https://github.com/kingrangE/DCBA.git repo
fi
cd repo
git fetch --no-tags origin main
if [[ "$(git rev-parse FETCH_HEAD)" != "$release" ]]; then
  echo "Skipping superseded release $release"
  exit 0
fi
git checkout --detach "$release"

export DCBA_RELEASE="$release"
compose=(docker compose -p dcba --env-file ../.env -f docker-compose.yml -f docker-compose.ec2.yml)
if [[ -f ../postgres.enabled ]]; then
  export DCBA_REQUIRE_POSTGRES_IMPORT=1
  compose+=(-f docker-compose.postgres.yml)
fi
"${compose[@]}" pull
"${compose[@]}" up -d --no-build --wait --wait-timeout 120
curl --fail --silent --show-error --retry 20 --retry-all-errors --retry-delay 2 \
  --output /dev/null http://127.0.0.1:8080/api/auth/csrf
echo "DCBA $release is serving web requests"
if [[ ! -f ../postgres.enabled ]]; then
  "${compose[@]}" up -d --no-build --no-deps --force-recreate search-index
fi
