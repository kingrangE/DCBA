import os
import time
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

    
    # 파싱 전용 함수 
    def parse_content(self, content: str) -> dict:
        question = ""
        answer = ""
        lines = content.strip().split("\n")

        # Q \n A 형식으로 들어오지 않았다면 return 빈 값
        if len(lines) != 2 : 
            return {"question": question, "answer": answer}
        
        # 제대로 들어왔으면 Q,A 파싱 (둘 중 하나라도 없으면 빈값 리턴(Error 처리 할 수 있게 ))
        for line in lines :
            processed_line = line.strip()
            if processed_line.startswith("Q:"):
                question = processed_line.replace("Q:", "").strip().strip("\"")
            elif processed_line.startswith("A:"):
                answer = processed_line.replace("A:", "").strip().strip("\"")
        if not question or not answer : 
            # 둘 중 하나라도 빈 값이면 빈 값 리턴
            return {"question": "", "answer": ""}
        # 둘 다 있으면 정상 리턴
        return {"question": question, "answer": answer}

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
                
                # 생성된 내용 파싱
                parsed_data = self.parse_content(content)
                question = parsed_data["question"]
                
                if not question: # 질문이 없으면 실패로 간주하고 재시도
                     print(f"[Retry] Failed to parse question (Attempt {attempt+1}/{max_retries})\ncontent: {content}")
                     continue
                    
                # 중복 검사 (중복되면 다시)
                if deduplication_service.is_duplicate(subject, level, content):
                    print(f"[Retry] Duplicate content detected (Attempt {attempt+1}/{max_retries})")
                    time.sleep(1) # Rate Limit 방지
                    continue
                
                # 중복 검사 통과하면 저장
                deduplication_service.save(subject, level, content)
                return content
                
            # 에러 처리
            except Exception as e:
                print(f"[Error] Generation failed: {e}")
                time.sleep(1) # Rate Limit 방지
        
        raise Exception("Failed to generate unique content after multiple retries.")


# 싱글톤을 위한 객체 생성
exercise_service = ExerciseService()
