from fastapi import FastAPI
from fastapi.responses import JSONResponse
from prometheus_client import Gauge
import time
from app.routers import exercise
from app.services.automation_service import automation_service
from contextlib import asynccontextmanager, suppress
from prometheus_fastapi_instrumentator import Instrumentator
import asyncio

@asynccontextmanager
async def lifespan(app: FastAPI):
    # 시작 시 실행
    automation_service.start_producer()
    consumer = asyncio.create_task(automation_service.start_consumer())
    app.state.consumer_task = consumer
    monitor = asyncio.create_task(monitor_generation())
    try:
        yield
    finally:
        automation_service.stop()
        monitor.cancel()
        with suppress(asyncio.CancelledError):
            await monitor
        try:
            await asyncio.wait_for(consumer, timeout=10)
        except asyncio.TimeoutError:
            pass

app = FastAPI(lifespan=lifespan)
Instrumentator().instrument(app).expose(app)
GENERATION_READY = Gauge("dcba_generation_ready", "Result of the latest readiness check")

@app.get("/health")
def health_check():
    return {"status": "ok"}

def database_available():
    from app.repositories.exercise_repository import exercise_repository
    with exercise_repository.get_connection() as connection:
        connection.ping(reconnect=False)
    return True


@app.get("/ready")
async def readiness():
    depth, database, quarantined = await asyncio.gather(
        asyncio.wait_for(asyncio.to_thread(automation_service.queue.size), timeout=3),
        asyncio.wait_for(asyncio.to_thread(database_available), timeout=3),
        asyncio.wait_for(asyncio.to_thread(automation_service.queue.failed_size), timeout=3),
        return_exceptions=True,
    )
    redis_available = (isinstance(depth, int) and not isinstance(depth, bool)
                       and isinstance(quarantined, int) and not isinstance(quarantined, bool))
    automation_service.queue_depth = depth if redis_available else -1
    automation_service.quarantined_tasks = quarantined if isinstance(quarantined, int) and not isinstance(quarantined, bool) else -1
    consumer = getattr(app.state, "consumer_task", None)
    consumer_running = consumer is not None and not consumer.done() and automation_service.is_running
    last_success = automation_service.last_success_at
    recent_success = last_success is not None and time.time() - last_success < 600
    ready = (consumer_running and recent_success and redis_available and database is True
             and depth <= 20 and automation_service.consecutive_failures == 0
             and not automation_service.producer_failed)
    GENERATION_READY.set(int(ready))
    return JSONResponse(status_code=200 if ready else 503, content={
        "status": "ready" if ready else "degraded",
        "consumer_running": consumer_running,
        "database_available": database is True,
        "redis_available": redis_available,
        "queue_depth": depth if redis_available else None,
        "quarantined_tasks": automation_service.quarantined_tasks,
        "last_success_timestamp": last_success,
        "consecutive_failures": automation_service.consecutive_failures,
        "producer_failed": automation_service.producer_failed,
    })


async def monitor_generation():
    while True:
        try:
            await readiness()
        except Exception:
            GENERATION_READY.set(0)
        await asyncio.sleep(15)


Gauge("dcba_generation_quarantined_tasks", "Tasks preserved for operator review").set_function(lambda: automation_service.quarantined_tasks)
Gauge("dcba_generation_queue_depth", "Waiting and unacknowledged generation tasks").set_function(lambda: automation_service.queue_depth)
Gauge("dcba_generation_last_success_timestamp_seconds", "Last completed generation timestamp").set_function(lambda: automation_service.last_success_at or 0)
Gauge("dcba_generation_consecutive_failures", "Consecutive consumer failures").set_function(lambda: automation_service.consecutive_failures)
Gauge("dcba_generation_producer_failed", "Whether the last enqueue failed").set_function(lambda: int(automation_service.producer_failed))

app.include_router(exercise.router)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
