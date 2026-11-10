from unittest.mock import MagicMock
from app.models.exercise import Exercise
from app.services.exercise_service import exercise_service
import pytest

# +++++++++++++ 파싱 전용 함수 테스트들 +++++++++++++ 
def test_generation_uses_canonical_categories_and_display_labels(mocker):
    completion = mocker.patch.object(exercise_service.client.chat.completions, "create")
    completion.return_value.choices[0].message.content = "Q: Question\nA: Answer"
    dedup = mocker.patch("app.services.deduplication_service.deduplication_service")
    dedup.is_duplicate.return_value = False
    exercise_service.generate_exercise("DATA_STRUCTURE", "EASY")
    prompt = completion.call_args.kwargs["messages"][1]["content"]
    assert "자료구조" in prompt and "난이도 1 (기초)" in prompt
    dedup.is_duplicate.assert_called_once_with("DATA_STRUCTURE", "EASY", "Q: Question\nA: Answer")

def test_parse_content():
    # =========== given =========== 
    content = "Q:123 \n A:4456"
    service = exercise_service
    # =========== when =========== 
    parsed_dict = service.parse_content(content=content)
    # =========== then =========== 
    assert parsed_dict["question"] == "123"
    assert parsed_dict["answer"] == "4456"


def test_parse_content_with_spaces():
    # 공백이 다수 존재하는 경우
    content = "  Q:  123   \n   A:  4456   "
    service = exercise_service
    
    parsed_dict = service.parse_content(content=content)
    
    # 양쪽 빈칸이 싹 지워지고 "123"만 남았는지 확인!
    assert parsed_dict["question"] == "123"
    assert parsed_dict["answer"] == "4456"

def test_parse_content_with_spaces():
    # 공백이 다수 존재하는 경우
    # =========== given =========== 
    content = "  Q:  123   \n   A:  4456   "
    service = exercise_service
    
    # =========== when =========== 
    parsed_dict = service.parse_content(content=content)
    
    # =========== then =========== 
    # 값을 잘 추출했는지 확인
    assert parsed_dict["question"] == "123"
    assert parsed_dict["answer"] == "4456"

def test_parse_content_with_weird_two_line():
    """라인이 2개로 구분되어 있으나, Q,A가 아닌 경우"""
    # =========== given =========== 
    bad_content = " Q: 1123 A: 456 \n 123"
    service = exercise_service

    # =========== when =========== 
    parsed_dict = service.parse_content(bad_content)

    # =========== then =========== 
    # 정상이 아니므로 둘 다 빈값으로 들어왔는가?
    assert parsed_dict["question"] == ""
    assert parsed_dict["answer"] == ""


def test_parse_content_with_weird_two_line():
    """라인 구분 없이 값이 입력된 경우"""
    # =========== given =========== 
    bad_content = " Q: 1123 A: 456"
    service = exercise_service

    # =========== when =========== 
    parsed_dict = service.parse_content(bad_content)

    # =========== then =========== 
    # 정상이 아니므로 둘 다 빈값으로 들어왔는가?
    assert parsed_dict["question"] == ""
    assert parsed_dict["answer"] == ""

def test_parse_content_with_only_two_lines_A():
    """A만 두 줄로 구성된 경우"""
    # =========== given =========== 
    bad_content = "A: 456 \n1515"
    service = exercise_service

    # =========== when =========== 
    parsed_dict = service.parse_content(bad_content)

    # =========== then =========== 
    # 정상이 아니므로 둘 다 빈값으로 들어왔는가?
    assert parsed_dict["question"] == ""
    assert parsed_dict["answer"] == ""

def test_generate_exercise_success(mocker):
    """문제 생성을 한 번에 잘 한 경우"""
    # =========== given =========== 
    mock_completion = mocker.Mock()
    # 함수 return 값을 생성 결과 mock로 연결 (그럼 content가 이거다 가정)
    mocker.patch.object(exercise_service.client.chat.completions, 
                        "create", 
                        # completion.choices[0].message.content 형태
                        return_value=mocker.Mock(
                            choices = [
                                    mocker.Mock(
                                        message=mocker.Mock(
                                            content="Q: 1123 \nA:456"
                                        )
                                    )
                                ]
                            )
                        )

    # parse_content의 return 값을 정해줌
    mocker.patch.object(exercise_service, "parse_content", return_value={"question":"1123","answer":"456"})

    # 중복 검사 객체 mock
    mock_dedup = mocker.patch("app.services.deduplication_service.deduplication_service") 
    # return 값을 False로 설정(중복 X 가정)
    mock_dedup.is_duplicate.return_value=False

    # =========== when =========== 
    # ALGORITHM EASY로 입력 받았다 가정
    result = exercise_service.generate_exercise("ALGORITHM","EASY")

    # =========== then =========== 
    assert result == "Q: 1123 \nA:456"

def test_generate_exercise_success_within_max_tries(mocker):
    """문제 생성을 maximum try이내로 잘 한 경우"""
    # =========== given =========== 
    mock_completion = mocker.Mock()
    # 함수 return 값을 생성 결과 mock로 연결 (그럼 content가 이거다 가정)
    mocker.patch.object(exercise_service.client.chat.completions, 
                        "create", 
                        # completion.choices[0].message.content 형태
                        return_value=mocker.Mock(
                            choices = [
                                    mocker.Mock(
                                        message=mocker.Mock(
                                            content="Q: 1123 \nA:456"
                                        )
                                    )
                                ]
                            )
                        )
    # parse_content의 return 값을 이상하게 정해줌 (try : 1~5)
    mocker.patch.object(exercise_service, "parse_content",side_effect=[
        {"question":"", "answer":""},
        {"question":"", "answer":""},
        {"question":"", "answer":""},
        {"question":"", "answer":""},
        {"question":"1123", "answer":"456"},
    ] )

    # 중복 검사 객체 mock
    mock_dedup = mocker.patch("app.services.deduplication_service.deduplication_service") 
    # return 값을 False로 설정(중복 X 가정)
    mock_dedup.is_duplicate.return_value=False

    # =========== when =========== 
    # ALGORITHM EASY로 입력 받았다 가정
    result = exercise_service.generate_exercise("ALGORITHM","EASY")

    # =========== then =========== 
    assert result == "Q: 1123 \nA:456"

