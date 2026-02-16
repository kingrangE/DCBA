import os
from langchain_chroma import Chroma
from langchain_openai import OpenAIEmbeddings
from langchain_core.documents import Document
from dotenv import load_dotenv

load_dotenv()

class DeduplicationService:
    def __init__(self):
        self.similarity_threshold = 0.7
        # Cosine Similarity를 위한 Embedding 모델
        self.embeddings = OpenAIEmbeddings(model="text-embedding-3-small",
                                           api_key=os.getenv("OPENAI_API_KEY"))

        # Chroma DB 초기화
        self.persist_directory = "./chroma_db"
        self.vector_store = Chroma(
            collection_name="exercise_deduplication",
            embedding_function=self.embeddings,
            persist_directory=self.persist_directory,
        )

    def is_duplicate(self, subject: str, level: str, text: str) -> bool:
        """
        text 중복 결과 반환 (True: 중복 / False: 중복 아님)
        """
        
        if not text: # 결과가 없으면 중복일 수 없음 (예외처리)
            return False

        try:
            # Metadata를 사용하여 효율 높여 비교
            results = self.vector_store.similarity_search_with_relevance_scores(
                query=text,
                k=1,
                filter={
                    "$and": [
                        {"subject": {"$eq": subject}},
                        {"level": {"$eq": level}}
                    ]
                }
            )

            if not results: # 없으면 중복이 아님 (예외 처리)
                return False

            # 가장 높은 값 확인
            best_doc, score = results[0]
            
            # threshold 넘으면 중복 (로그 출력 후 중복 처리)
            if score >= self.similarity_threshold:
                print(f"[Duplicate Detected] Score: {score:.4f} \n Existing Content[:30]: {best_doc.page_content[:30]}... \n New Content[:30]: {text[:30]}...")
                return True
            
            return False

        except Exception as e:
            print(f"[Error] Deduplication check failed: {e}")
            return False

    def save(self, subject: str, level: str, text: str):
        """
        Chroma DB에 Document 추가
        """

        try:
            document = Document(
                page_content=text,
                metadata={
                    "subject": subject,
                    "level": level
                }
            )
            self.vector_store.add_documents([document])
            print(f"[Saved] Subject: {subject}, Level: {level}, Content[:30]: {text[:30]}..")

        except Exception as e:
            print(f"[Error] Failed to save document: {e}")

# 싱글톤 객체 생성
deduplication_service = DeduplicationService()
