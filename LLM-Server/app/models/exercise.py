from pydantic import BaseModel, ConfigDict, Field, StringConstraints
from datetime import datetime
from enum import StrEnum
from typing import Annotated

class Subject(StrEnum):
    COMPUTER_ARCHITECTURE = "COMPUTER_ARCHITECTURE"
    OPERATING_SYSTEM = "OPERATING_SYSTEM"
    COMPUTER_NETWORK = "COMPUTER_NETWORK"
    DATA_STRUCTURE = "DATA_STRUCTURE"
    ALGORITHM = "ALGORITHM"
    DATABASE = "DATABASE"

class Level(StrEnum):
    EASY = "EASY"
    MEDIUM = "MEDIUM"
    HARD = "HARD"

SUBJECT_LABELS = {
    "COMPUTER_ARCHITECTURE": "컴퓨터 구조",
    "OPERATING_SYSTEM": "운영체제",
    "COMPUTER_NETWORK": "컴퓨터 네트워크",
    "DATA_STRUCTURE": "자료구조",
    "ALGORITHM": "알고리즘",
    "DATABASE": "데이터베이스",
}
LEVEL_LABELS = {"EASY": "난이도 1 (기초)", "MEDIUM": "난이도 2 (기본)", "HARD": "난이도 3 (심화)"}

class ExerciseBase(BaseModel):
    subject: Subject
    level: Level

class ExerciseRequest(ExerciseBase):
    pass

NonBlankText = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1)]


class RelatedConcept(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    term: NonBlankText
    explanation: NonBlankText


class GeneratedExercise(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    question: NonBlankText
    answer: NonBlankText
    concepts: list[RelatedConcept] = Field(min_length=1, max_length=4)


class Exercise(ExerciseBase):
    id: int
    question: str
    answer: str
    created_at : datetime = None
