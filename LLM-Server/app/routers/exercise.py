import os
import secrets
from fastapi import APIRouter, Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from app.models.exercise import ExerciseRequest
from app.services.exercise_service import exercise_service

def require_api_key(credentials: HTTPAuthorizationCredentials | None = Depends(HTTPBearer(auto_error=False))):
    expected = os.getenv("LLM_API_KEY")
    if not expected:
        raise HTTPException(status_code=503, detail="Generation API is not configured")
    if credentials is None or not secrets.compare_digest(credentials.credentials.encode(), expected.encode()):
        raise HTTPException(status_code=401, detail="Invalid API credentials", headers={"WWW-Authenticate": "Bearer"})


router = APIRouter(
    prefix="/exercise", # api 경로 prefix 설정 /exercise/~~~
    tags=["exercise"],
    dependencies=[Depends(require_api_key)],
)

@router.post("/generate") # /exercise/generate
def generate_exercise(request: ExerciseRequest):
    try:
        return exercise_service.generate_and_save(request.subject.value, request.level.value)
    except Exception:
        raise HTTPException(status_code=503, detail="Generation is temporarily unavailable")
