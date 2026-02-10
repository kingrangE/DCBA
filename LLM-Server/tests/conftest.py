import sys
import pytest
import asyncio
from unittest.mock import MagicMock, AsyncMock

mock_redis_module = MagicMock()
mock_redis_module.Redis.side_effect = lambda *args, **kwargs: MagicMock() # Redis 생성마다 새로운 Mock를 반환
sys.modules["redis"] = mock_redis_module # import에서 만들어둔 Mock를 사용하도록 설정

mock_pymysql = MagicMock()
sys.modules["pymysql"] = mock_pymysql

from app.main import app
from fastapi.testclient import TestClient

@pytest.fixture
def client():
    with pytest.MonkeyPatch.context() as m:
        # 테스트 전, producer와 consumer 기능을 대체 (with에서만 가짜가 적용)
        m.setattr("app.services.automation_service.automation_service.start_producer", MagicMock())
        m.setattr("app.services.automation_service.automation_service.start_consumer", AsyncMock()) # 비동기 함수에 사용할 Mock
        
        # 서버가 꺼지면 곧바로 
        with TestClient(app) as test_client:
            yield test_client

@pytest.fixture
def mock_exercise_service(mocker):
    # 테스트가 끝나야 가짜가 사라짐
    return mocker.patch("app.services.exercise_service.exercise_service") 

@pytest.fixture
def mock_exercise_repository(mocker):
    return mocker.patch("app.repositories.exercise_repository.exercise_repository")
