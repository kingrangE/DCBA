import os
import time
import re
from openai import OpenAI, APIConnectionError, APIStatusError
from dotenv import load_dotenv
from app.models.exercise import ExerciseRequest, SUBJECT_LABELS, LEVEL_LABELS

load_dotenv()

# 운동 관련 서비스
class ExerciseService:
    def __init__(self):
        # Exaone API 이용을 위한 설정
        self.api_key = os.getenv("EXAONE_API_KEY") 
        self.base_url = os.getenv("EXAONE_API_URL")
        self.model_name = os.getenv("MODEL_NAME")
        self.client = OpenAI(
            api_key=self.api_key,
            base_url=self.base_url,
            timeout=30,
            max_retries=0,
        )
        
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
        match = re.fullmatch(r"Q:[ \t]*(.*?)\r?\n[ \t]*A:[ \t]*(.*)", content.strip(), re.DOTALL)
        if not match:
            return {"question": "", "answer": ""}
        question, answer = (part.strip() for part in match.groups())
        if not question or not answer:
            return {"question": "", "answer": ""}
        return {"question": question, "answer": answer}

    # 문제 생성
    def generate_and_save(self, subject: str, level: str, task_id=None):
        from app.repositories.exercise_repository import exercise_repository
        from app.services.deduplication_service import deduplication_service
        request = ExerciseRequest(subject=subject, level=level)
        with exercise_repository.generation_lock() as connection:
            if task_id is not None:
                saved = exercise_repository.find_task(task_id, connection)
                if saved is not None:
                    return saved
            deduplication_service.synchronize(exercise_repository, connection)
            content, embedding = self.generate_exercise(request.subject.value, request.level.value)
            parsed = self.parse_content(content)
            saved = exercise_repository.save_exercise(
                request.subject.value, request.level.value, parsed["question"], parsed["answer"],
                task_id=task_id, connection=connection,
            )
            deduplication_service.record(saved, embedding)
            return saved

    def generate_exercise(self, subject: str, level: str) -> tuple[str, list[float]]:
        from app.services.deduplication_service import deduplication_service
        request = ExerciseRequest(subject=subject, level=level)
        subject, level = request.subject.value, request.level.value
        
        if not self.system_prompt or not self.prompt_template: # Prompt 없으면 Error
            raise Exception("Prompt files not correctly loaded.")

        # Prompt에 전달받은 값 넣어 완성
        formatted_prompt = self.prompt_template.format(
            subject=SUBJECT_LABELS[subject],
            level=LEVEL_LABELS[level]
        )

        # 재시도 10번
        max_retries = 10
        
        for attempt in range(max_retries):
            try:
                # 문제 생성 요청
                completion = self.client.chat.completions.create(
                    model=self.model_name,
                    extra_body={
                      "parse_reasoning": True,
                      "chat_template_kwargs": {
                        "enable_thinking": True
                      }
                    },
                    messages=[
                        {"role": "system", "content": self.system_prompt},
                        {"role": "user", "content": formatted_prompt},
                    ],
                )
                # 응답에서 content 꺼냄
                content = completion.choices[0].message.content
                if not isinstance(content, str):
                    continue
                
                # 생성된 내용 파싱
                parsed_data = self.parse_content(content)
                question = parsed_data["question"]
                
                if not question: # 질문이 없으면 실패로 간주하고 재시도
                     print(f"[Retry] Failed to parse question (Attempt {attempt+1}/{max_retries})\ncontent: {content}")
                     continue
                    
            except APIConnectionError:
                if attempt + 1 < max_retries:
                    time.sleep(min(2 ** attempt, 30))
                continue
            except APIStatusError as error:
                if error.status_code not in (408, 409, 429) and error.status_code < 500:
                    raise
                if attempt + 1 < max_retries:
                    time.sleep(min(2 ** attempt, 30))
                continue

            canonical_content = f"Q: {parsed_data['question']}\nA: {parsed_data['answer']}"
            duplicate, embedding = deduplication_service.check_duplicate(subject, level, canonical_content)
            if duplicate:
                print(f"[Retry] Duplicate content detected (Attempt {attempt+1}/{max_retries})")
                time.sleep(1)
                continue
            return content, embedding

        raise Exception("Failed to generate unique content after multiple retries.")


# 싱글톤을 위한 객체 생성
exercise_service = ExerciseService()
