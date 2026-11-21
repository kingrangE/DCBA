# PostgreSQL 전환 근거와 운영 절차

## S · 상황

DCBA의 문항 목록과 검색은 읽기 비중이 높다. MariaDB는 한글 부분 문자열을 빠르게 찾기 위해 3글자 조각 테이블과 트리거, 초기 채우기 작업을 유지한다. 초기 PostgreSQL GIN 구현은 흔한 검색어의 넓은 후보 집합을 확인하고 정렬하면서 초당 75건의 혼합 부하에서 목록 p99 6,469.5ms를 기록했다. [이슈 #49](https://github.com/kingrangE/DCBA/issues/49)

## T · 목표

문항 ID와 조회 동작을 유지하면서 PostgreSQL에서 검색 피크의 읽기 비용을 줄인다. 기존 로그인, 저장·차단, 복습, 관리자 삭제, Redis 작업 재전달, Chroma 문항 참조를 보존한다. 운영 데이터를 비운 대상에 이관하고 데이터 검증을 통과한 뒤 DB를 선택할 수 있게 한다. PostgreSQL에서 받은 쓰기도 비운 MariaDB로 되옮겨 보존할 수 있어야 한다.

## A · 구현과 선택 이유

- PostgreSQL에서 B-tree를 문항 ID와 커서 방향으로 읽는다. 분야·난이도와 화면별 저장·차단 규칙을 적용한 후보 최대 128건에서 페이지 9건과 다음 페이지 확인용 1건을 찾으면 중단한다. 적게 찾으면 그대로 전체 GIN 검색을 실행해 오래된 문항과 다음 커서를 확인한다. [PostgreSQL 인덱스 정렬](https://www.postgresql.org/docs/17/indexes-ordering.html), [pg_trgm](https://www.postgresql.org/docs/17/pgtrgm.html)
- 계정 이름의 대소문자와 악센트 비교에는 기본 강도의 ICU collation을 적용하고 DB에서 유일성을 확인한다. Unicode 문자에 따라 MariaDB와 ICU의 동등성 규칙은 다를 수 있다. 복사 중 계정이 충돌하면 트랜잭션이 롤백되므로 운영 사본으로 먼저 대조한다. [PostgreSQL collation](https://www.postgresql.org/docs/17/collation.html)
- Spring은 PostgreSQL JDBC와 전용 프로필을, 생성 서버는 psycopg를 사용한다. PostgreSQL의 세션 자문 잠금은 작업 레코드를 읽을 때부터 DB 커밋 이후 벡터 동기화가 끝날 때까지 유지되고 연결을 닫으면 해제된다. 문항과 완료 작업 행은 같은 트랜잭션에 기록하고 PostgreSQL은 `RETURNING`으로 문항 ID를 가져온다. [PostgreSQL 자문 잠금](https://www.postgresql.org/docs/17/explicit-locking.html#ADVISORY-LOCKS)
- 데이터 복사는 문항·계정·저장·차단·생성 작업·삭제 예정 문항·복습 기준의 7개 테이블을 비교한다. 행 수와 ID 순서에 따른 SHA-256, 새 ID가 참조하는 시퀀스의 다음 값을 검증한다. PostgreSQL의 이관 확인 표시는 검증된 데이터와 같은 트랜잭션에 기록한다. 기록이 있어야 운영용 PostgreSQL 프로필이 시작된다. 운영 이미지에는 MariaDB 경로도 있어 검증된 스냅샷으로 환경을 되돌릴 수 있다.

## R · 검증과 배포 판단

합성 문항 5만 건에서 초당 75건의 읽기 95% 혼합 부하를 세 번 반복했다. 최적화한 PostgreSQL에서 목록 p99는 **85.72ms → 15.37ms**, 분야·난이도 검색 p95는 **111.98ms → 11.30ms**였다. 초당 150건에서 성공 처리량 중앙값은 MariaDB 100.3건, PostgreSQL 150.0건이었다. 흔한 검색어의 PostgreSQL 본문 조회가 9,860행에서 38행으로 줄었다. API 검색과 페이징 등 37건을 대조했다. 초당 25건의 목록 p99는 MariaDB 19.91ms와 PostgreSQL 36.29ms였으며 초당 75건의 문항 추가·관리자 삭제 p95는 PostgreSQL이 높았다. 운영 판단에는 실제 부하의 쓰기 지연과 계정 비교를 함께 사용한다. [벤치마크 원본과 결과](postgresql-benchmark.md)

리허설은 MariaDB에서 300개 문항과 사용자·저장·차단·작업·삭제 기록·복습 기준을 PostgreSQL로 옮겼다. 독립 생성 요청 두 건이 같은 작업 ID로 재전달되었을 때 기존 완료문항을 받았으며, 중복 쓰기 오류 시 새 문항 전체가 롤백됐다. 인증된 Spring API에서 기존 계정의 대문자 로그인, 중복 가입 차단, 두 방향 커서, 오래된 문항, 리터럴 검색, 복습 표시와 관리자 삭제를 확인했다. PostgreSQL에 추가·삭제를 반영한 뒤 별도의 MariaDB에 복사해 7개 테이블 지문과 ID 증가를 다시 확인했다.

## 운영 전환

운영 자동화는 GitHub Actions의 **DCBA Production PostgreSQL Cutover**에서 실행한다. preflight는 현재 릴리스와 서비스 상태를 확인한다. cutover는 같은 EC2 배포 잠금을 잡고, 대상이 비어 있으면 쓰기를 멈춘다. MariaDB 덤프, Redis RDB와 사용 중인 Chroma 볼륨 백업은 호스트의 /opt/dcba/backups 아래에 기록한다. 이관과 내부 API 확인이 끝나면 PostgreSQL을 선택하고 공개 API를 점검한다. PostgreSQL 암호를 사전에 두지 않았으면 호스트에 권한 0600인 환경 파일을 생성한다. 공개 쓰기 재개 전 실패는 기존 MariaDB 재시작으로 처리한다. 이후 장애는 PostgreSQL을 기준으로 복구한다.

운영 EC2의 `/opt/dcba/.env`에 PostgreSQL 데이터베이스명과 권한이 분리된 관리자·서비스 암호를 설정한다. 변수 이름은 `.env.example`을 사용한다. 먼저 운영 사본으로 리허설하고, 원본 데이터와 Redis AOF 및 Chroma 볼륨의 복구본을 확인한다. 대상 저장소에는 배포 이미지의 릴리스 SHA가 체크아웃되어 있어야 한다.

1. 배포와 같은 `/opt/dcba/deploy.lock`을 잡고 데이터베이스에 접근하는 모든 쓰기 경로를 멈춘다. EC2에서는 프론트엔드, Java API와 LLM 워커를 중단한다. Redis 큐와 Chroma 볼륨은 유지한다. 이 상태에서 MariaDB와 Redis·Chroma를 백업한다.
2. 기존 MariaDB를 읽을 수 있는 같은 Compose 네트워크에서 PostgreSQL을 시작한다. `base`와 `pg`는 아래와 같이 정의한다. 이관은 소스와 대상이 안정된 상태에서 실행하며 빈 대상만 허용한다.
3. 7개 테이블 검증 결과와 새 ID, 운영 계정 샘플의 로그인 및 중복 가입, 검색·저장·차단, 큐 재처리를 확인한다. 충돌하거나 스키마가 달라 이관이 중단되면 기존 MariaDB 상태로 서비스를 재개할 수 있다.
4. 확인 후 `/opt/dcba/postgres.enabled` 파일을 만들고 PostgreSQL Compose로 서비스를 연다. 운영 배포 스크립트는 이 파일이 있을 때 PostgreSQL 오버레이와 이관 확인 조건을 적용한다.

```bash
cd /opt/dcba/repo
export DCBA_RELEASE=$(git rev-parse HEAD)
base=(docker compose -p dcba --env-file ../.env -f docker-compose.yml -f docker-compose.ec2.yml)
pg=("${base[@]}" -f docker-compose.postgres.yml)
"${base[@]}" stop frontend dcba-app llm-server
"${pg[@]}" up -d --wait postgres
"${pg[@]}" run --rm --no-deps -e DCBA_REQUIRE_POSTGRES_IMPORT=0 schema-migrate
"${pg[@]}" run --rm --no-deps schema-migrate python -m app.transfer_database maria postgres --writers-stopped
"${pg[@]}" run --rm --no-deps -e DCBA_REQUIRE_POSTGRES_IMPORT=1 schema-migrate
# Perform the recorded verification before enabling web writes.
touch ../postgres.enabled
DCBA_REQUIRE_POSTGRES_IMPORT=1 "${pg[@]}" up -d --no-build --wait
```

### PostgreSQL 쓰기 이후 롤백

1. 같은 배포 잠금 아래 프론트엔드, Java API와 LLM 워커를 멈추고 PostgreSQL과 Redis·Chroma를 백업한다.
2. 기존 MariaDB 볼륨은 보존하고, 원본과 별개의 **빈 MariaDB 데이터베이스**와 그 DB에 권한을 가진 서비스 계정을 준비한다. 새 MariaDB 이름과 계정은 운영 `.env`에 반영한다.
3. 기존 Compose의 MariaDB 마이그레이션을 새 DB에 실행한다. PostgreSQL 오버레이의 마이그레이션 컨테이너에서 `python -m app.transfer_database postgres maria --writers-stopped`를 실행한다. 두 DB의 비밀은 환경 변수에서 전달한다. PostgreSQL에서 새로 생긴 계정의 문자열 비교 충돌이 발견되면 복사는 롤백한다.
4. 지문과 시퀀스, 인증과 CRUD를 확인한 뒤 `postgres.enabled`를 제거하고 백업 고정본과 함께 기존 Compose로 서비스한다. Redis는 완료 작업 ID를 기준으로 재처리하며, 두 DB에서 같은 문항 ID를 사용하므로 Chroma 참조를 유지할 수 있다.

리허설 실행은 `(cd DCBA && ./gradlew bootJar)`로 JAR을 만든 뒤 저장소 루트에서 `bash scripts/test-postgres-cutover.sh`를 실행한다. Python에는 pymysql, psycopg, pydantic, httpx와 prometheus-client가 필요하다. Java CI에서 동일한 리허설을 실행한다.

에이전트 출제 릴리스는 PostgreSQL의 두 번째 마이그레이션에서 기존 완료 작업의 알림 필드와 문항 출제 이력을 추가한다. 이후 양방향 복사에서는 두 DB에 최신 마이그레이션을 적용하고, 기존 7개 테이블과 출제 이력 테이블을 함께 검증한다.
