import json

import pytest

from app.services.exercise_service import exercise_service


QUESTION = "새 버전을 배포한 뒤 주문 API가 느려졌습니다.\n\n- **관측값** — 타임아웃이 늘었습니다.\n\n### 어디부터 조사하겠습니까?"
ANSWER = '##### 핵심 원리\n\n- **워커**가 "작업"을 실행합니다.\n- 경로는 \\로 시작합니다.'
CONCEPT = {"term": "워커", "explanation": "작업을 실행하는 스레드입니다."}
OUTPUT = json.dumps({"question": QUESTION, "answer": ANSWER, "concepts": [CONCEPT]})
FORMATTED_ANSWER = f"{ANSWER}\n\n##### 이 문제를 풀기 위해 알아야 할 개념\n\n- **워커** — 작업을 실행하는 스레드입니다."


def test_parser_preserves_markdown_and_escaped_characters():
    parsed = exercise_service.parse_content(OUTPUT)
    assert parsed.question == QUESTION
    assert parsed.answer == ANSWER
    assert parsed.concepts[0].model_dump() == CONCEPT


@pytest.mark.parametrize("changes", [
    {"question": 1}, {"answer": " \n"}, {"answer": []}, {"extra": "값"},
    {"concepts": None}, {"concepts": []}, {"concepts": [CONCEPT] * 5},
    {"concepts": [{"term": "워커"}]}, {"concepts": [dict(CONCEPT, extra="값")]},
])
def test_parser_rejects_schema_violations(changes):
    assert exercise_service.parse_content(json.dumps(json.loads(OUTPUT) | changes)) is None


@pytest.mark.parametrize("content", [
    "", "Q: 질문\nA: 답", "[]", OUTPUT[:-1], OUTPUT + "{}", f"```json\n{OUTPUT}\n```",
    '{"answer": "첫 답변", ' + OUTPUT[1:], OUTPUT[:-1] + ',}',
    OUTPUT.replace('"term": ', '"term": "중복", "term": '),
])
def test_parser_rejects_non_json_or_ambiguous_keys(content):
    assert exercise_service.parse_content(content) is None


@pytest.mark.parametrize("line_index", [0, 1, 2])
def test_prompt_examples_match_output_schema(line_index):
    prompt = exercise_service.prompt_template.format(subject="운영체제", level="난이도 1 (기초)")
    examples = [line for line in prompt.splitlines() if line.startswith("{")]
    assert exercise_service.parse_content(examples[line_index]) is not None


def test_invalid_json_is_retried_before_saving_valid_markdown(mocker):
    mocker.patch.object(exercise_service, "deduplication_enabled", False)
    generator = mocker.patch.object(exercise_service.client, "generate", side_effect=[OUTPUT[:-1], OUTPUT])
    repository = mocker.patch("app.repositories.exercise_repository.exercise_repository")
    exercise_service.generate_and_save("OPERATING_SYSTEM", "EASY")
    assert generator.call_count == 2
    repository.save_exercise.assert_called_once()
    assert repository.save_exercise.call_args.args == ("OPERATING_SYSTEM", "EASY", QUESTION, FORMATTED_ANSWER)


def test_disabled_dedup_does_not_access_embedding_service(mocker):
    mocker.patch.object(exercise_service, "deduplication_enabled", False)
    generator = mocker.patch.object(exercise_service.client, "generate", return_value=OUTPUT)
    dedup = mocker.patch("app.services.deduplication_service.deduplication_service")
    assert exercise_service.generate_exercise("ALGORITHM", "EASY") == (QUESTION, FORMATTED_ANSWER, None)
    generator.assert_called_once()
    dedup.check_duplicate.assert_not_called()


def test_invalid_output_has_bounded_retry(mocker):
    mocker.patch.object(exercise_service, "deduplication_enabled", False)
    generator = mocker.patch.object(exercise_service.client, "generate", return_value="invalid")
    repository = mocker.patch("app.repositories.exercise_repository.exercise_repository")
    with pytest.raises(ValueError, match="valid, unique content"):
        exercise_service.generate_and_save("ALGORITHM", "EASY")
    assert generator.call_count == 10
    repository.save_exercise.assert_not_called()


def test_model_failure_is_not_retried_within_request(mocker):
    generator = mocker.patch.object(exercise_service.client, "generate", side_effect=RuntimeError("provider unavailable"))
    with pytest.raises(RuntimeError, match="provider unavailable"):
        exercise_service.generate_exercise("ALGORITHM", "EASY")
    generator.assert_called_once()
