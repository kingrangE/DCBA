import os
import json
import redis
import asyncio
import random
from apscheduler.schedulers.asyncio import AsyncIOScheduler
from app.services.exercise_service import exercise_service

class RedisQueue:
    def __init__(self, host="localhost", port=6379, db=0):
        self.redis_client = redis.Redis(host=host, port=port, db=db, decode_responses=True)
        self.queue_key = "exercise:generation_queue"

    def enqueue(self, task: dict):
        # Producer 역할, task가 들어오면 queue에 삽입 (LLM이 이전 요청을 모두 처리하지 못했는데, 요청이 오는경우 여기서 처리)
        try:
            self.redis_client.rpush(self.queue_key, json.dumps(task))
            print(f"[Producer] Enqueued task: {task['subject']} - {task['level']}")
        except Exception as e:
            print(f"[Producer Error] Failed to enqueue: {e}")

    def dequeue(self, timeout=0):
        # consumer 역할, 완료되면 queue에서 하나뺌
        try:
            result = self.redis_client.blpop(self.queue_key, timeout=timeout)
            if result:
                return json.loads(result[1])
        except Exception as e:
            print(f"[Consumer Error] Failed to dequeue: {e}")
        return None

    def size(self):
        # 현재 저장된 양 확인
        return self.redis_client.llen(self.queue_key)


class AutomationService:
    # 자동으로 30초마다 추가하는 시스템
    def __init__(self):
        # Redis 관련 정보
        redis_host = os.getenv("REDIS_HOST", "localhost")
        redis_port = int(os.getenv("REDIS_PORT", 6379))
        self.queue = RedisQueue(host=redis_host, port=redis_port) 

        self.scheduler = AsyncIOScheduler() # scheduler를 사용하여 계속 돌아가게끔(문제 Pool에 쌓이게끔)

        # 현재 scheduler 실행 여부 상태
        self.is_running = False
        
        self.subject_map = {
            "COMPUTER_ARCHITECTURE": "컴퓨터 구조",
            "OPERATING_SYSTEM": "운영체제",
            "COMPUTER_NETWORK": "컴퓨터 네트워크",
            "DATA_STRUCTURE": "자료구조",
            "ALGORITHM": "알고리즘",
            "DATABASE": "데이터베이스"
        }
        
        self.level_map = {
            "EASY": "난이도 1 (기초)",
            "MEDIUM": "난이도 2 (기본)",
            "HARD": "난이도 3 (심화)"
        }

    def start_producer(self):
        # 아직 안돌아가고 있으면 scheduler에 잡 넣고 시작
        if not self.scheduler.running:
            self.scheduler.add_job(self._produce_job, 'interval', seconds=30)
            self.scheduler.start()
            print("[Start] Producer Scheduler started.")

    def _produce_job(self):
        """The job that runs every 30s."""
        
        # 랜덤 선택
        selected_subject_key = random.choice(list(self.subject_map.keys()))
        selected_level_key = random.choice(list(self.level_map.keys()))
        
        # 잡 넣기 - 저장 및 Java 통신용으로는 Key(영어)를 사용, Prompt용으로는 Value(한글) 사용
        task = {
            "subject": selected_subject_key, # DB 저장용 (Java Enum)
            "level": selected_level_key,     # DB 저장용 (Java Enum)
            "subject_display": self.subject_map[selected_subject_key], # LLM 프롬프트용
            "level_display": self.level_map[selected_level_key]       # LLM 프롬프트용
        }
        self.queue.enqueue(task)

    async def start_consumer(self):
        # consumer
        print("[Consumer] Consumer process started.")
        self.is_running = True
        
        while self.is_running:
            try:

                # 현재 OS에 할당된 event loop 가져오기
                loop = asyncio.get_event_loop()
                # 기본 ThreadPoolExecutor 사용
                task = await loop.run_in_executor(None, self.queue.dequeue, 5) 
                
                if task:
                    print(f"[Consumer] Processing: {task['subject']} ({task['level']})")
                    # 문제 생성 시작
                    try:
                        from app.repositories.exercise_repository import exercise_repository
                        
                        # 문제 생성 
                        content = await loop.run_in_executor(
                            None, 
                            exercise_service.generate_exercise, 
                            task['subject_display'], 
                            task['level_display']
                        )
                        
                        # 파싱
                        parsed_data = exercise_service.parse_content(content)
                        question = parsed_data["question"]
                        answer = parsed_data["answer"]
                        
                        # 결과 잘 있으면 저장 
                        if question and answer:
                            saved_exercise = await loop.run_in_executor(
                                None, 
                                exercise_repository.save_exercise, 
                                task['subject'], 
                                task['level'], 
                                question, 
                                answer
                            )
                            print(f"[Consumer] Saved : {saved_exercise.question} (ID : {saved_exercise.id})")
                            
                        else: # 결과 잘 없으면 로그찍고 저장없이 다음
                            print(f"[Consumer] Failed to parse content: {content[:50]}...")

                    except Exception as e:
                        print(f"[Consumer] Failed generation: {e}")
                
                await asyncio.sleep(0.1) 
                
            except Exception as e:
                print(f"[Error] {e}")
                await asyncio.sleep(1)

    # 종료 
    def stop(self):
        self.is_running = False
        if self.scheduler.running: 
            self.scheduler.shutdown()

# 싱글톤을 위한 객체 생성
automation_service = AutomationService()
