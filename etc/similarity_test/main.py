import os
import asyncio
from dotenv import load_dotenv
from openai import OpenAI
from langchain_chroma import Chroma
from langchain_openai import OpenAIEmbeddings
from langchain_core.documents import Document
import matplotlib.pyplot as plt
import numpy as np

load_dotenv()

class Generator:
    def __init__(self):
        self.api_key = os.getenv("EXAONE_API_KEY")
        self.base_url = os.getenv("EXAONE_API_URL")
        self.model_name = os.getenv("MODEL_NAME")
        if not self.api_key:
            print("[Warning] EXAONE_API_KEY is not set.")
            
        self.client = OpenAI(
            api_key=self.api_key,
            base_url=self.base_url,
        )
        base_dir = "/Users/project/Desktop/github/DCBA/etc/similarity_test/instructions"
        self.system_prompt = self._load_prompt(f"{base_dir}/system_prompt.txt")
        self.prompt_template = self._load_prompt(f"{base_dir}/generate_exercise_prompt.txt")
        self.similar_prompt_template = self._load_prompt(f"{base_dir}/similar_prompt.txt")
        self.different_prompt_template = self._load_prompt(f"{base_dir}/different_prompt.txt")

    def _load_prompt(self, file_path: str) -> str:
        try:
            with open(file_path, "r", encoding='utf-8') as f:
                return f.read()
        except Exception as e:
            print(f"[Error] Failed to load {file_path}: {e}")
            return ""

    async def generation(self, subject:str, level:str) -> str:
        user_prompt = self.prompt_template.replace("{subject}", subject).replace("{level}", level)
        try:
            completion = self.client.chat.completions.create(
                model=self.model_name or "exaone",
                messages=[
                    {"role": "system", "content": self.system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
            )
            return completion.choices[0].message.content
        except Exception as e:
            print(f"[Error] Generation Failed:", e)
            return ""

    async def similar_generation(self, subject:str, level:str, original:str) -> str:
        user_prompt = self.similar_prompt_template.replace("{subject}", subject).replace("{level}", level).replace("{original}", original)
        try:
            completion = self.client.chat.completions.create(
                model=self.model_name or "exaone",
                messages=[
                    {"role": "system", "content": self.system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
            )
            return completion.choices[0].message.content
        except Exception as e:
            print(f"[Error] Similar Generation Failed:", e)
            return ""
        
    async def different_generation(self, subject:str, level:str, original:str) -> str:
        user_prompt = self.different_prompt_template.replace("{subject}", subject).replace("{level}", level).replace("{original}", original)
        try:
            completion = self.client.chat.completions.create(
                model=self.model_name or "exaone",
                messages=[
                    {"role": "system", "content": self.system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
            )
            return completion.choices[0].message.content
        except Exception as e:
            print(f"[Error] Different Generation Failed:", e)
            return ""

class ChromaService:
    def __init__(self):
        self.embedding = OpenAIEmbeddings(model='text-embedding-3-small',
                                          api_key=os.getenv("OPENAI_API_KEY"))
        self.persist_directory = "./chroma_db"
        self.vector_store = Chroma(
            collection_name="Exercise",
            embedding_function=self.embedding,
            persist_directory=self.persist_directory,
        )

    def get_existing_data(self, subject:str, level:str):
        try:
            result = self.vector_store.get(
                where={
                    "$and": [
                        {"subject": {"$eq": subject}},
                        {"level": {"$eq": level}}
                    ]
                }
            )
            if result and result.get("documents") and len(result["documents"]) > 0:
                return result["documents"][0]
        except Exception as e:
            print(f"[Error] Failed to get existing data: {e}")
        return None

    def save(self, subject:str, level:str, text:str):
        try:
            document = Document(
                page_content=text,
                metadata={
                    "subject": subject,
                    "level": level
                }
            )
            self.vector_store.add_documents([document])
            print(f"[Saved] Subject: {subject}, Level: {level}")
        except Exception as e:
            print(f"[Error] Failed to save document: {e}")

    def get_similarity_score(self, subject:str, level:str, text:str) -> float:
        if not text:
            raise Exception("Text is empty.")
        result = self.vector_store.similarity_search_with_relevance_scores(
            query=text,
            k=1,
            where={
                "$and":[
                    {"subject":{"$eq":subject}},
                    {"level":{"$eq":level}}
                ]
            }
        )
        if not result:
            raise Exception("Cannot find any document.")
        return result[0][1]

async def main():
    generator = Generator()
    chromadb = ChromaService()
    
    SUBJECTS = [
        "컴퓨터 구조",
        "운영체제",
        "컴퓨터 네트워크",
        "자료구조",
        "알고리즘",
        "데이터베이스"
    ]
    LEVELS = [
        "난이도 1 (기초)",
        "난이도 2 (기본)",
        "난이도 3 (심화)"
    ]
    
    # 1. 원본 데이터 한 개씩 생성 및 ChromaDB 저장
    original_data = {}
    print("=== 원본 데이터 확인 및 생성 ===")
    for sub in SUBJECTS:
        for level in LEVELS:
            existing = chromadb.get_existing_data(sub, level)
            if existing:
                print(f"[Exists] Subject: {sub}, Level: {level}")
                original_data[(sub, level)] = existing
            else:
                content = await generator.generation(sub, level)
                if content:
                    chromadb.save(sub, level, content)
                    original_data[(sub, level)] = content
                else:
                    print(f"[Skip] Failed to generate original data for {sub} {level}")
                    original_data[(sub, level)] = ""

    # 2. 유사 / 비유사 데이터 10개씩 생성 후 점수 수집
    similar_scores = []
    different_scores = []

    print("\n=== 유사/비유사 데이터 생성 및 유사도 검사 ===")
    for sub in SUBJECTS:
        for level in LEVELS:
            orig = original_data.get((sub, level), "")
            if not orig:
                continue
                
            print(f"[{sub} - {level}] 10개씩 데이터 생성 중...")
            
            for i in range(10):
                sim_content = await generator.similar_generation(sub, level, orig)
                if sim_content:
                    try:
                        score = chromadb.get_similarity_score(sub, level, sim_content)
                        similar_scores.append(score)
                    except Exception as e:
                        print(f"[Error: Similar Score] {e}")

            for i in range(10):
                diff_content = await generator.different_generation(sub, level, orig)
                if diff_content:
                    try:
                        score = chromadb.get_similarity_score(sub, level, diff_content)
                        different_scores.append(score)
                    except Exception as e:
                        print(f"[Error: Different Score] {e}")

    # 3. 결과를 numpy와 matplotlib을 이용해 Graph로 출력
    print("\n=== 결과 그래프 출력 ===")
    plt.figure(figsize=(10, 6))
    
    np_similar = np.array(similar_scores)
    np_different = np.array(different_scores)
    
    if len(np_similar) > 0:
        print(f"Similar Score: 평균 {np.mean(np_similar):.4f}, 최소 {np.min(np_similar):.4f}, 최대 {np.max(np_similar):.4f}")
    if len(np_different) > 0:
        print(f"Different Score: 평균 {np.mean(np_different):.4f}, 최소 {np.min(np_different):.4f}, 최대 {np.max(np_different):.4f}")

    plt.hist(np_similar, bins=20, alpha=0.5, label='Similar Data', color='blue', edgecolor='black')
    plt.hist(np_different, bins=20, alpha=0.5, label='Different Data', color='red', edgecolor='black')
    
    plt.xlabel('Similarity Score')
    plt.ylabel('Frequency')
    plt.title('Similarity Scores Distribution (Chromadb Relevance Score)')
    plt.legend(loc='upper right')
    plt.grid(True, alpha=0.3)
    plt.show()

if __name__ == "__main__":
    asyncio.run(main())