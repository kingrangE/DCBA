import json
import pytest
from unittest.mock import MagicMock
from app.models.exercise import Level
from app.services.automation_service import AutomationService, automation_service, RedisQueue


def test_enqueue_is_bounded_and_gives_each_task_an_identity():
    queue = RedisQueue()
    queue.redis_client.hincrby.return_value = 1
    queue.redis_client.eval.return_value = "1-0"
    queue.enqueue({"subject": "ALGORITHM", "level": "EASY"})
    payload = json.loads(queue.redis_client.eval.call_args.args[-2])
    assert payload["subject"] == "ALGORITHM" and payload["task_id"]
    queue.redis_client.eval.return_value = None
    with pytest.raises(RuntimeError, match="full"):
        queue.enqueue({"subject": "ALGORITHM", "level": "EASY"})


def test_unfinished_task_is_reclaimed_without_removal():
    queue = RedisQueue()
    queue.redis_client.hincrby.return_value = 1
    queue.redis_client.xautoclaim.return_value = ["0-0", [("1-0", {"payload": json.dumps({"subject": "ALGORITHM", "level": "EASY", "task_id": "original"})})], []]
    task = queue.dequeue()
    assert task["task_id"] == "original" and task["_message_id"] == "1-0"
    queue.redis_client.xreadgroup.assert_not_called()
    queue.redis_client.xdel.assert_not_called()
    queue.acknowledge(task)
    transaction = queue.redis_client.pipeline.return_value.__enter__.return_value
    transaction.xack.assert_called_once_with(queue.queue_key, queue.group, "1-0")
    transaction.xdel.assert_called_once_with(queue.queue_key, "1-0")
    transaction.execute.assert_called_once()


def test_dequeue_reads_new_work_and_preserves_legacy_identity():
    queue = RedisQueue()
    queue.redis_client.hincrby.return_value = 1
    queue.redis_client.xautoclaim.return_value = ["0-0", [], []]
    queue.redis_client.xreadgroup.return_value = [(queue.queue_key, [("2-0", {"payload": '{"subject":"DATABASE","level":"HARD"}'})])]
    assert queue.dequeue()["task_id"] == "legacy:2-0"


def test_produce_job(mocker):
    mocker.patch("random.choice", return_value="ALGORITHM")
    choose_level = mocker.patch("random.choices", return_value=[Level.MEDIUM])
    enqueue = mocker.patch.object(automation_service.queue, "enqueue")
    automation_service._produce_job()
    choose_level.assert_called_once_with((Level.EASY, Level.MEDIUM, Level.HARD), weights=(1, 4, 2))
    enqueue.assert_called_once_with({"subject": "ALGORITHM", "level": "MEDIUM"}, only_when_idle=True)


def test_automatic_generation_does_not_enqueue_while_work_is_pending():
    queue = RedisQueue()
    queue.redis_client.eval.return_value = -1
    accepted = queue.enqueue({"subject": "ALGORITHM", "level": "EASY"}, only_when_idle=True)
    assert accepted is False
    assert queue.redis_client.eval.call_args.args[-1] == "1"


def test_pause_key_is_checked_in_the_automatic_enqueue_script():
    queue = RedisQueue()
    queue.redis_client.eval.return_value = -1
    task = {"subject": "ALGORITHM", "level": "EASY"}
    assert queue.enqueue(task, only_when_idle=True) is False
    script, key_count, _, _, pause_key, _, automatic = queue.redis_client.eval.call_args.args
    assert key_count == 3 and pause_key == "exercise:auto_generation_paused" and automatic == "1"
    assert script.index("EXISTS") < script.index("XADD")
    queue.redis_client.eval.return_value = "1-0"
    assert queue.enqueue(task) is True
    assert queue.redis_client.eval.call_args.args[-1] == "0"


