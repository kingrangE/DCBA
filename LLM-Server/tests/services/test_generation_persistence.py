import json
from unittest.mock import MagicMock, patch
import pytest
from app.models.exercise import Exercise, QueuedExerciseRequest
from app.repositories.exercise_repository import ExerciseRepository
from app.services.exercise_service import exercise_service
from app.services.deduplication_service import DeduplicationService

MODEL_OUTPUT = '{"question":"Question","answer":"Answer","concepts":[{"term":"Term","explanation":"Explanation"}]}'
STORED_ANSWER = "Answer\n\n##### 이 문제를 풀기 위해 알아야 할 개념\n\n- **Term** — Explanation"


def test_duplicate_check_failure_stops_without_repeated_paid_generation(mocker):
    completion = mocker.patch.object(exercise_service.client, "generate")
    completion.return_value = MODEL_OUTPUT
    dedup = mocker.patch("app.services.deduplication_service.deduplication_service")
    dedup.check_duplicate.side_effect = RuntimeError("embedding unavailable")
    with pytest.raises(RuntimeError, match="embedding unavailable"):
        exercise_service.generate_exercise("ALGORITHM", "EASY")
    assert completion.call_count == 1


def test_duplicate_retries_use_recent_conflicts_only_for_the_current_request(mocker):
    rejected = [f"중복 후보 {number}" for number in range(4)]
    accepted_embedding = [0.0, 1.0]
    completion = mocker.patch.object(exercise_service.client, "generate", side_effect=[
        *(json.dumps(json.loads(MODEL_OUTPUT) | {"question": question}) for question in rejected),
        "invalid output",
        MODEL_OUTPUT,
        MODEL_OUTPUT,
    ])
    dedup = mocker.patch("app.services.deduplication_service.deduplication_service")
    dedup.check_duplicate.side_effect = [
        *((f"기존 문항 {number} " + "x" * 500, [1.0, 0.0]) for number in range(4)),
        (None, accepted_embedding),
        (None, accepted_embedding),
    ]

    assert exercise_service.generate_exercise("ALGORITHM", "EASY") == ("Question", STORED_ANSWER, accepted_embedding)
    assert exercise_service.generate_exercise("ALGORITHM", "EASY") == ("Question", STORED_ANSWER, accepted_embedding)

    prompts = [call.kwargs["messages"][1]["content"] for call in completion.call_args_list]
    assert "# 재생성 사유" not in prompts[0]
    first_retry = json.loads(prompts[1].splitlines()[-1])
    assert first_retry == [{
        "rejected_question": rejected[0],
        "existing_exercise_excerpt": ("기존 문항 0 " + "x" * 500)[:500],
    }]
    history = json.loads(prompts[4].splitlines()[-1])
    assert [item["rejected_question"] for item in history] == rejected[1:]
    assert prompts[5] == prompts[4]
    assert prompts[6] == prompts[0]
    assert dedup.check_duplicate.call_count == 6


def test_index_failure_prevents_generation_and_save(mocker):
    repository = mocker.patch("app.repositories.exercise_repository.exercise_repository")
    dedup = mocker.patch("app.services.deduplication_service.deduplication_service")
    dedup.synchronize.side_effect = RuntimeError("index unavailable")
    generate = mocker.patch.object(exercise_service, "generate_exercise")
    with pytest.raises(RuntimeError, match="index unavailable"):
        exercise_service.generate_and_save("ALGORITHM", "EASY")
    generate.assert_not_called()
    repository.save_exercise.assert_not_called()


@pytest.mark.parametrize("deleted", [False, True])
def test_completed_task_does_not_regenerate_even_after_deletion(mocker, deleted):
    repository = mocker.patch("app.repositories.exercise_repository.exercise_repository")
    saved = Exercise(id=1, subject="ALGORITHM", level="EASY", question="Question", answer="Answer")
    repository.find_task.return_value = {"id": None} if deleted else saved.model_dump(exclude_none=True)
    generate = mocker.patch.object(exercise_service, "generate_exercise")
    assert exercise_service.generate_and_save("ALGORITHM", "EASY", "task-1") == (None if deleted else saved)
    generate.assert_not_called()
    repository.save_exercise.assert_not_called()


