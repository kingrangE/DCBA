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
CONCEPT_ONLY = '{"decision":"intent","intent":{"concept":"팬텀 리드","objective":null}}'
GOAL = "반복 조회 결과 차이 판단"
INTENT = "팬텀 리드에서 반복 조회 결과 차이 판단을 연습하고 싶어요"
CONFIRMED = json.dumps({"decision":"intent","intent":{"concept":"팬텀 리드","objective":GOAL}})
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
    service, repo, request, dedup, budget, reservations = run(([CONCEPT_ONLY] if intent else []) + [PLAN, WRITTEN, PASS], kind=kind, intent=intent)
    assert generate(service, request, repo, None, dedup, budget)[3] == (
        "팬텀 리드", "반복 조회의 결과 차이를 판단한다.", "원인 진단", "1"
    )
    repo.recent_history.assert_called_once_with("DATABASE", "HARD", None)
    assert reservations == (["model"] if intent else []) + ["model", "model", "embedding", "model"]
    plan_input, writing_input, review_input = [
        json.loads(call.kwargs["messages"][1]["content"]) for call in service.client.generate.call_args_list[-3:]
    ]
    assert plan_input["intent"] == review_input["user_intent"] == ({"concept":intent,"objective":None} if intent else None)
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
    service, repo, request, dedup, budget, _ = run([CONCEPT_ONLY, PLAN, written], intent="팬텀 리드")
    with pytest.raises(GenerationRejected, match="private_output"):
        generate(service, request, repo, None, dedup, budget)
    dedup.check_duplicate.assert_not_called()
    assert service.client.generate.call_count == 3


def test_normalized_excerpt_from_a_long_intent_cannot_reach_later_models():
    intent = "팬텀 리드 고객사 내부용특별환경 이관작업전용문서 단계별처리규칙 상황에서 반복 조회를 판단하고 싶어요"
    leaking = json.loads(PLAN)
    leaking["plan"]["scenario"] = "내부용특별환경-이관작업전용문서 단계별 처리규칙"
    service, repo, request, dedup, budget, _ = run([CONCEPT_ONLY, json.dumps(leaking)], intent=intent)
    with pytest.raises(GenerationRejected, match="private_output"):
        generate(service, request, repo, None, dedup, budget)
    assert service.client.generate.call_count == 2
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
        [CONFIRMED, PLAN, json.dumps({"plan_matches_intent":True, "exercise_id":decision}), WRITTEN, PASS], intent=INTENT)
    repo.recommendation_candidates.return_value = [same, irrelevant]
    dedup.candidate_ids.return_value = [7, 8]
    existing = Exercise(id=7, subject="DATABASE", level="HARD", question=same["question"], answer=same["answer"])
    recommend = Mock(return_value=existing if recommend_result else None)
    result = generate(service, request, repo, None, dedup, budget, recommend)
    comparison = json.loads(service.client.generate.call_args_list[2].kwargs["messages"][1]["content"])
    assert comparison["user_intent"]["objective"] == GOAL and INTENT not in str(comparison)
    if recommend_result:
        assert result == existing
        assert reservations.count("model") == 3
        dedup.check_duplicate.assert_not_called()
    else:
        assert result[0] == json.loads(WRITTEN)["question"]
        assert reservations.count("model") == 5
        dedup.check_duplicate.assert_called_once()
    assert recommend.call_count == (1 if decision == 7 else 0)


def test_empty_search_can_continue_but_search_error_cannot_reach_review():
    from app.services.generation_workflow import SearchUnavailable

    service, repo, request, dedup, budget, _ = run([CONFIRMED, PLAN, WRITTEN, PASS], intent=INTENT)
    dedup.candidate_ids.return_value = []
    repo.recommendation_candidates.return_value = []
    assert generate(service, request, repo, None, dedup, budget, Mock())[0] == json.loads(WRITTEN)["question"]

    service, repo, request, dedup, budget, _ = run([PLAN, WRITTEN, PASS], kind="automatic")
    dedup.check_duplicate.side_effect = RuntimeError("upstream unavailable")
    with pytest.raises(SearchUnavailable):
        generate(service, request, repo, None, dedup, budget)
    assert service.client.generate.call_count == 2


@pytest.mark.parametrize("intent", [
    "dummy-12345", "격리 수준과 관련해 dummy-12345 예시를 배우고 싶어요", "DUMMY 12345", "ＤＵＭＭＹ — １２３４５",
    "PostgreSQL 5432 기본 포트와 dummy-12345", "Redis_6379 기본 포트와 개인 @ example . invalid",
    "MariaDB · 3306 기본 포트와 계정 ID: acme-west", "Redis 6379 기본 포트와 조직 ID: qa · ７",
    "PostgreSQL-5432 기본 포트와 ＤＵＭＭＹ — １２３４５",
])
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


def test_drifted_concept_is_replanned_before_candidate_lookup():
    wrong = json.loads(PLAN)
    wrong["plan"]["concept"] = "교착 상태 재시도"
    service, repo, request, dedup, budget, reservations = run(
        [CONFIRMED, json.dumps(wrong), PLAN, '{"plan_matches_intent":true,"exercise_id":7}'], intent=INTENT)
    repo.recommendation_candidates.return_value = [{"id":7, "question":"반복 조회 결과 차이를 비교하세요.",
                                                   "answer":"격리 수준에 따라 결과를 판단합니다.",
                                                   "concept":"팬텀 리드", "objective":GOAL}]
    recommend = Mock(return_value="matched")
    assert generate(service, request, repo, None, dedup, budget, recommend) == "matched"
    repo.recommendation_candidates.assert_called_once()
    dedup.candidate_ids.assert_called_once_with("DATABASE", "HARD", f"팬텀 리드\n{GOAL}")
    recommend.assert_called_once_with(7)
    assert reservations.count("model") == 4


