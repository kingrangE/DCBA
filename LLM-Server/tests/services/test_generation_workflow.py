import json
from unittest.mock import Mock

import pytest

from app.models.exercise import QueuedExerciseRequest
from app.services.exercise_service import ExerciseService
from app.services.generation_workflow import GenerationRejected, generate, summarize_history


PLAN = json.dumps({"decision": "plan", "plan": {
    "concept": "팬텀 리드", "objective": "반복 조회의 결과 차이를 판단한다.",
    "question_type": "원인 진단", "scenario": "두 트랜잭션의 반복 조회",
    "clues": ["두 번째 트랜잭션이 조회 조건에 맞는 행을 추가했다."],
    "reasoning": "두 조회 사이의 커밋 순서를 비교한다.",
}})
WRITTEN = json.dumps({"question": "두 번째 조회에 새 행이 나타난 이유를 설명하세요.",
                      "answer": "격리 수준과 두 트랜잭션의 커밋 순서를 확인합니다.",
                      "concepts": [{"term": "격리 수준", "explanation": "동시 실행의 관측 범위를 정합니다."}]})
PASS = '{"decision":"pass","reason":"의도와 단서를 충족"}'


def run(responses, *, kind="manual", intent=None, duplicate_results=None):
    service = ExerciseService()
    service.client = Mock()
    service.client.generate.side_effect = responses
    repo = Mock()
    repo.recent_history.return_value = [
        {"concept": str(i), "objective": str(i), "question_type": "원인 진단"} for i in range(20)
    ]
    dedup = Mock()
    dedup.check_duplicate.side_effect = duplicate_results or [(None, [])] * 3
    request = QueuedExerciseRequest(subject="DATABASE", level="HARD", request_type=kind,
                                    requester_id=1 if kind == "manual" else None, learning_intent=intent)
    reservations = []

    def budget(stage=None):
        if stage is not None:
            reservations.append(stage)
        if reservations.count("model") > 6:
            raise GenerationRejected("generation_limit")
        return 30

    return service, repo, request, dedup, budget, reservations


@pytest.mark.parametrize(("kind", "intent"), [("manual", None), ("manual", "팬텀 리드"), ("automatic", None), ("legacy", None)])
def test_each_queue_source_runs_the_same_roles_and_bounded_history(kind, intent):
    service, repo, request, dedup, budget, reservations = run([PLAN, WRITTEN, PASS], kind=kind, intent=intent)
    assert generate(service, request, repo, None, dedup, budget)[3] == (
        "팬텀 리드", "반복 조회의 결과 차이를 판단한다.", "원인 진단", "1"
    )
    repo.recent_history.assert_called_once_with("DATABASE", "HARD", None)
    assert reservations == ["model", "model", "embedding", "model"]
    plan_input, writing_input, review_input = [
        json.loads(call.kwargs["messages"][1]["content"]) for call in service.client.generate.call_args_list
    ]
    assert plan_input["intent"] == intent and "intent" not in review_input
    assert len(plan_input["history"]["recent_goals"]) == 5
    assert set(writing_input) == {"plan", "feedback"}


def test_duplicate_skips_review_and_replans_with_bounded_excerpt():
    service, repo, request, dedup, budget, _ = run(
        [PLAN, WRITTEN, PLAN, WRITTEN, PASS], duplicate_results=[("중" * 700, []), (None, [])])
    generate(service, request, repo, None, dedup, budget)
    calls = service.client.generate.call_args_list
    second_plan = json.loads(calls[2].kwargs["messages"][1]["content"])
    assert len(calls) == 5 and len(second_plan["duplicates"][0]["existing_exercise_excerpt"]) == 500
    assert second_plan["history"] == json.loads(calls[0].kwargs["messages"][1]["content"])["history"]
    repo.recent_history.assert_called_once()


