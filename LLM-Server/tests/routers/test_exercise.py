from unittest.mock import MagicMock
from app.models.exercise import Exercise
import pytest
from concurrent.futures import ThreadPoolExecutor
from threading import Event

def test_health_responds_while_generation_waits(client, mocker):
    started, release = Event(), Event()
    def slow_generation(*args):
        started.set()
        release.wait(3)
        return Exercise(id=1, subject="ALGORITHM", level="EASY", question="Question", answer="Answer")
    mocker.patch("app.routers.exercise.exercise_service.generate_and_save", side_effect=slow_generation)
    repository = mocker.patch("app.repositories.exercise_repository.exercise_repository")
    repository.save_exercise.return_value = Exercise(id=1, subject="ALGORITHM", level="EASY", question="Question", answer="Answer")
    with ThreadPoolExecutor() as pool:
        request = pool.submit(client.post, "/exercise/generate", json={"subject": "ALGORITHM", "level": "EASY"})
        try:
            assert started.wait(2)
            assert client.get("/health").status_code == 200
            assert not request.done()
        finally:
            release.set()
        assert request.result().status_code == 200

@pytest.mark.parametrize("field,value", [("subject", "UNKNOWN"), ("level", "UNKNOWN"), ("subject", ""), ("level", "easy")])
def test_generate_rejects_invalid_category(client, mocker, field, value):
    service = mocker.patch("app.routers.exercise.exercise_service")
    payload = {"subject": "ALGORITHM", "level": "EASY", field: value}
    assert client.post("/exercise/generate", json=payload).status_code == 422
    service.generate_and_save.assert_not_called()

def test_generate_requires_credentials(client):
    client.headers.pop("Authorization")
    response = client.post("/exercise/generate", json={"subject": "ALGORITHM", "level": "EASY"})
    assert response.status_code == 401

def test_generate_disabled_without_key(client, monkeypatch):
    monkeypatch.delenv("LLM_API_KEY")
    response = client.post("/exercise/generate", json={"subject": "ALGORITHM", "level": "EASY"})
    assert response.status_code == 503

def test_generate_exercise(client, mocker):
    service = mocker.patch("app.routers.exercise.exercise_service")
    service.generate_and_save.return_value = Exercise(id=1, subject="ALGORITHM", level="EASY", question="What is 1+1?", answer="2")
    response = client.post("/exercise/generate", json={"subject": "ALGORITHM", "level": "EASY"})
    assert response.status_code == 200
    assert response.json()["question"] == "What is 1+1?"
    service.generate_and_save.assert_called_once_with("ALGORITHM", "EASY")
