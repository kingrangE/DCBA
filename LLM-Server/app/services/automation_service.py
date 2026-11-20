import os
import json
import redis
import asyncio
import random
import uuid
import time
from prometheus_client import Counter, Histogram
from openai import APIStatusError

TASK_RESULTS = Counter("dcba_generation_tasks_total", "Generation task outcomes", ["result"])
MODEL_CALLS = Counter("dcba_generation_provider_calls_total", "Provider calls reserved by the worker", ["kind"])
QUEUE_WAIT = Histogram("dcba_generation_queue_wait_seconds", "Time from enqueue to first delivery")
WORK_TIME = Histogram("dcba_generation_work_seconds", "Time handling a generation delivery")
from apscheduler.schedulers.asyncio import AsyncIOScheduler
from app.services.exercise_service import exercise_service
from app.models.exercise import QueuedExerciseRequest, Level, SUBJECT_LABELS
from app.services.generation_workflow import GenerationRejected, SearchUnavailable

class RedisQueue:
    AUTO_GENERATION_PAUSE_KEY = "exercise:auto_generation_paused"

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

    def enqueue(self, task: dict, *, only_when_idle: bool = False) -> bool:
        request = QueuedExerciseRequest(subject=task["subject"], level=task["level"], request_type="automatic")
        payload = {**request.model_dump(mode="json", exclude_none=True), "task_id": str(uuid.uuid4())}
        result = self.redis_client.eval("""
            if ARGV[2] == '1' and redis.call('EXISTS', KEYS[3]) == 1 then return -1 end
            local depth = redis.call('XLEN', KEYS[1]) + redis.call('LLEN', KEYS[2])
            if ARGV[2] == '1' and depth > 0 then return -1 end
            if depth >= 1000 then return false end
            return redis.call('XADD', KEYS[1], '*', 'payload', ARGV[1])
        """, 3, self.queue_key, self.legacy_key, self.AUTO_GENERATION_PAUSE_KEY,
            json.dumps(payload), "1" if only_when_idle else "0")
        if result == -1:
            return False
        if not result:
            raise RuntimeError("Generation queue is full")
        return True

    def is_auto_generation_paused(self) -> bool:
        return bool(self.redis_client.exists(self.AUTO_GENERATION_PAUSE_KEY))

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
        try:
            payload = json.loads(message["_payload"])
            task_id = payload.get("task_id") or "legacy:" + message_id
            if not isinstance(task_id, str) or len(task_id) > 128:
                raise ValueError("Invalid task identity")
            message["task_id"] = task_id
            request = QueuedExerciseRequest(**payload)
            if message["_attempt"] > 5:
                self.reject(message, "temporary_failure")
                return None
            return {**request.model_dump(mode="json"), "task_id": task_id, **message}
        except (ValueError, TypeError, AttributeError):
            self.reject(message, "invalid_request")
            return None

    def state_key(self, task_id):
        return f"exercise:generation_request:{task_id}"

    def start(self, task):
        key = self.state_key(task["task_id"])
        if self.redis_client.hget(key, "status") == "failed":
            return False
        if task["_attempt"] == 1:
            QUEUE_WAIT.observe(max(0, time.time() - int(task["_message_id"].split("-")[0]) / 1000))
        if task.get("requester_id") is not None:
            self.redis_client.hset(key, "status", "running")
        return True

    def reserve_call(self, task_id, kind):
        count = self.redis_client.eval("""
            local count = tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0')
            if ARGV[1] == 'model' and count >= 6 then return -1 end
            return redis.call('HINCRBY', KEYS[1], ARGV[1], 1)
        """, 1, self.state_key(task_id), kind)
        if count == -1:
            raise GenerationRejected("generation_limit")
        MODEL_CALLS.labels(kind).inc()

    def acknowledge(self, task):
        with self.redis_client.pipeline() as transaction:
            transaction.xack(self.queue_key, self.group, task["_message_id"])
            transaction.xdel(self.queue_key, task["_message_id"])
            transaction.hdel(self.attempts_key, task["_message_id"])
            if task.get("task_id"):
                transaction.expire(self.state_key(task["task_id"]), 604800)
            transaction.execute()

    def reject(self, task, reason):
        # Keep only fields needed to diagnose or requeue the task, with no free text.
        try:
            payload = json.loads(task["_payload"])
            request = QueuedExerciseRequest(subject=payload["subject"], level=payload["level"])
            safe_payload = request.model_dump(mode="json", include={"subject", "level"})
        except (ValueError, TypeError, KeyError):
            safe_payload = {}
        task_id = task.get("task_id")
        if task_id:
            safe_payload["task_id"] = task_id
        self.redis_client.eval("""
            redis.call('XADD', KEYS[2], '*', 'original_id', ARGV[1], 'payload', ARGV[2], 'reason', ARGV[3])
            if ARGV[5] == '1' then redis.call('HSET', KEYS[4], 'status', 'failed', 'reason', ARGV[3]) end
            redis.call('XACK', KEYS[1], ARGV[4], ARGV[1])
            redis.call('XDEL', KEYS[1], ARGV[1])
            redis.call('HDEL', KEYS[3], ARGV[1])
            return 1
        """, 4, self.queue_key, self.failed_key, self.attempts_key, self.state_key(task_id),
            task["_message_id"], json.dumps(safe_payload), reason, self.group, "1" if task_id else "0")
        TASK_RESULTS.labels("dead_letter").inc()

    def failed(self, task, error):
        permanent_api_error = (isinstance(error, APIStatusError)
                               and error.status_code < 500 and error.status_code not in (408, 409, 429))
        if isinstance(error, ValueError) or permanent_api_error or task["_attempt"] >= 5:
            self.reject(task, error.code if isinstance(error, GenerationRejected) else
                        "search_unavailable" if isinstance(error, SearchUnavailable) else
                        "invalid_request" if isinstance(error, ValueError) else "temporary_failure")

    def failed_size(self):
        return self.redis_client.xlen(self.failed_key)

    def size(self):
        return self.redis_client.xlen(self.queue_key) + self.redis_client.llen(self.legacy_key)