def test_revision_keeps_the_plan_and_rechecks_duplicate_and_review():
    service, repo, request, dedup, budget, _ = run(
        [PLAN, WRITTEN, '{"decision":"revise","reason":"시간 순서를 보완"}', WRITTEN, PASS])
    generate(service, request, repo, None, dedup, budget)
    calls = service.client.generate.call_args_list
    first, revised = [json.loads(calls[index].kwargs["messages"][1]["content"]) for index in (1, 3)]
    assert first["plan"] == revised["plan"] and revised["feedback"] == "시간 순서를 보완"
    assert len(calls) == 5 and dedup.check_duplicate.call_count == 2


def test_out_of_scope_and_exhaustion_do_not_continue_generating():
    service, repo, request, dedup, budget, _ = run(['{"decision":"out_of_scope"}'], intent="CPU 캐시")
    with pytest.raises(GenerationRejected, match="out_of_scope"):
        generate(service, request, repo, None, dedup, budget)
    service.client.generate.assert_called_once()
    dedup.check_duplicate.assert_not_called()
    service, repo, request, dedup, budget, _ = run(["not json"] * 6)
    with pytest.raises(GenerationRejected, match="generation_limit"):
        generate(service, request, repo, None, dedup, budget)
    assert service.client.generate.call_count == 6


def test_intent_uses_browser_whitespace_and_unicode_code_points():
    values = {"subject": "DATABASE", "level": "HARD"}
    assert QueuedExerciseRequest(**values, learning_intent="\u00a0\ufeff ").learning_intent is None
    assert QueuedExerciseRequest(**values, learning_intent="\u00a0" + "🚀" * 200 + "\u3000").learning_intent == "🚀" * 200
    with pytest.raises(ValueError, match="200 code point"):
        QueuedExerciseRequest(**values, learning_intent="🚀" * 201)
    rows = [{"concept": "락", "objective": "값" * 200, "question_type": "원인 진단"}] * 20
    assert summarize_history(rows) == {"concept_frequency": [("락", 20)], "recent_goals": [("값" * 120, "원인 진단")]}


@pytest.mark.parametrize("private_text", [
    "qa-7", "**qa** — 7", "ｑ\u200bａ · ７", "tester @ example . invalid", "조직 ID: acme-west",
])
def test_generated_identifiers_stop_before_embeddings_or_review(private_text):
    written = json.dumps(json.loads(WRITTEN) | {"concepts": [{"term":"격리 수준", "explanation":private_text}]})
    service, repo, request, dedup, budget, _ = run([PLAN, written], intent="팬텀 리드")
    with pytest.raises(GenerationRejected, match="private_output"):
        generate(service, request, repo, None, dedup, budget)
    dedup.check_duplicate.assert_not_called()
    assert service.client.generate.call_count == 2


def test_normalized_excerpt_from_a_long_intent_cannot_reach_later_models():
    intent = "고객사 내부용특별환경 이관작업전용문서 단계별처리규칙 상황에서 반복 조회를 판단하고 싶어요"
    leaking = json.loads(PLAN)
    leaking["plan"]["scenario"] = "내부용특별환경-이관작업전용문서 단계별 처리규칙"
    service, repo, request, dedup, budget, _ = run([json.dumps(leaking)], intent=intent)
    with pytest.raises(GenerationRejected, match="private_output"):
        generate(service, request, repo, None, dedup, budget)
    assert service.client.generate.call_count == 1
    dedup.candidate_ids.assert_not_called()


def test_private_input_and_missing_search_stop_before_provider_calls(mocker, monkeypatch):

    service = ExerciseService()
    service.client = Mock()
    repo = mocker.patch("app.repositories.exercise_repository.exercise_repository")
    repo.find_task.return_value = None
    monkeypatch.setattr(service, "deduplication_enabled", False)
    reserve = Mock()
    data = {"subject":"DATABASE", "level":"HARD", "request_type":"manual", "requester_id":1}
    for intent, code in [("테스트 qa-7", "private_input"), ("팬텀 리드", "search_configuration")]:
        with pytest.raises(GenerationRejected, match=code):
            service.run_queued("request", QueuedExerciseRequest(**data, learning_intent=intent), reserve)
    service.client.generate.assert_not_called()
    reserve.assert_not_called()
    repo.save_exercise.assert_not_called()


