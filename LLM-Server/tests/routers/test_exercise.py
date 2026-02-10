from unittest.mock import MagicMock
from app.models.exercise import Exercise

def test_generate_exercise(client, mocker):
    # =========== given ===========
    # exercise_service 대신 응답할 mock 제작
    mock_service = mocker.patch("app.routers.exercise.exercise_service")
    mock_service.generate_exercise.return_value = "Mocked LLM Content" # 문제 생성 고정 return 값
    mock_service.parse_content.return_value = { # 파싱 고정 리턴 값
        "question": "What is 1+1?",
        "answer": "2"
    }
    
    # exercise repo mock 제작
    mock_repo_cls = mocker.patch("app.repositories.exercise_repository.exercise_repository")
    mock_saved_exercise = Exercise(
        id=1,
        subject="ALGORITHM",
        level="EASY",
        question="What is 1+1?",
        answer="2"
    )
    mock_repo_cls.save_exercise.return_value = mock_saved_exercise # 위 객체를 고정으로 return
    
    # =========== when ===========
    # 확인을 위해 전송할 값
    payload = { 
        "subject": "ALGORITHM",
        "level": "EASY"
    }

    response = client.post("/exercise/generate", json=payload) # API 경로로 payload 보내서, 실제 HTTP 전송처럼 Test

    # =========== then ===========
    if response.status_code != 200: # 응답을 제대로 받는지 확인
        print(response.json()) # 제대로 받지 못했다면, 받은 응답 출력
        

    # 정상적으로 응답을 전달 받은는지 확인
    assert response.status_code == 200 
    data = response.json()
    assert data["subject"] == "ALGORITHM"
    assert data["question"] == "What is 1+1?"
    assert data["id"] == 1

    # mock가 제대로 호출되었는지 확인
    mock_service.generate_exercise.assert_called_once()
    mock_repo_cls.save_exercise.assert_called_once()
