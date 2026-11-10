import os
import chromadb
from langchain_openai import OpenAIEmbeddings
from app.models.exercise import ExerciseRequest


class DeduplicationService:
    def __init__(self):
        self.similarity_threshold = 0.7
        self.embeddings = OpenAIEmbeddings(
            model="text-embedding-3-small", api_key=os.getenv("OPENAI_API_KEY"),
            request_timeout=30, max_retries=0,
        )
        self.persist_directory = os.getenv("CHROMA_PERSIST_DIRECTORY", "./chroma_db")
        self.client = chromadb.PersistentClient(path=self.persist_directory)
        # A new collection excludes legacy entries saved before the problem DB was committed.
        self.collection_name = "exercise_deduplication_v2"
        self.collection = self.client.get_or_create_collection(
            self.collection_name, configuration={"hnsw": {"space": "cosine"}},
            embedding_function=None,
        )

    def synchronize(self, repository, connection):
        self.collection = self.client.get_collection(self.collection_name, embedding_function=None)
        after_id = int((self.collection.metadata or {}).get("last_exercise_id", 0))
        while rows := repository.find_after(after_id, connection):
            requests = [ExerciseRequest(subject=row["subject"], level=row["level"]) for row in rows]
            documents = [f"Q: {row['question']}\nA: {row['answer']}" for row in rows]
            self.collection.upsert(
                ids=[str(row["id"]) for row in rows], documents=documents,
                embeddings=self.embeddings.embed_documents(documents),
                metadatas=[{"subject": request.subject.value, "level": request.level.value} for request in requests],
            )
            after_id = rows[-1]["id"]
            self.collection.modify(metadata={"last_exercise_id": after_id})

    def check_duplicate(self, subject: str, level: str, text: str) -> tuple[bool, list[float]]:
        request = ExerciseRequest(subject=subject, level=level)
        embedding = self.embeddings.embed_query(text)
        results = self.collection.query(
            query_embeddings=[embedding], n_results=1,
            where={"$and": [{"subject": request.subject.value}, {"level": request.level.value}]},
            include=["distances"],
        )
        distances = results["distances"][0]
        return bool(distances and 1 - distances[0] >= self.similarity_threshold), embedding

    def record(self, exercise, embedding):
        self.collection.upsert(
            ids=[str(exercise.id)], documents=[f"Q: {exercise.question}\nA: {exercise.answer}"],
            embeddings=[embedding], metadatas=[{"subject": exercise.subject.value, "level": exercise.level.value}],
        )
        # The shared generation lock keeps the committed DB prefix in the same order as this checkpoint.
        self.collection.modify(metadata={"last_exercise_id": exercise.id})


deduplication_service = (DeduplicationService() if os.getenv("DEDUPLICATION_ENABLED", "false").lower()
                         in {"1", "true", "yes", "on"} else None)
