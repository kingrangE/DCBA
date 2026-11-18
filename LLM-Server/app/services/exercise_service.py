import json
import os
from dotenv import load_dotenv
from app.services.nvidia_generator import NvidiaGenerator
from app.models.exercise import ExerciseRequest, GeneratedExercise, SUBJECT_LABELS, LEVEL_LABELS

load_dotenv()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key")
        result[key] = value
    return result


# 운동 관련 서비스
class ExerciseService:
    def __init__(self):
        self.client = NvidiaGenerator()
        self.deduplication_enabled = os.getenv(
            "DEDUPLICATION_ENABLED", "false"
        ).lower() in {"1", "true", "yes", "on"}
        
        # Prompt load
        self.system_prompt = self._load_prompt("instructions/system_prompt.txt")
        self.prompt_template = self._load_prompt("instructions/generate_exercise_prompt.txt")

    def _load_prompt(self, file_path: str) -> str:
        try:
            with open(file_path, "r", encoding='utf-8') as f:
                return f.read()
        except Exception as e:
            print(f"[Error] Failed to load {file_path}: {e}")
            return ""

    
    def parse_content(self, content: str) -> GeneratedExercise | None:
        try:
            value = json.loads(content, object_pairs_hook=unique_object)
            return GeneratedExercise.model_validate(value)
        except (ValueError, RecursionError):
            return None

    def generate_and_save(self, subject: str, level: str, task_id=None):
        from app.repositories.exercise_repository import exercise_repository
        request = ExerciseRequest(subject=subject, level=level)
        deduplication = None
        if self.deduplication_enabled:
            from app.services.deduplication_service import deduplication_service
            deduplication = deduplication_service
        with exercise_repository.generation_lock() as connection:
            if task_id is not None:
                saved = exercise_repository.find_task(task_id, connection)
                if saved is not None:
                    return saved
            if deduplication is not None:
                deduplication.synchronize(exercise_repository, connection)
            question, answer, embedding = self.generate_exercise(request.subject.value, request.level.value)
            saved = exercise_repository.save_exercise(
                request.subject.value, request.level.value, question, answer,
                task_id=task_id, connection=connection,
            )
            if deduplication is not None:
                deduplication.record(saved, embedding)
            return saved

    def generate_exercise(self, subject: str, level: str):
        request = ExerciseRequest(subject=subject, level=level)
        if not self.system_prompt or not self.prompt_template:
            raise RuntimeError("Prompt files not correctly loaded.")
        formatted_prompt = self.prompt_template.format(
            subject=SUBJECT_LABELS[request.subject.value], level=LEVEL_LABELS[request.level.value]
        )
        deduplication = None
        if self.deduplication_enabled:
            from app.services.deduplication_service import deduplication_service
            deduplication = deduplication_service
        for attempt in range(10):
            # Retry unusable content here. The queue handles API and storage failures.
            content = self.client.generate(messages=[
                {"role": "system", "content": self.system_prompt},
                {"role": "user", "content": formatted_prompt},
            ])
            parsed = self.parse_content(content)
            if parsed is None:
                continue
            concepts = "\n".join(f"- **{item.term}** — {item.explanation}" for item in parsed.concepts)
            answer = f"{parsed.answer}\n\n##### 이 문제를 풀기 위해 알아야 할 개념\n\n{concepts}"
            embedding = None
            if deduplication is not None:
                duplicate, embedding = deduplication.check_duplicate(
                    request.subject.value, request.level.value,
                    f"Q: {parsed.question}\nA: {answer}",
                )
                if duplicate:
                    continue
            return parsed.question, answer, embedding
        raise ValueError("Failed to generate valid, unique content after multiple attempts")


exercise_service = ExerciseService()
