# DCBA
Daily CS, Base to Advanced

# Domain
[DCBA Service](DCBA.kingrange.site) <- You can access DCBA procject via this link.

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

## Why did I decouple the LLM Service from the Main Server?
1. **`Leveraging the LLM Ecosystem`**
    - To fully utilize the Python-based AI ecosystem, including advanced libraries like koNLPy and various vector databases.

2.  **`Resource Isolation & Stability`**
    - By separating the CPU-intensive LLM processing from the main Java server, I ensured that high LLM loads do not degrade the performance or availability of the core web service.

3. **`Independent Scalability (Scale-out)`**
    - This architecture allows me to scale the LLM server independently based on its specific workload, which differs significantly from the main application's resource demands.

4. **`Technical Proficiency`**
    - I leveraged my extensive experience with FastAPI to rapidly build and iterate on a high-performance asynchronous API server.