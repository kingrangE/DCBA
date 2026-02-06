from pydantic import BaseModel
from datetime import datetime

class ExerciseBase(BaseModel):
    subject: str
    level: str

class ExerciseRequest(ExerciseBase):
    pass

class Exercise(ExerciseBase):
    id: int
    question: str
    answer: str
    created_at : datetime = None