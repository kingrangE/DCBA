import pytest
from app.services.exercise_service import exercise_service

@pytest.mark.parametrize("content,question,answer", [
    ('Q: "인용" Q: 본문\nA: "답변" A: 본문', '"인용" Q: 본문', '"답변" A: 본문'),
    ('  Q: 첫 줄\n둘째 줄\n\nA: 설명\n\n다음 문단  ', '첫 줄\n둘째 줄', '설명\n\n다음 문단'),
    ('Q: 내용 A: 답', '', ''), ('Q: 내용\nA: ', '', ''), ('', '', ''),
])
def test_parser_preserves_body(content, question, answer):
    assert exercise_service.parse_content(content) == {"question":question,"answer":answer}

def test_disabled_dedup_does_not_access_embedding_service(mocker):
    mocker.patch.object(exercise_service, "deduplication_enabled", False)
    generator=mocker.patch.object(exercise_service.client, "generate", return_value="Q: 질문\nA: 답")
    dedup=mocker.patch("app.services.deduplication_service.deduplication_service")
    assert exercise_service.generate_exercise("ALGORITHM", "EASY") == ("Q: 질문\nA: 답", None)
    generator.assert_called_once()
    dedup.check_duplicate.assert_not_called()

def test_invalid_output_has_bounded_retry(mocker):
    generator=mocker.patch.object(exercise_service.client, "generate", return_value="invalid")
    with pytest.raises(RuntimeError): exercise_service.generate_exercise("ALGORITHM", "EASY")
    assert generator.call_count == 10

def test_model_failure_is_not_retried_within_request(mocker):
    generator=mocker.patch.object(exercise_service.client, "generate", side_effect=RuntimeError("missing weights"))
    with pytest.raises(RuntimeError, match="missing weights"): exercise_service.generate_exercise("ALGORITHM", "EASY")
    generator.assert_called_once()
