import os
from openai import OpenAI
from dotenv import load_dotenv

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

    
    # 문제 생성
    def generate_exercise(self, subject: str, level: str) -> str:
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
                

                # 중복 검사 (중복되면 다시)
                if deduplication_service.is_duplicate(subject, level, content):
                    print(f"[Retry] Duplicate content detected (Attempt {attempt+1}/{max_retries})")
                    continue
                
                # 중복 검사 통과하면 저장
                deduplication_service.save(subject, level, content)
                return content
                
            # 에러 처리
            except Exception as e:
                print(f"[Error] Generation failed: {e}")
        
        raise Exception("Failed to generate unique content after multiple retries.")


# 싱글톤을 위한 객체 생성
exercise_service = ExerciseService()