class AutomationService:
    def __init__(self):
        self.auto_generation_enabled = os.getenv("AUTO_GENERATION_ENABLED", "true").lower() == "true"
        self.generation_interval_seconds = int(os.getenv("AUTO_GENERATION_INTERVAL_SECONDS", "60"))
        if self.generation_interval_seconds <= 0:
            raise ValueError("AUTO_GENERATION_INTERVAL_SECONDS must be positive")
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
        self.quarantined_tasks = -1
        
        self.subject_map = SUBJECT_LABELS

    def start_producer(self):
        # 아직 안돌아가고 있으면 scheduler에 잡 넣고 시작
        if self.auto_generation_enabled and not self.scheduler.running:
            self.scheduler.add_job(self._produce_job, 'interval', seconds=self.generation_interval_seconds)
            self.scheduler.start()
            print(f"[Start] Automatic generation interval: {self.generation_interval_seconds}s")

    def _produce_job(self):
        # 랜덤 선택
        selected_subject_key = random.choice(list(self.subject_map.keys()))
        selected_level = random.choices((Level.EASY, Level.MEDIUM, Level.HARD), weights=(1, 4, 2))[0]
        
        # 잡 넣기 - 저장 및 Java 통신용으로는 Key(영어)를 사용, Prompt용으로는 Value(한글) 사용
        task = {
            "subject": selected_subject_key, # DB 저장용 (Java Enum)
            "level": selected_level.value,   # DB 저장용 (Java Enum)
        }
        try:
            self.queue.enqueue(task, only_when_idle=True)
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
                        saved_exercise = None
                        if await loop.run_in_executor(None, self.queue.start, task):
                            with WORK_TIME.time():
                                saved_exercise = await loop.run_in_executor(
                                    None, exercise_service.run_queued, task["task_id"], QueuedExerciseRequest(**task),
                                    self.queue.reserve_call,
                                )
                        await loop.run_in_executor(None, self.queue.acknowledge, task)
                        self.consecutive_failures = 0
                        if saved_exercise is not None:
                            self.last_success_at = time.time()
                            TASK_RESULTS.labels("success").inc()
                            print(f"[Consumer] Saved exercise {saved_exercise.id}")
                        self.queue_depth = await loop.run_in_executor(None, self.queue.size)

                    except Exception as e:
                        await loop.run_in_executor(None, self.queue.failed, task, e)
                        if isinstance(e, GenerationRejected):
                            TASK_RESULTS.labels("content_rejected").inc()
                        else:
                            self.consecutive_failures += 1
                            TASK_RESULTS.labels("failure").inc()
                        print(f"[Consumer] Failed generation: {type(e).__name__}")
                
                await asyncio.sleep(0.1) 
                
            except Exception as e:
                self.consecutive_failures += 1
                self.queue_depth = -1
                TASK_RESULTS.labels("queue_failure").inc()
                print(f"[Error] {type(e).__name__}")
                await asyncio.sleep(1)

    # 종료 
    def stop(self):
        self.is_running = False
        if self.scheduler.running: 
            self.scheduler.shutdown()

# 싱글톤을 위한 객체 생성
automation_service = AutomationService()
