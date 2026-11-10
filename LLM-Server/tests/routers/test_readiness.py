import time
from unittest.mock import MagicMock
import pytest
from app.main import app, monitor_generation, GENERATION_READY
from app.services.automation_service import automation_service


@pytest.mark.parametrize("condition", ["healthy", "database", "redis", "stopped", "stale", "warming", "backlog", "generation", "producer", "failed_storage"])
def test_readiness_reports_generation_and_dependency_failures(client, mocker, condition):
    mocker.patch("app.main.database_available", return_value=True)
    size = mocker.patch.object(automation_service.queue, "size", return_value=0)
    mocker.patch.object(automation_service.queue, "failed_size", return_value=0)
    mocker.patch.object(automation_service, "is_running", True)
    mocker.patch.object(automation_service, "last_success_at", time.time())
    mocker.patch.object(automation_service, "consecutive_failures", 0)
    mocker.patch.object(automation_service, "producer_failed", False)
    consumer = MagicMock()
    consumer.done.return_value = condition == "stopped"
    app.state.consumer_task = consumer
    if condition == "database":
        mocker.patch("app.main.database_available", side_effect=RuntimeError("private connection detail"))
    elif condition == "redis":
        size.side_effect = RuntimeError("private Redis detail")
    elif condition == "stale":
        automation_service.last_success_at = time.time() - 601
    elif condition == "warming":
        automation_service.last_success_at = None
    elif condition == "backlog":
        size.return_value = 21
    elif condition == "generation":
        automation_service.consecutive_failures = 1
    elif condition == "producer":
        automation_service.producer_failed = True
    elif condition == "failed_storage":
        mocker.patch.object(automation_service.queue, "failed_size", side_effect=RuntimeError("private storage detail"))
    response = client.get("/ready")
    assert response.status_code == (200 if condition == "healthy" else 503)
    assert "private" not in response.text
    assert client.get("/health").status_code == 200


def test_metrics_include_generation_state(client):
    response = client.get("/metrics")
    assert response.status_code == 200
    assert "dcba_generation_queue_depth" in response.text
    assert "dcba_generation_last_success_timestamp_seconds" in response.text


@pytest.mark.asyncio
async def test_monitor_refreshes_readiness_without_an_http_request(mocker):
    import asyncio
    mocker.patch("app.main.database_available", return_value=True)
    mocker.patch.object(automation_service.queue, "size", return_value=0)
    mocker.patch.object(automation_service.queue, "failed_size", return_value=3)
    mocker.patch.object(automation_service, "is_running", True)
    mocker.patch.object(automation_service, "last_success_at", time.time())
    mocker.patch.object(automation_service, "consecutive_failures", 0)
    mocker.patch.object(automation_service, "producer_failed", False)
    consumer = MagicMock()
    consumer.done.return_value = False
    mocker.patch.object(app.state, "consumer_task", consumer, create=True)
    GENERATION_READY.set(0)
    mocker.patch("app.main.asyncio.sleep", side_effect=asyncio.CancelledError)
    with pytest.raises(asyncio.CancelledError):
        await monitor_generation()
    assert GENERATION_READY.collect()[0].samples[0].value == 1
    assert automation_service.quarantined_tasks == 3


@pytest.mark.asyncio
async def test_monitor_clears_stale_readiness_after_unexpected_failure(mocker):
    import asyncio
    GENERATION_READY.set(1)
    mocker.patch("app.main.readiness", side_effect=RuntimeError("monitor failure"))
    mocker.patch("app.main.asyncio.sleep", side_effect=asyncio.CancelledError)
    with pytest.raises(asyncio.CancelledError):
        await monitor_generation()
    assert GENERATION_READY.collect()[0].samples[0].value == 0
