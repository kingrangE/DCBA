import json
import pytest
from unittest.mock import MagicMock
from app.services.automation_service import automation_service, RedisQueue


def test_enqueue_is_bounded_and_gives_each_task_an_identity():
    queue = RedisQueue()
    queue.redis_client.hincrby.return_value = 1
    queue.redis_client.eval.return_value = "1-0"
    queue.enqueue({"subject": "ALGORITHM", "level": "EASY"})
    payload = json.loads(queue.redis_client.eval.call_args.args[-1])
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
    mocker.patch("random.choice", side_effect=["ALGORITHM", "EASY"])
    enqueue = mocker.patch.object(automation_service.queue, "enqueue")
    automation_service._produce_job()
    enqueue.assert_called_once_with({"subject": "ALGORITHM", "level": "EASY"})


@pytest.mark.asyncio
@pytest.mark.parametrize("fails", [False, True])
async def test_consumer_acknowledges_only_committed_tasks(mocker, fails):
    task = {"subject": "DATABASE", "level": "HARD", "task_id": "task-1", "_message_id": "1-0", "_attempt": 1, "_payload": "{}"}
    def dequeue(*args):
        automation_service.is_running = False
        return task
    mocker.patch.object(automation_service.queue, "dequeue", side_effect=dequeue)
    acknowledge = mocker.patch.object(automation_service.queue, "acknowledge")
    service = mocker.patch("app.services.automation_service.exercise_service")
    if fails:
        service.generate_and_save.side_effect = RuntimeError("database unavailable")
    else:
        service.generate_and_save.return_value = MagicMock(id=123)
    await automation_service.start_consumer()
    service.generate_and_save.assert_called_once_with("DATABASE", "HARD", "task-1")
    if fails:
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