def test_plan_judgment_drift_cannot_commit_even_with_a_candidate_id():
    responses = [CONFIRMED, PLAN, '{"plan_matches_intent":false,"exercise_id":8}',
                 PLAN, '{"plan_matches_intent":true,"exercise_id":null}', WRITTEN, PASS]
    service, repo, request, dedup, budget, _ = run(responses, intent=INTENT)
    repo.recommendation_candidates.return_value = [{"id":8, "question":"팬텀 리드를 정의하세요.",
        "answer":"정의를 설명합니다.", "concept":"팬텀 리드", "objective":"정의 설명"}]
    recommend = Mock()
    with pytest.raises(GenerationRejected, match="generation_limit"):
        generate(service, request, repo, None, dedup, budget, recommend)
    recommend.assert_not_called()
    assert service.client.generate.call_count == 6


def test_extractor_cannot_replace_original_concept():
    service, repo, request, dedup, budget, _ = run(
        ['{"decision":"intent","intent":{"concept":"교착 상태","objective":null}}', PLAN], intent="팬텀 리드")
    with pytest.raises(GenerationRejected, match="invalid_request"):
        generate(service, request, repo, None, dedup, budget)
    service.client.generate.assert_called_once()


@pytest.mark.parametrize("choice", [None, "ambiguous", "matched"])
def test_concept_only_request_recommends_or_writes_based_on_evidence(choice):
    responses = [CONCEPT_ONLY, PLAN]
    if choice:
        responses.append(json.dumps({"plan_matches_intent": True, "exercise_id": 7 if choice == "matched" else None}))
    if choice != "matched":
        responses.extend([WRITTEN, PASS])
    service, repo, request, dedup, budget, reservations = run(responses, intent="팬텀 리드")
    dedup.candidate_ids.return_value = [7] if choice else []
    repo.recommendation_candidates.return_value = [{
        "id": 7, "question": "팬텀 리드가 발생하는 이유를 설명하세요.", "answer": "격리 수준과 커밋 순서를 확인합니다.",
        "concept": "팬텀 리드", "objective": None,
    }] if choice else []
    recommend = Mock(return_value="existing")
    result = generate(service, request, repo, None, dedup, budget, recommend)
    dedup.candidate_ids.assert_called_once_with("DATABASE", "HARD", "팬텀 리드")
    repo.recommendation_candidates.assert_called_once_with("DATABASE", "HARD", 1, "팬텀 리드",
                                                            [7] if choice else [], None)
    if choice == "matched":
        assert result == "existing"
        recommend.assert_called_once_with(7)
        assert reservations == ["model", "model", "embedding", "model"]
    else:
        assert result[0] == json.loads(WRITTEN)["question"]
        recommend.assert_not_called()
        assert reservations.count("model") == (5 if choice else 4)


@pytest.mark.parametrize(("port", "formatted"), [
    ("PostgreSQL 5432", "PostgreSQL-5432"),
    ("Redis 6379", "Redis_6379"),
    ("MariaDB 3306", "MariaDB · 3306"),
])
def test_default_ports_pass_request_and_publication_checks(port, formatted):
    from app.services.generation_privacy import contains_identifier, private_fragments, public_text

    for expression in (port, formatted):
        intent = f"{expression} 기본 포트 확인 방법을 공부합니다."
        assert not contains_identifier(expression)
        assert not contains_identifier(intent)
        assert public_text([f"{formatted} 기본 포트에 연결하는 방법은?", f"{port} 기본 포트를 확인합니다."],
                           private_fragments(intent))
        for private_text in ("dummy-12345", "개인 @ example . invalid", "계정 ID: acme-west",
                             "조직 ID: qa · ７", "ＤＵＭＭＹ — １２３４５"):
            combined = f"{expression} 기본 포트와 {private_text}"
            assert contains_identifier(combined)
            assert not public_text([combined])
    assert contains_identifier(f"{port.split()[0]}-12345")
    assert contains_identifier(port.replace(" ", ""))
    assert contains_identifier(f"조직 ID: {port}")
    confidential = f"내부용특별환경 {port} 이관작업전용문서 단계별처리규칙"
    assert not public_text([confidential], private_fragments(confidential))

    extracted = json.dumps({"decision": "intent", "intent": {"concept": port, "objective": None}})
    plan = json.loads(PLAN)
    plan["plan"]["concept"] = port
    written = json.loads(WRITTEN)
    written.update(question=f"{formatted} 기본 포트에 어떻게 연결하나요?", answer=f"{port} 기본 포트를 확인합니다.")
    service, repo, request, dedup, budget, _ = run(
        [extracted, json.dumps(plan), json.dumps(written), PASS], intent=f"{port} 기본 포트 확인 방법을 공부합니다.")
    question, answer, _, _ = generate(service, request, repo, None, dedup, budget)
    assert formatted in question and port in answer
