import os
import json
import redis
import asyncio
import random
import uuid
import time
from prometheus_client import Counter
from openai import APIStatusError

TASK_RESULTS = Counter("dcba_generation_tasks_total", "Generation task outcomes", ["result"])
from apscheduler.schedulers.asyncio import AsyncIOScheduler
from app.services.exercise_service import exercise_service
from app.models.exercise import ExerciseRequest, SUBJECT_LABELS, LEVEL_LABELS

class RedisQueue:
    def __init__(self, host="localhost", port=6379, db=0):
        self.redis_client = redis.Redis(host=host, port=port, db=db, decode_responses=True,
                                        socket_connect_timeout=5, socket_timeout=10)
        self.queue_key = "exercise:generation_stream"
        self.legacy_key = "exercise:generation_queue"
        self.attempts_key = "exercise:generation_attempts"
        self.failed_key = "exercise:generation_failed"
        self.group = "exercise-generators"
        self.consumer = str(uuid.uuid4())
        self.retry_idle_ms = 600_000
        self.claim_cursor = "0-0"

    def enqueue(self, task: dict):
        request = ExerciseRequest(**task)
        payload = {**request.model_dump(mode="json"), "task_id": str(uuid.uuid4())}
        result = self.redis_client.eval("""
            if redis.call('XLEN', KEYS[1]) + redis.call('LLEN', KEYS[2]) >= 1000 then return false end
            return redis.call('XADD', KEYS[1], '*', 'payload', ARGV[1])
        """, 2, self.queue_key, self.legacy_key, json.dumps(payload))
        if not result:
            raise RuntimeError("Generation queue is full")

    def dequeue(self, timeout=5):
        try:
            self.redis_client.xgroup_create(self.queue_key, self.group, id="0-0", mkstream=True)
        except Exception as error:
            if not str(error).startswith("BUSYGROUP"):
                raise
        # Move old deployments' queued work without a gap between removal and insertion.
        self.redis_client.eval("""
            if redis.call('XLEN', KEYS[1]) >= 1000 then return false end
            local payload = redis.call('LINDEX', KEYS[2], 0)
            if not payload then return false end
            local id = redis.call('XADD', KEYS[1], '*', 'payload', payload)
            redis.call('LPOP', KEYS[2])
            return id
        """, 2, self.queue_key, self.legacy_key)
        claimed = self.redis_client.xautoclaim(
            self.queue_key, self.group, self.consumer, self.retry_idle_ms,
            start_id=self.claim_cursor, count=1,
        )
        self.claim_cursor = claimed[0]
        messages = claimed[1]
        if not messages:
            result = self.redis_client.xreadgroup(self.group, self.consumer,
                        {self.queue_key: ">"}, count=1, block=max(1, timeout * 1000))
            messages = result[0][1] if result else []
        if not messages:
            return None
        message_id, fields = messages[0]
        message = {
            "_message_id": message_id,
            "_attempt": self.redis_client.hincrby(self.attempts_key, message_id, 1),
            "_payload": fields.get("payload", ""),
        }
        if message["_attempt"] > 5:
            self.reject(message, "Retry limit reached after interrupted processing")
            return None
        try:
            payload = json.loads(message["_payload"])
            request = ExerciseRequest(**payload)
            task_id = payload.get("task_id") or "legacy:" + message_id
            if not isinstance(task_id, str) or len(task_id) > 128:
                raise ValueError("Invalid task identity")
            return {**request.model_dump(mode="json"), "task_id": task_id, **message}
        except (ValueError, TypeError):
            self.reject(message, "Invalid task payload")
            return None

    def acknowledge(self, task):
        with self.redis_client.pipeline() as transaction:
            transaction.xack(self.queue_key, self.group, task["_message_id"])
            transaction.xdel(self.queue_key, task["_message_id"])
            transaction.hdel(self.attempts_key, task["_message_id"])
            transaction.execute()

    def reject(self, task, reason):
        # Preserve the original payload before removing pending work; Lua stops before removal if storage fails.
        self.redis_client.eval("""
            redis.call('XADD', KEYS[2], '*', 'original_id', ARGV[1], 'payload', ARGV[2], 'reason', ARGV[3])
            redis.call('XACK', KEYS[1], ARGV[4], ARGV[1])
            redis.call('XDEL', KEYS[1], ARGV[1])
            redis.call('HDEL', KEYS[3], ARGV[1])
            return 1
        """, 3, self.queue_key, self.failed_key, self.attempts_key,
            task["_message_id"], task["_payload"], reason, self.group)
        TASK_RESULTS.labels("dead_letter").inc()

    def failed(self, task, error):
        permanent_api_error = (isinstance(error, APIStatusError)
                               and error.status_code < 500 and error.status_code not in (408, 409, 429))
        if isinstance(error, ValueError) or permanent_api_error or task["_attempt"] >= 5:
            self.reject(task, type(error).__name__)

    def size(self):
        return self.redis_client.xlen(self.queue_key) + self.redis_client.llen(self.legacy_key)


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
        self.last_success_at = None
        self.consecutive_failures = 0
        self.producer_failed = False
        self.queue_depth = -1
        
        self.subject_map = SUBJECT_LABELS
        self.level_map = LEVEL_LABELS

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
        }
        try:
            self.queue.enqueue(task)
            self.queue_depth = self.queue.size()
            self.producer_failed = False
        except Exception:
            self.producer_failed = True
            TASK_RESULTS.labels("enqueue_failure").inc()
            raise

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
                        saved_exercise = await loop.run_in_executor(
                            None, exercise_service.generate_and_save, task['subject'], task['level'], task['task_id']
                        )
                        await loop.run_in_executor(None, self.queue.acknowledge, task)
                        self.last_success_at = time.time()
                        self.consecutive_failures = 0
                        TASK_RESULTS.labels("success").inc()
                        self.queue_depth = await loop.run_in_executor(None, self.queue.size)
                        print(f"[Consumer] Saved exercise {saved_exercise.id}")

                    except Exception as e:
                        await loop.run_in_executor(None, self.queue.failed, task, e)
                        self.consecutive_failures += 1
                        TASK_RESULTS.labels("failure").inc()
                        print(f"[Consumer] Failed generation: {e}")
                
                await asyncio.sleep(0.1) 
                
            except Exception as e:
                self.consecutive_failures += 1
                self.queue_depth = -1
                TASK_RESULTS.labels("queue_failure").inc()
                print(f"[Error] {e}")
                await asyncio.sleep(1)

    # 종료 
    def stop(self):
        self.is_running = False
        if self.scheduler.running: 
            self.scheduler.shutdown()

# 싱글톤을 위한 객체 생성
automation_service = AutomationService()
