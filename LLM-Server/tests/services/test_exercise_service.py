from unittest.mock import MagicMock
from app.models.exercise import Exercise
from app.services.exercise_service import exercise_service
import pytest
# +++++++++++++ 파싱 전용 함수 테스트들 +++++++++++++ 
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
