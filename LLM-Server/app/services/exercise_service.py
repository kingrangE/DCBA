import os
import re
from dotenv import load_dotenv
from app.services.kanana_generator import KananaGenerator
from app.models.exercise import ExerciseRequest, SUBJECT_LABELS, LEVEL_LABELS

load_dotenv()

# 운동 관련 서비스
class ExerciseService:
    def __init__(self):
        # Kanana 모델은 첫 생성 요청에서 지연 로딩한다.
        self.model_name = os.getenv(
            "KANANA_MODEL_NAME", "kakaocorp/kanana-2-3b-instruct"
        )
        self.client = KananaGenerator(model_name=self.model_name)
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

    
    # 파싱 전용 함수 
    def parse_content(self, content: str) -> dict:
        if not content:
            return {"question": "", "answer": ""}

        match = re.fullmatch(r"Q:[ \t]*(.*?)\r?\n[ \t]*A:[ \t]*(.*)", content.strip(), re.DOTALL)
        if not match:
            return {"question": "", "answer": ""}
        question, answer = (part.strip() for part in match.groups())
        return {"question": question, "answer": answer} if question and answer else {"question": "", "answer": ""}

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
            content, embedding = self.generate_exercise(request.subject.value, request.level.value)
            parsed = self.parse_content(content)
            saved = exercise_repository.save_exercise(
                request.subject.value, request.level.value, parsed["question"], parsed["answer"],
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
            # Model loading, configuration and index failures need operator action, not repeated generation.
            content = self.client.generate(messages=[
                {"role": "system", "content": self.system_prompt},
                {"role": "user", "content": formatted_prompt},
            ])
            parsed = self.parse_content(content)
            if not parsed["question"] or not parsed["answer"]:
                continue
            embedding = None
            if deduplication is not None:
                duplicate, embedding = deduplication.check_duplicate(
                    request.subject.value, request.level.value,
                    f"Q: {parsed['question']}\nA: {parsed['answer']}",
                )
                if duplicate:
                    continue
            return content, embedding
        raise RuntimeError("Failed to generate unique content after multiple retries.")


exercise_service = ExerciseService()