def test_generate_exercise_success_within_max_tries(mocker):
    """중복 검사 3번째에 통과되는 경우"""
    # =========== given =========== 
    mock_dedup = mocker.patch("app.services.deduplication_service.deduplication_service") 
    mock_dedup.is_duplicate.side_effect = [True,True,False] # 2번 중복 후 3번재 통과
    mock_completion = mocker.Mock()
    # 함수 return 값을 생성 결과 mock로 연결 (그럼 content가 이거다 가정)
    mocker.patch.object(exercise_service.client.chat.completions, 
                        "create", 
                        # completion.choices[0].message.content 형태
                        return_value=mocker.Mock(
                            choices = [
                                    mocker.Mock(
                                        message=mocker.Mock(
                                            content="Q: 1123 \nA:456"
                                        )
                                    )
                                ]
                            )
                        )
    mocker.patch.object(exercise_service,"parse_content",return_value={"question":"1123","answer":"456"})

    # =========== when =========== 
    # ALGORITHM EASY로 입력 받았다 가정
    result = exercise_service.generate_exercise("ALGORITHM","EASY")

    # =========== then =========== 
    # 2번 호출됨
    assert mock_dedup.is_duplicate.call_count == 3

def test_generate_exercise_success_within_max_tries(mocker):
    """무한 중복으로 인한 취소"""
    # =========== given =========== 
    mock_dedup = mocker.patch("app.services.deduplication_service.deduplication_service") 
    mock_dedup.is_duplicate.side_effect = [True]*10 # 2번 중복 후 3번재 통과
    mock_completion = mocker.Mock()

    # 값은 정상적으로 잘 제작 됐는데 무한 중복으로 인해 취소되는걸 확인
    mocker.patch.object(exercise_service.client.chat.completions, 
                        "create", 
                        # completion.choices[0].message.content 형태
                        return_value=mocker.Mock(
                            choices = [
                                    mocker.Mock(
                                        message=mocker.Mock(
                                            content="Q: 1123 \nA:456"
                                        )
                                    )
                                ]
                            )
                        )
    
    mocker.patch.object(exercise_service,"parse_content",return_value={"question":"1123","answer":"456"})

    # =========== when =========== 
    # ALGORITHM EASY로 입력 받았다 가정 (Catched Error를 excinfo에 담음)
    with pytest.raises(Exception)as excinfo :
        exercise_service.generate_exercise("ALGORITHM","EASY")

    # =========== then =========== 
    # 10번 시도한거 맞는지 확인
    assert mock_dedup.is_duplicate.call_count == 10  
    # Error Message 확인
    assert "Failed to generate unique content after multiple retries." in str(excinfo.value)


@pytest.mark.parametrize("status", [400, 401, 403, 404, 422])
def test_permanent_api_errors_are_not_retried(mocker, status):
    import httpx
    from openai import APIStatusError
    response = httpx.Response(status, request=httpx.Request("POST", "http://example.invalid"))
    generate = mocker.patch.object(exercise_service.client.chat.completions, "create",
                                  side_effect=APIStatusError("permanent", response=response, body=None))
    sleep = mocker.patch("app.services.exercise_service.time.sleep")
    with pytest.raises(APIStatusError):
        exercise_service.generate_exercise("ALGORITHM", "EASY")
    assert generate.call_count == 1
    sleep.assert_not_called()


@pytest.mark.parametrize("status", [408, 409, 429, 500, 503])
def test_transient_api_errors_retry_with_backoff(mocker, status):
    import httpx
    from openai import APIStatusError
    response = httpx.Response(status, request=httpx.Request("POST", "http://example.invalid"))
    completion = mocker.MagicMock()
    completion.choices[0].message.content = "Q: Question\nA: Answer"
    generate = mocker.patch.object(exercise_service.client.chat.completions, "create",
                                  side_effect=[APIStatusError("temporary", response=response, body=None), completion])
    sleep = mocker.patch("app.services.exercise_service.time.sleep")
    mocker.patch("app.services.deduplication_service.deduplication_service").is_duplicate.return_value = False
    assert exercise_service.generate_exercise("ALGORITHM", "EASY") == "Q: Question\nA: Answer"
    assert generate.call_count == 2
    sleep.assert_called_once_with(1)


def test_connection_failure_has_bounded_backoff(mocker):
    import httpx
    from openai import APIConnectionError
    generate = mocker.patch.object(exercise_service.client.chat.completions, "create",
                                  side_effect=APIConnectionError(request=httpx.Request("POST", "http://example.invalid")))
    sleep = mocker.patch("app.services.exercise_service.time.sleep")
    with pytest.raises(Exception, match="Failed to generate"):
        exercise_service.generate_exercise("ALGORITHM", "EASY")
    assert generate.call_count == 10
    assert sleep.call_count == 9
    assert max(call.args[0] for call in sleep.call_args_list) <= 30