def test_index_rebuild_resumes_after_committed_batch(tmp_path, monkeypatch, mocker):
    monkeypatch.setenv("CHROMA_PERSIST_DIRECTORY", str(tmp_path))
    embeddings = mocker.patch("app.services.deduplication_service.OpenAIEmbeddings").return_value
    embeddings.embed_documents.return_value = [[1.0, 0.0]]
    embeddings.embed_query.return_value = [1.0, 0.0]
    repository = MagicMock()
    repository.find_deleted_ids.return_value = []
    repository.find_after.side_effect = [[{"id": 7, "subject": "ALGORITHM", "level": "EASY", "question": "Question", "answer": "Answer"}], []]
    store = DeduplicationService()
    store.synchronize(repository, None)
    assert store.check_duplicate("ALGORITHM", "EASY", "Question") == ("Q: Question\nA: Answer", [1.0, 0.0])
    assert store.check_duplicate("DATABASE", "EASY", "Question") == (None, [1.0, 0.0])
    reopened = DeduplicationService()
    repository.find_after.side_effect = [[]]
    reopened.synchronize(repository, None)
    repository.find_after.assert_called_with(7, None)
    assert reopened.collection.count() == 1


def test_failed_embedding_does_not_advance_rebuild_cursor(mocker):
    store = DeduplicationService.__new__(DeduplicationService)
    store.client = MagicMock()
    store.collection_name = "test"
    store.client.get_collection.return_value.metadata = None
    store.embeddings = MagicMock()
    store.embeddings.embed_documents.side_effect = RuntimeError("embedding unavailable")
    repository = MagicMock()
    repository.find_after.return_value = [{"id": 1, "subject": "ALGORITHM", "level": "EASY", "question": "Question", "answer": "Answer"}]
    with pytest.raises(RuntimeError):
        store.synchronize(repository, None)
    store.client.get_collection.return_value.modify.assert_not_called()


def test_accepted_embedding_is_recorded_once_and_not_embedded_again(tmp_path, monkeypatch, mocker):
    monkeypatch.setenv("CHROMA_PERSIST_DIRECTORY", str(tmp_path))
    embeddings = mocker.patch("app.services.deduplication_service.OpenAIEmbeddings").return_value
    embeddings.embed_query.return_value = [1.0, 0.0]
    store = DeduplicationService()
    mocker.patch("app.services.deduplication_service.deduplication_service", store)
    repository = mocker.patch("app.repositories.exercise_repository.exercise_repository")
    repository.find_after.return_value = []
    repository.find_deleted_ids.return_value = []
    saved = Exercise(id=11, subject="ALGORITHM", level="EASY", question="Question", answer=STORED_ANSWER)
    repository.save_exercise.return_value = saved
    completion = mocker.patch.object(exercise_service.client, "generate")
    completion.return_value = MODEL_OUTPUT
    assert exercise_service.generate_and_save("ALGORITHM", "EASY") == saved
    store.synchronize(repository, None)
    embeddings.embed_query.assert_called_once_with(f"Q: Question\nA: {STORED_ANSWER}")
    embeddings.embed_documents.assert_not_called()
    assert store.collection.metadata["last_exercise_id"] == 11
    assert list(store.collection.get(ids=["11"], include=["embeddings"])["embeddings"][0]) == [1.0, 0.0]


def test_database_failure_never_records_candidate_embedding(mocker):
    repository = mocker.patch("app.repositories.exercise_repository.exercise_repository")
    repository.save_exercise.side_effect = RuntimeError("database failed")
    dedup = mocker.patch("app.services.deduplication_service.deduplication_service")
    mocker.patch.object(exercise_service, "generate_exercise", return_value=("Question", STORED_ANSWER, [1.0, 0.0]))
    with pytest.raises(RuntimeError, match="database failed"):
        exercise_service.generate_and_save("ALGORITHM", "EASY")
    dedup.record.assert_not_called()