def test_automatic_generation_uses_configured_interval(monkeypatch, mocker):
    monkeypatch.setenv("AUTO_GENERATION_ENABLED", "true")
    monkeypatch.setenv("AUTO_GENERATION_INTERVAL_SECONDS", "600")
    service = AutomationService()
    scheduler = mocker.patch.object(service, "scheduler")
    scheduler.running = False
    service.start_producer()
    scheduler.add_job.assert_called_once_with(service._produce_job, "interval", seconds=600)
    scheduler.start.assert_called_once()


def test_on_demand_mode_does_not_schedule_automatic_generation(mocker):
    mocker.patch.object(automation_service, "auto_generation_enabled", False)
    scheduler = mocker.patch.object(automation_service, "scheduler")
    automation_service.start_producer()
    scheduler.add_job.assert_not_called()
    scheduler.start.assert_not_called()


@pytest.mark.asyncio
@pytest.mark.parametrize("outcome", ["saved", "deleted", "failed"])
async def test_consumer_acknowledges_only_committed_tasks(mocker, outcome):
    task = {"subject": "DATABASE", "level": "HARD", "task_id": "task-1", "_message_id": "1-0", "_attempt": 1, "_payload": "{}"}
    def dequeue(*args):
        automation_service.is_running = False
        return task
    mocker.patch.object(automation_service.queue, "dequeue", side_effect=dequeue)
    acknowledge = mocker.patch.object(automation_service.queue, "acknowledge")
    service = mocker.patch("app.services.automation_service.exercise_service")
    if outcome == "failed":
        service.generate_and_save.side_effect = RuntimeError("database unavailable")
    else:
        service.generate_and_save.return_value = None if outcome == "deleted" else MagicMock(id=123)
    await automation_service.start_consumer()
    service.generate_and_save.assert_called_once_with("DATABASE", "HARD", "task-1")
    if outcome == "failed":
        acknowledge.assert_not_called()
    else:
        acknowledge.assert_called_once_with(task)


def test_failed_tasks_are_preserved_after_retry_limit():
    queue = RedisQueue()
    task = {"_message_id": "1-0", "_attempt": 4, "_payload": '{"subject":"DATABASE","level":"HARD"}'}
    queue.failed(task, RuntimeError("temporary"))
    queue.redis_client.eval.assert_not_called()
    task["_attempt"] = 5
    queue.failed(task, RuntimeError("permanent"))
    args = queue.redis_client.eval.call_args.args
    assert queue.failed_key in args and task["_payload"] in args
    assert args[0].index("XADD") < args[0].index("XACK") < args[0].index("XDEL")


@pytest.mark.parametrize("payload", ['not json', '[]', '{"subject":"INVALID","level":"EASY"}', '{"subject":"DATABASE","level":"EASY","task_id":42}'])
def test_invalid_payload_is_quarantined_without_stopping_consumer(payload):
    queue = RedisQueue()
    queue.redis_client.hincrby.return_value = 1
    queue.redis_client.xautoclaim.return_value = ["0-0", [("1-0", {"payload": payload})], []]
    assert queue.dequeue() is None
    assert queue.redis_client.eval.call_args.args[-3] == payload


def test_interrupted_tasks_stop_after_five_processing_attempts():
    queue = RedisQueue()
    queue.redis_client.hincrby.return_value = 6
    queue.redis_client.xautoclaim.return_value = ["0-0", [("1-0", {"payload": '{"subject":"DATABASE","level":"EASY"}'})], []]
    assert queue.dequeue() is None
    assert queue.redis_client.eval.call_args.args[-2] == "Retry limit reached after interrupted processing"


@pytest.mark.parametrize("status,quarantined", [(401, True), (429, False), (503, False)])
def test_permanent_api_errors_do_not_cycle_through_queue(status, quarantined):
    import httpx
    from openai import APIStatusError
    queue = RedisQueue()
    task = {"_message_id": "1-0", "_attempt": 1, "_payload": "{}"}
    response = httpx.Response(status, request=httpx.Request("POST", "http://example.invalid"))
    queue.failed(task, APIStatusError("failed", response=response, body=None))
    assert queue.redis_client.eval.called == quarantined
