from fastapi import FastAPI
from app.routers import exercise
from app.services.automation_service import automation_service
from contextlib import asynccontextmanager
from prometheus_fastapi_instrumentator import Instrumentator
import asyncio

@asynccontextmanager
async def lifespan(app: FastAPI):
    # 시작 시 실행
    automation_service.start_producer()
    asyncio.create_task(automation_service.start_consumer())
    yield
    # 종료 시 실행
    automation_service.stop()

app = FastAPI(lifespan=lifespan)
Instrumentator().instrument(app).expose(app)

@app.get("/health")
def health_check():
    return {"status": "ok"}

app.include_router(exercise.router)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
