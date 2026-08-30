# DCBA
Daily CS, Base to Advanced
LLM-Server/app/services/automation_service.py:68
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
    - 60초마다 문제 생성 작업을 등록하고, 생성 결과를 MariaDB Server에 저장
        - `kakaocorp/kanana-2-3b-instruct`를 로컬에서 지연 로딩하여 문제 생성
        - 첫 생성 시 Hugging Face 모델 가중치를 내려받으며, 이후에는 Docker 볼륨 캐시를 재사용
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

## Kanana LLM 실행

Kanana-2-3B는 모델 메모리가 필요한 로컬 추론 모델입니다. Docker Desktop에 충분한 메모리를 할당한 뒤 LLM 프로필을 실행합니다.

```bash
docker compose --profile llm up -d --build llm-server
```

모델과 생성 옵션은 `.env`의 `KANANA_MODEL_NAME`, `KANANA_DEVICE_MAP`, `KANANA_MAX_NEW_TOKENS`, `KANANA_TEMPERATURE`, `KANANA_TOP_P`로 조정할 수 있습니다. 첫 문제 생성은 모델 다운로드와 로딩 때문에 오래 걸릴 수 있습니다.

## Why did I decouple the LLM Service from the Main Server?
1. **`Leveraging the LLM Ecosystem`**
    - To fully utilize the Python-based AI ecosystem, including advanced libraries like koNLPy and various vector databases.

2.  **`Resource Isolation & Stability`**
    - By separating the CPU-intensive LLM processing from the main Java server, I ensured that high LLM loads do not degrade the performance or availability of the core web service.

3. **`Independent Scalability (Scale-out)`**
    - This architecture allows me to scale the LLM server independently based on its specific workload, which differs significantly from the main application's resource demands.

4. **`Technical Proficiency`**
    - I leveraged my extensive experience with FastAPI to rapidly build and iterate on a high-performance asynchronous API server.
