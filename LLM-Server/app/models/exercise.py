from pydantic import BaseModel

class ExerciseBase(BaseModel):
    subject: str
    level: str

class ExerciseRequest(ExerciseBase):
    pass

class Exercise(ExerciseBase):
    num: int
    title: str
    best_answer: str