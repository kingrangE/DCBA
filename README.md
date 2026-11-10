# DCBA
Daily CS, Base to Advanced

# Domain
[DCBA Service](https://DCBA.kingrange.site) <- You can access DCBA procject via this link.

# Tech Stack
1. **`Java Spring Boot`**
2. **`FastAPI`**
3. **`MariaDB`**
4. **`Redis`**
5. **`Elastic Search`**
5. **`Docker`**
6. **`Kubernetes`**
7. **`Grafana/Prometheus`**

# Service Flow
1. LLM-Server   
    - 30초마다 문제 생성 후, MariaDB Server에 저장
        - `Exaone 4.0`을 사용하여 문제 생성 (이후, 자체 OpenSource LLM Server를 구성하여 연결할 예정 (Exaone API 서비스 중단 대비))
        - 생성된 문제를 koNLPy를 이용하여 형태소 분석
            - 문제에서 명사를 추출하여 Redis에 저장
            - Jaccard 유사도 검사를 통해 Redis에 공통된 문제가 존재하는지 검사함.
    - Redis에 Token을 저장하여, 유사도 검사 수행
    
2. DCBA (Spring Web Application)
    - Web Service 제공
        - `유저 로그인 / 회원가입 / 대시보드 제공`
            - 유저는 대시보드에서 문제 Pool 조회 가능
            - 유저는 대시보드에서 자신이 공부하고 싶은 **문제를 저장**할 수 있음
            - 유저는 대시보드에서 더 이상 보고 싶지 않은 **문제를 차단**할 수 있음
            - 유저는 대시보드에서 자신이 공부하고 싶은 내용을 **검색하여 문제를 조회**할 수 있음

# File 
- DCBA : Java Spring Boot Web Application
- LLM-Server : LLM Python Server for DCBA 

## Database deployment

The `schema-migrate` Compose service owns schema changes. It waits for MariaDB, applies the versioned schema, and must finish successfully before either application starts. Java validates the schema in production; Python no longer creates tables while serving requests. Include `LLM-Server/migrations` when building the generation server image.

For an existing deployment, stop both application containers and back up MariaDB before upgrading. The first migration preserves rows, upgrades legacy problem identifiers to BIGINT, and applies preference uniqueness and foreign keys. Duplicate preferences, orphaned references, incomplete problems, or unexpected external references stop migration; review and repair those records before retrying. MariaDB schema changes commit individually, so keep applications stopped until migration succeeds. Completed migrations are recorded in `schema_migration` and reruns skip them. The second migration adds a subject/difficulty index and a difficulty index to both new and existing databases; it does not repeat the first migration or rewrite problem contents. Do not set schema auto-update or auto-create in production.

## Why did I decouple the LLM Service from the Main Server?
1. **`Leveraging the LLM Ecosystem`**
    - To fully utilize the Python-based AI ecosystem, including advanced libraries like koNLPy and various vector databases.

2.  **`Resource Isolation & Stability`**
    - By separating the CPU-intensive LLM processing from the main Java server, I ensured that high LLM loads do not degrade the performance or availability of the core web service.

3. **`Independent Scalability (Scale-out)`**
    - This architecture allows me to scale the LLM server independently based on its specific workload, which differs significantly from the main application's resource demands.

4. **`Technical Proficiency`**
    - I leveraged my extensive experience with FastAPI to rapidly build and iterate on a high-performance asynchronous API server.

## Internal service access

Redis is available only on the Compose network. MariaDB and Prometheus host ports bind to loopback; use an SSH tunnel for remote administration. Production Java management endpoints use the separate internal port 9091, which is not published by Compose. Application traffic continues to use port 8080.

## Authentication traffic

Login and signup share a limit of 10 POST attempts per client IP per minute, after CSRF validation. Rejected requests receive HTTP 429 and a retry delay. Counters hold at most 10,000 addresses per minute and run in memory for the current single Java server. The application uses the peer address and does not trust arbitrary forwarded headers. If a reverse proxy is used, configure trusted client-address handling there; before adding Java replicas, enforce shared limits at the trusted ingress. Users sharing one public IP share the same budget.

## Failed generation tasks

Redis persists attempt counts across consumer restarts. A task receives at most five processing attempts, with unfinished work reclaimed after ten minutes. Invalid payloads and exhausted tasks move atomically to `exercise:generation_failed`, preserving the original payload and failure category before leaving the active queue. This stream is retained for operator review; inspect it with `XRANGE exercise:generation_failed - +`. After correcting the cause, replay the stored payload into `exercise:generation_stream` without changing its original task identity so committed work remains idempotent. Do not delete quarantined tasks until their outcome has been reviewed.

Generation retries apply only to connection failures, request timeouts, temporary conflicts, rate limits, and upstream server errors, using capped exponential delays. Authentication, authorization, invalid model/request errors, and other permanent client errors stop immediately and the background task is quarantined for review.

## Generation alerts

A background check refreshes generation readiness, dependency availability, queue depth, and quarantined task count every 15 seconds without requiring traffic to the readiness endpoint. Prometheus loads `prometheus/alerts.yml` for backend availability, missing generation, first-generation failure, repeated failures, backlog, enqueue failure, quarantined work, and prolonged unready state. Validate the rules with `promtool check config` and `promtool test rules prometheus/alerts.test.yml`. Pending and firing alerts are visible in the Prometheus Alerts view through its loopback port or an SSH tunnel.

External notification delivery is not configured in this repository because no recipient or contact channel has been supplied. Connect these rules to the deployment's approved Alertmanager/contact channel before relying on unattended operation; no email or chat notifications have been sent by these changes.

## Problem filter indexes

The count and page queries retain their current filter and banned-problem semantics. In a separate MariaDB with 54,000 synthetic problems, filtered count queries changed from full table scans to indexed lookups after adding `(subject, level)` and `(level)` indexes. All four combinations of subject and difficulty filters returned the same counts and latest problem identifiers, including banned-problem exclusions. This checks the query plan and result preservation; production latency and data distribution have not been measured. Additional indexes were not added because these two cover the measured filter paths.

## Request and generation performance

Each dashboard derives the state already implied by its membership instead of querying it again: visible problems are not banned, saved-list entries are saved, and banned-list entries are banned. Cancellation retains the user lock and missing-problem checks, but uses an existence check and one conditional relationship deletion rather than loading the problem body and preference entity.

Generation reuses the accepted candidate's embedding after the database commit. The vector index checkpoint advances only after recording that vector. If saving fails, the candidate is not indexed; if the process stops or checkpoint recording fails after commit, the existing database synchronization can rebuild the missing index entry. The shared generation lock remains required.

Dashboards now show nine problems at a time, newest first, with First/Prev/Next navigation instead of page numbers or a final-page link. They fetch one extra problem to detect continuation and do not count the whole result. Next uses a positive `before` problem ID; Prev uses a positive `after` ID and reverses the query direction. Both cannot be supplied together. Links retain the active list, subject and difficulty; changing filters starts from the first result. Old page/size/sort query parameters no longer select a page. Empty cursor results retain a First link for recovery.

In the separate 54,000-problem MariaDB fixture, obtaining the same ten eligible rows at a deep position read 45,465 problem rows with OFFSET and ten with the cursor range. This is an actual-row-count comparison for that fixture, not a production latency or throughput benchmark. Forward/backward traversal, preference ownership, filters, new insertions, and single-query list loading are covered by the Java checks.

## CI build paths

The existing workflow and check names remain in place. Lint and language tests start independently; image publication on a main-branch push requires both to succeed. Superseded feature-branch runs can be cancelled, while an in-progress main-branch publication is allowed to finish. Each workflow also runs when its own configuration changes.

Java compiles and packages once in CI. Gradle build caching is enabled, but `test --rerun` forces the tests themselves to execute even with warm caches. Main-branch CI passes the successful `app.jar` to CD as a one-day, uncompressed artifact. The Docker `ci` target copies that exact JAR into the runtime image without invoking Gradle. The default Docker target still builds from source, using the same Gradle version as the wrapper. The CI target expects a context containing only `app.jar`; the normal source context excludes local build outputs and Gradle state.

Python CI uses Python 3.13, matching the runtime image's minor version, and installs `requirements-test.txt` with uv. That file includes the runtime requirements plus the test tools; the production image installs only `requirements.txt`. The uv cache key includes both manifests. Local development environments that run tests should install `requirements-test.txt` inside their virtual environment. Existing research-script dependencies remain available.

Docker Buildx exports separate GitHub Actions layer caches for Java and Python. These settings follow the [Docker cache documentation](https://docs.docker.com/build/ci/github-actions/cache/), and Python package caching follows the [uv integration guide](https://docs.astral.sh/uv/guides/integration/github/). Docker layer caching reuses unchanged dependency installation layers; it does not persist BuildKit cache mounts across changed dependency layers. Cached compilation does not replace actual test execution, as specified by the [Gradle task rerun option](https://docs.gradle.org/current/userguide/command_line_interface.html#sec:rerun_tasks).

Local validation covered actionlint, publication gates, artifact matching, Java tests, both Java image build targets, fresh Python dependency installation, Python tests in a fresh environment and Linux container, and the Python runtime image's imports and exclusion of test tools. GitHub-hosted durations, artifact transfer, remote cache hit rates, and image publication have not been measured or performed.
