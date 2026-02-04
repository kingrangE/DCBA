from fastapi import FastAPI
from app.routers import exercise
from app.services.automation_service import automation_service
import asyncio

app = FastAPI()

@app.on_event("startup")
async def startup_event():
    automation_service.start_producer()
    asyncio.create_task(automation_service.start_consumer())

@app.get("/health")
def health_check():
    return {"status": "ok"}

app.include_router(exercise.router)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
