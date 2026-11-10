from unittest.mock import MagicMock
import pytest
from app.models.exercise import Exercise
from app.services.exercise_service import exercise_service
from app.services.deduplication_service import DeduplicationService


def test_index_failure_prevents_generation_and_save(mocker):
    repository = mocker.patch("app.repositories.exercise_repository.exercise_repository")
    dedup = mocker.patch("app.services.deduplication_service.deduplication_service")
    dedup.synchronize.side_effect = RuntimeError("index unavailable")
    generate = mocker.patch.object(exercise_service, "generate_exercise")
    with pytest.raises(RuntimeError, match="index unavailable"):
        exercise_service.generate_and_save("ALGORITHM", "EASY")
    generate.assert_not_called()
    repository.save_exercise.assert_not_called()


def test_completed_task_returns_saved_problem_without_regeneration(mocker):
    repository = mocker.patch("app.repositories.exercise_repository.exercise_repository")
    saved = Exercise(id=1, subject="ALGORITHM", level="EASY", question="Question", answer="Answer")
    repository.find_task.return_value = saved
    generate = mocker.patch.object(exercise_service, "generate_exercise")
    assert exercise_service.generate_and_save("ALGORITHM", "EASY", "task-1") == saved
    generate.assert_not_called()
    repository.save_exercise.assert_not_called()


def test_index_rebuild_resumes_after_committed_batch(tmp_path, monkeypatch, mocker):
    monkeypatch.setenv("CHROMA_PERSIST_DIRECTORY", str(tmp_path))
    embeddings = mocker.patch("app.services.deduplication_service.OpenAIEmbeddings").return_value
    embeddings.embed_documents.return_value = [[1.0, 0.0]]
    embeddings.embed_query.return_value = [1.0, 0.0]
    repository = MagicMock()
    repository.find_after.side_effect = [[{"id": 7, "subject": "ALGORITHM", "level": "EASY", "question": "Question", "answer": "Answer"}], []]
    store = DeduplicationService()
    store.synchronize(repository, None)
    assert store.is_duplicate("ALGORITHM", "EASY", "Question")
    assert not store.is_duplicate("DATABASE", "EASY", "Question")
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
