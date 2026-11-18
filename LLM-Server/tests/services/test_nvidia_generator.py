import json
from contextlib import contextmanager

import httpx
import pytest
from openai import APIStatusError, APITimeoutError, OpenAI

from app.services.automation_service import RedisQueue
from app.services.nvidia_generator import NvidiaGenerator


MESSAGES = [{"role": "user", "content": "한국어 CS 문제를 작성해 주세요."}]


@pytest.fixture
def setup_generator(monkeypatch, mocker):
    monkeypatch.setenv("NVIDIA_API_KEY", "test-nvidia-key")
    monkeypatch.setenv("NVIDIA_MODEL", "test/model")
    monkeypatch.delenv("NVIDIA_BASE_URL", raising=False)

    @contextmanager
    def setup(handler):
        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            mocker.patch("app.services.nvidia_generator.OpenAI", side_effect=lambda **kwargs: OpenAI(
                http_client=client, **kwargs,
            ))
            generator = NvidiaGenerator()
            generator.configure()
            yield generator

    return setup


@pytest.mark.parametrize("endpoint", [None, "https://nim.example/v1"])
def test_generator_sends_authenticated_chat_completion(monkeypatch, setup_generator, endpoint):
    if endpoint:
        monkeypatch.setenv("NVIDIA_BASE_URL", endpoint)

    def respond(request):
        assert str(request.url) == f"{endpoint or 'https://integrate.api.nvidia.com/v1'}/chat/completions"
        assert request.headers["Authorization"] == "Bearer test-nvidia-key"
        payload = json.loads(request.content)
        assert payload["model"] == "test/model"
        assert payload["messages"] == MESSAGES
        assert request.extensions["timeout"]["read"] == 30.0
        return httpx.Response(200, json={"choices": [
            {"finish_reason": "stop", "message": {"role": "assistant", "content": " Q: 질문\nA: 답 "}},
        ]})

    with setup_generator(respond) as generator:
        assert generator.generate(MESSAGES) == "Q: 질문\nA: 답"


@pytest.mark.parametrize("choices", [[], [{"finish_reason": "stop"}], [
    {"finish_reason": "length", "message": {"role": "assistant", "content": "Q: 질문\nA: 미완"}},
]])
def test_generator_discards_empty_and_incomplete_output(setup_generator, choices):
    with setup_generator(lambda _: httpx.Response(200, json={"choices": choices})) as generator:
        assert generator.generate(MESSAGES) == ""


@pytest.mark.parametrize("status,quarantine", [(None, False), (429, False), (503, False), (401, True), (404, True)])
def test_generator_leaves_api_retries_to_queue(setup_generator, status, quarantine):
    calls = []

    def respond(request):
        calls.append(request)
        if status is None:
            raise httpx.ReadTimeout("timed out", request=request)
        return httpx.Response(status, json={"error": {"message": "provider error"}})

    with setup_generator(respond) as generator:
        with pytest.raises(APIStatusError if status else APITimeoutError) as failure:
            generator.generate(MESSAGES)

    assert len(calls) == 1
    queue = RedisQueue()
    task = {"_message_id": "1-0", "_attempt": 1, "_payload": "{}"}
    queue.failed(task, failure.value)
    assert queue.redis_client.eval.called == quarantine


@pytest.mark.parametrize("key,model,url,expected", [
    ("", "test/model", "", "are required"),
    ("test-key", "", "", "are required"),
    ("test-key", "test/model", "http://nim.example/v1", "HTTPS URL"),
    ("test-key", "test/model", "https://user:secret@nim.example/v1", "HTTPS URL"),
])
def test_invalid_configuration_fails_before_processing(monkeypatch, key, model, url, expected):
    monkeypatch.setenv("NVIDIA_API_KEY", key)
    monkeypatch.setenv("NVIDIA_MODEL", model)
    monkeypatch.setenv("NVIDIA_BASE_URL", url)
    with pytest.raises(ValueError, match=expected):
        NvidiaGenerator().configure()
