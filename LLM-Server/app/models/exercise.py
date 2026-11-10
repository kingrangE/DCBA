from pydantic import BaseModel
from datetime import datetime
from enum import StrEnum

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

class ExerciseBase(BaseModel):
    subject: Subject
    level: Level

class ExerciseRequest(ExerciseBase):
    pass

class Exercise(ExerciseBase):
    id: int
    question: str
    answer: str
    created_at : datetime = None
