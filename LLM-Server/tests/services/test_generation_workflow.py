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

    def budget(stage):
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
    assert plan_input["intent"] == review_input["intent"] == intent
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
