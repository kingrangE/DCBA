from fastapi import APIRouter, HTTPException
from app.models.exercise import ExerciseRequest
from app.services.exercise_service import exercise_service

router = APIRouter( 
    prefix="/exercise", # api 경로 prefix 설정 /exercise/~~~
    tags=["exercise"] # swagger 그룹(확장할 수도 있으니까)
)

@router.post("/generate") # /exercise/generate
async def generate_exercise(request: ExerciseRequest): 
    try:
        from app.repositories.exercise_repository import exercise_repository
        
        # 문제 생성
        content = exercise_service.generate_exercise(request.subject, request.level)
        
        # 생성된 내용 파싱 (Q:~ A:~)
        title = ""
        best_answer = ""
        for line in content.split("\n"):
            processed_line = line.strip()
            if processed_line.startswith("Q:"):
                title = processed_line.replace("Q:", "").strip()
            elif processed_line.startswith("A:"):
                best_answer = processed_line.replace("A:", "").strip()
        
        if not title or not best_answer: #파싱 제대로 안되면 에러 레이즈
            raise Exception("Failed to parse generated content")

        # 저장 
        saved_exercise = exercise_repository.save_exercise(
            request.subject, 
            request.level, 
            title, 
            best_answer
        )
        
        # Exercise 객체 반환
        return saved_exercise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
