import os
import re
import time
from dotenv import load_dotenv
from app.services.kanana_generator import KananaGenerator

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

        # Kanana가 빈 줄이나 여러 문단을 포함해도 줄 시작의 Q:/A:를 기준으로 분리한다.
        question_marker = re.search(r"(?im)^\s*Q\s*:\s*", content)
        if not question_marker:
            return {"question": "", "answer": ""}

        answer_marker = re.search(
            r"(?im)^\s*A\s*:\s*",
            content[question_marker.end():],
        )
        if not answer_marker:
            return {"question": "", "answer": ""}

        answer_start = question_marker.end() + answer_marker.start()
        answer_content_start = question_marker.end() + answer_marker.end()
        question = self._normalize_generated_field(
            content[question_marker.end():answer_start]
        )
        answer = self._normalize_generated_field(content[answer_content_start:])

        if not question or not answer:
            return {"question": "", "answer": ""}

        return {"question": question, "answer": answer}

    @staticmethod
    def _normalize_generated_field(value: str) -> str:
        # DB에는 기존과 동일하게 개행 없는 한 줄 문자열로 저장한다.
        return re.sub(r"\s+", " ", value).strip().strip("\"").strip()

    # 문제 생성
    def generate_exercise(self, subject: str, level: str) -> str:
        deduplication_service = None
        if self.deduplication_enabled:
            from app.services.deduplication_service import deduplication_service
        
        if not self.system_prompt or not self.prompt_template: # Prompt 없으면 Error
            raise Exception("Prompt files not correctly loaded.")

        # Prompt에 전달받은 값 넣어 완성
        formatted_prompt = self.prompt_template.format(
            subject=subject,
            level=level
        )

        # 재시도 10번
        max_retries = 10
        
        for attempt in range(max_retries):
            try:
                # 문제 생성 요청
                content = self.client.generate(
                    messages=[
                        {"role": "system", "content": self.system_prompt},
                        {"role": "user", "content": formatted_prompt},
                    ],
                )
                
                # 생성된 내용 파싱
                parsed_data = self.parse_content(content)
                question = parsed_data["question"]
                
                if not question: # 질문이 없으면 실패로 간주하고 재시도
                     print(f"[Retry] Failed to parse question (Attempt {attempt+1}/{max_retries})\ncontent: {content}")
                     continue
                    
                if self.deduplication_enabled:
                    # 중복이면 새 문제 생성을 다시 시도한다.
                    if deduplication_service.is_duplicate(subject, level, content):
                        print(f"[Retry] Duplicate content detected (Attempt {attempt+1}/{max_retries})")
                        time.sleep(1) # Rate Limit 방지
                        continue

                    deduplication_service.save(subject, level, content)
                return content
                
            # 에러 처리
            except Exception as e:
                print(f"[Error] Generation failed: {e}")
                time.sleep(1) # Rate Limit 방지
        
        raise Exception("Failed to generate unique content after multiple retries.")


# 싱글톤을 위한 객체 생성
exercise_service = ExerciseService()
