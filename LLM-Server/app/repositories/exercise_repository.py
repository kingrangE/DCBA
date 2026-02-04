import os
from pymongo import MongoClient, ReturnDocument
from app.models.exercise import Exercise

class ExerciseRepository:
    def __init__(self):
        self.mongo_uri = os.getenv("MONGO_URI", "mongodb://mongodb:27017")
        self.client = MongoClient(self.mongo_uri)
        self.db = self.client["dcba"]
        self.collection = self.db["exercises"]
        self.counters = self.db["counters"]

    def get_next_sequence(self, sequence_name):
        ret = self.counters.find_one_and_update(
            {"_id": sequence_name},
            {"$inc": {"seq": 1}},
            upsert=True,
            return_document=ReturnDocument.AFTER # 증가한 후의 값을 id로 업데이트
        )
        return ret["seq"] # sequence값 update

    def save_exercise(self, subject: str, level: str, title: str, best_answer: str) -> Exercise:
        exercise_id = self.get_next_sequence("exerciseid") # 문제 번호
        
        exercise_doc = { # 문제 구성에 맞게 정의
            "num": exercise_id,
            "title": title,
            "best_answer": best_answer,
            "subject": subject,
            "level": level,    
        }
        
        self.collection.insert_one(exercise_doc) # MongoDB에 삽입
        
        # 저장 후 모델 반환
        return Exercise(
            num=exercise_id,
            subject=subject,
            level=level,
            title=title,
            best_answer=best_answer
        )

exercise_repository = ExerciseRepository()