@pytest.mark.parametrize("decision,recommend_result", [(7, "existing"), (7, None), (None, None), (9, None)])
def test_only_confirmed_current_candidate_skips_writing(decision, recommend_result):
    from app.models.exercise import Exercise

    same = {"id":7, "question":"격리 수준별 반복 조회 결과 차이를 비교하세요.", "answer":"커밋 순서를 확인합니다.",
            "concept":"팬텀 리드", "objective":"격리 수준별 결과 비교"}
    irrelevant = {"id":8, "question":"팬텀 리드의 정의를 답하세요.", "answer":"정의를 답합니다.",
                  "concept":"팬텀 리드", "objective":"정의 설명"}
    service, repo, request, dedup, budget, reservations = run(
        [PLAN, json.dumps({"exercise_id":decision}), WRITTEN, PASS], intent="팬텀 리드")
    repo.recommendation_candidates.return_value = [same, irrelevant]
    dedup.candidate_ids.return_value = [7, 8]
    existing = Exercise(id=7, subject="DATABASE", level="HARD", question=same["question"], answer=same["answer"])
    recommend = Mock(return_value=existing if recommend_result else None)
    result = generate(service, request, repo, None, dedup, budget, recommend)
    comparison = json.loads(service.client.generate.call_args_list[1].kwargs["messages"][1]["content"])
    assert "intent" not in comparison and comparison["objective"]
    if recommend_result:
        assert result == existing
        assert reservations.count("model") == 2
        dedup.check_duplicate.assert_not_called()
    else:
        assert result[0] == json.loads(WRITTEN)["question"]
        assert reservations.count("model") == 4
        dedup.check_duplicate.assert_called_once()
    assert recommend.call_count == (1 if decision == 7 else 0)


def test_empty_search_can_continue_but_search_error_cannot_reach_review():
    from app.services.generation_workflow import SearchUnavailable

    service, repo, request, dedup, budget, _ = run([PLAN, WRITTEN, PASS], intent="팬텀 리드")
    dedup.candidate_ids.return_value = []
    repo.recommendation_candidates.return_value = []
    assert generate(service, request, repo, None, dedup, budget, Mock())[0] == json.loads(WRITTEN)["question"]

    service, repo, request, dedup, budget, _ = run([PLAN, WRITTEN, PASS], kind="automatic")
    dedup.check_duplicate.side_effect = RuntimeError("upstream unavailable")
    with pytest.raises(SearchUnavailable):
        generate(service, request, repo, None, dedup, budget)
    assert service.client.generate.call_count == 2

@pytest.mark.parametrize("intent", ["dummy-12345", "격리 수준과 관련해 dummy-12345 예시를 배우고 싶어요", "DUMMY 12345", "ＤＵＭＭＹ — １２３４５"])
def test_new_identifier_formats_are_blocked_before_even_planning(intent):
    service, repo, request, dedup, budget, _ = run([PLAN, WRITTEN, PASS], intent=intent)
    with pytest.raises(GenerationRejected, match="private_input"):
        generate(service, request, repo, None, dedup, budget, Mock())
    service.client.generate.assert_not_called()
    repo.recent_history.assert_not_called()
    dedup.candidate_ids.assert_not_called()


def test_identifier_fragments_block_copy_even_without_the_prefix():
    from app.services.generation_privacy import private_fragments, public_text
    fragments = private_fragments("격리 수준에서 dummy-12345 사례")
    assert not public_text(["１２３４５"], fragments)
    assert not public_text(["DUMMY · １２３４５"], fragments)
    assert public_text(["팬텀 리드", "SHA-256"], fragments)
