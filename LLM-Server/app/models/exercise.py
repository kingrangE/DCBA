from pydantic import BaseModel, ConfigDict, Field, StringConstraints, field_validator
from datetime import datetime
from enum import StrEnum
from typing import Annotated, Literal

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


# The same edge whitespace as JavaScript trim, excluding Python-only control separators.
EDGE_WHITESPACE = "\u0009\u000a\u000b\u000c\u000d\u0020\u00a0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000\ufeff"


class QueuedExerciseRequest(ExerciseRequest):
    request_type: Literal["manual", "automatic", "legacy"] = "legacy"
    requester_id: Annotated[int, Field(gt=0)] | None = None
    learning_intent: str | None = None

    @field_validator("learning_intent")
    @classmethod
    def normalize_intent(cls, value):
        if value is None:
            return None
        value = value.strip(EDGE_WHITESPACE)
        if len(value) > 200:
            raise ValueError("Learning intent exceeds the 200 code point limit")
        return value or None

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
    created_at: datetime | None = None