def test_checkpoint_failure_recovers_committed_problem_from_database(tmp_path, monkeypatch, mocker):
    monkeypatch.setenv("CHROMA_PERSIST_DIRECTORY", str(tmp_path))
    embeddings = mocker.patch("app.services.deduplication_service.OpenAIEmbeddings").return_value
    embeddings.embed_query.return_value = [1.0, 0.0]
    embeddings.embed_documents.return_value = [[1.0, 0.0]]
    store = DeduplicationService()
    mocker.patch("app.services.deduplication_service.deduplication_service", store)
    repository = mocker.patch("app.repositories.exercise_repository.exercise_repository")
    repository.find_after.return_value = []
    repository.find_deleted_ids.return_value = []
    saved = Exercise(id=12, subject="ALGORITHM", level="EASY", question="Question", answer=STORED_ANSWER)
    repository.save_exercise.return_value = saved
    mocker.patch.object(exercise_service, "generate_exercise", return_value=("Question", STORED_ANSWER, [1.0, 0.0]))
    # synchronize reopens the collection, so fail the checkpoint at the collection class boundary.
    with patch.object(type(store.collection), "modify", side_effect=RuntimeError("checkpoint failed")):
        with pytest.raises(RuntimeError, match="checkpoint failed"):
            exercise_service.generate_and_save("ALGORITHM", "EASY")
    repository.save_exercise.assert_called_once()
    assert not (store.collection.metadata or {}).get("last_exercise_id")
    repository.find_after.side_effect = [[saved.model_dump()], []]
    store.synchronize(repository, None)
    assert store.collection.metadata["last_exercise_id"] == 12
    assert store.collection.count() == 1


def test_deleted_problem_is_removed_from_index_before_cleanup_is_acknowledged(tmp_path, monkeypatch, mocker):
    monkeypatch.setenv("CHROMA_PERSIST_DIRECTORY", str(tmp_path))
    mocker.patch("app.services.deduplication_service.OpenAIEmbeddings")
    store = DeduplicationService()
    saved = Exercise(id=9, subject="ALGORITHM", level="EASY", question="Example", answer="Example")
    store.record(saved, [1.0, 0.0])
    repository = MagicMock()
    repository.find_after.return_value = []
    repository.find_deleted_ids.side_effect = [[9, 10], []]
    store.synchronize(repository, None)
    assert store.collection.count() == 0
    repository.clear_deleted_ids.assert_called_once_with([9, 10], None)

    repository.find_deleted_ids.side_effect = None
    repository.find_deleted_ids.return_value = [9]
    repository.clear_deleted_ids.reset_mock()
    with patch.object(type(store.collection), "delete", side_effect=RuntimeError("index unavailable")):
        with pytest.raises(RuntimeError, match="index unavailable"):
            store.synchronize(repository, None)
    repository.clear_deleted_ids.assert_not_called()

@pytest.fixture(autouse=True)
def enable_deduplication(monkeypatch):
    monkeypatch.setattr(exercise_service, "deduplication_enabled", True)


def test_queued_redelivery_does_not_call_the_model_or_save_again(mocker):
    repository = mocker.patch("app.repositories.exercise_repository.exercise_repository")
    saved = Exercise(id=1, subject="DATABASE", level="HARD", question="문제", answer="해설")
    repository.find_task.return_value = saved.model_dump()
    reserve = MagicMock()
    request = QueuedExerciseRequest(subject="DATABASE", level="HARD", request_type="manual", requester_id=1)
    assert exercise_service.run_queued("reclaimed", request, reserve) == saved
    reserve.assert_not_called()
    repository.save_exercise.assert_not_called()


@pytest.mark.parametrize("saved_relation_fails", [False, True])
def test_manual_completion_commits_all_rows_or_rolls_back_together(saved_relation_fails):
    repository = ExerciseRepository()
    connection = MagicMock()
    cursor = connection.cursor.return_value.__enter__.return_value
    cursor.lastrowid = 7
    if saved_relation_fails:
        cursor.execute.side_effect = [None, None, None, RuntimeError("save failed")]
    args = {"task_id": "request", "connection": connection, "history": ("개념", "목표", "원인 진단", "1"),
            "requester_id": 3}
    if saved_relation_fails:
        with pytest.raises(RuntimeError, match="save failed"):
            repository.save_exercise("DATABASE", "HARD", "문제", "해설", **args)
        connection.rollback.assert_called_once()
        connection.commit.assert_not_called()
    else:
        assert repository.save_exercise("DATABASE", "HARD", "문제", "해설", **args).id == 7
        connection.commit.assert_called_once()
    assert [call.args[0].split()[2] for call in cursor.execute.call_args_list] == [
        "exercise", "generation_task", "exercise_generation_history", "selected_exercise"
    ]
