import pytest
import asyncio
import json
from unittest.mock import MagicMock
from app.services.automation_service import automation_service, RedisQueue

def test_redis_queue_enqueue():
    # =========== given =========== 
    # conftest에서 정의한 Redis Mock Get
    queue = RedisQueue() 
    task = {"subject": "ALGORITHM", "level": "EASY"}
    
    # =========== when =========== 
    # queue에 task mock 삽입
    queue.enqueue(task) 
    
    # =========== then =========== 
    # 1개를 삽입했으므로 rpush가 한 번 동작한게 맞는지 확인
    queue.redis_client.rpush.assert_called_once()
    # rpsuh에 들어온 인자 확인
    args = queue.redis_client.rpush.call_args
    # 인자 첫 값은 queue_key(exercise:generation_queue) 고정
    assert args[0][0] == "exercise:generation_queue"
    # 두 번째값은 task를 문자열로 반환한거임
    assert "ALGORITHM" == json.loads(args[0][1])["subject"]
    assert "EASY" == json.loads(args[0][1])["level"]

def test_redis_queue_dequeue():
    # =========== given =========== 
    # Mock 객체 생성
    queue = RedisQueue()
    # blpop 실행 시, return 값 설정
    queue.redis_client.blpop.return_value = ("queue_key", '{"subject": "TEST", "level": "HARD"}')
    
    # =========== when =========== 
    # 넣은거 하나 빼기 
    result = queue.dequeue()
    
    # =========== then =========== 
    # 결과가 있어야 하고
    assert result is not None
    # 결과 dictionary 형태가 다음과 같아야 함
    assert result["subject"] == "TEST"
    assert result["level"] == "HARD"

    # deque에서 pop은 한 번만 호출되어야 함
    queue.redis_client.blpop.assert_called_once()

@pytest.mark.asyncio
async def test_automation_service_produce_job(mocker):
    # =========== given =========== 
    mocker.patch("random.choice", side_effect=["ALGORITHM", "EASY"])
    mock_enqueue = mocker.patch.object(automation_service.queue, "enqueue")

    # =========== when =========== 
    automation_service._produce_job()
    
    # =========== then =========== 
    mock_enqueue.assert_called_once()
    call_arg = mock_enqueue.call_args[0][0]
    assert call_arg["subject"] == "ALGORITHM"
    assert call_arg["level"] == "EASY"

@pytest.mark.asyncio
async def test_automation_service_consumer(mocker):
    # =========== given =========== 
    automation_service.is_running = True
    
    task_payload = {
        "subject": "DB", "level": "HARD", 
        "subject_display": "Database", "level_display": "Advanced"
    }
    
    
    # is_running이 켜져있으면 계속 돌아가기에 dequeue Mock 구현
    def side_effect_dequeue(*args, **kwargs):
        automation_service.is_running = False 
        return task_payload
    # object mock
    mocker.patch.object(automation_service.queue, "dequeue", side_effect=side_effect_dequeue)

    # Service Mock 제작
    mock_exercise_service = mocker.patch("app.services.automation_service.exercise_service")
    mock_exercise_service.generate_exercise.return_value = "Mocked content"
    mock_exercise_service.parse_content.return_value = {"question": "Q", "answer": "A"}

    # Repository Mock 제작
    mock_repo_cls = mocker.patch("app.repositories.exercise_repository.exercise_repository")
    mock_saved_exercise = MagicMock(id=123, question="Q")
    mock_repo_cls.save_exercise.return_value = mock_saved_exercise

    # =========== when =========== 
    await automation_service.start_consumer()
    
    # =========== then =========== 
    # LLM 호출에 전달하는 내용을 잘 전달했는지 확인
    mock_exercise_service.generate_exercise.assert_called_with("Database", "Advanced")
    # subject = DB, level = HARD니까 확인
    # 위에 service에서 question은 q answer는 a니까 Q A 를 전달받았는지 확인
    mock_repo_cls.save_exercise.assert_called_with("DB", "HARD", "Q", "A")
