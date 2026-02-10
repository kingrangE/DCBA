from fastapi import FastAPI
from app.routers import exercise
from app.services.automation_service import automation_service
from contextlib import asynccontextmanager
import asyncio

app = FastAPI()

@asynccontextmanager
async def lifespan(app: FastAPI):
    automation_service.start_producer()
    asyncio.create_task(automation_service.start_consumer())
    yield

@app.get("/health")
def health_check():
    return {"status": "ok"}

app.include_router(exercise.router)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
