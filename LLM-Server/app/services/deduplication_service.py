import os
import redis
import json
from konlpy.tag import Okt
from typing import List, Set

class DeduplicationService:
    def __init__(self):
        # Token 저장을 위한 Redis 연결 정보
        self.redis_host = os.getenv("REDIS_HOST", "localhost")
        self.redis_port = int(os.getenv("REDIS_PORT", 6379))
        self.redis_client = redis.Redis(
            host=self.redis_host, 
            port=self.redis_port, 
            db=0, 
            decode_responses=True
        )

        # 형태소 분석을 위한 Okt 라이브러리 사용
        self.okt = Okt()

        # 유사도 기준
        self.similarity_threshold = 0.7

    def _get_tokens(self, text: str) -> Set[str]:
        try:
            nouns = self.okt.nouns(text)
            return set(nouns)
        except Exception as e:
            print(f"[Warning] Tokenization failed: {e}")
            return set()

    def _calculate_jaccard_similarity(self, set1: Set[str], set2: Set[str]) -> float:
        # 유사도 검사(자카드) (교집합/합집합)
        intersection = len(set1.intersection(set2))
        union = len(set1.union(set2))
        if union == 0:
            return 0.0
        return intersection / union

    def is_duplicate(self, subject: str, level: str, text: str) -> bool:
        new_tokens = self._get_tokens(text)
        if not new_tokens: # 토큰이 없으면 중복 X
            return False

        key = f"exercise:{subject}:{level}" # 저장할 키 (과목 : 레벨)
        
        stored_items = self.redis_client.lrange(key, 0, -1) # 저장된 값 Load
        
        # 저장된 item 하나씩 비교
        for item_json in stored_items: 
            try:
                item = json.loads(item_json)
                stored_tokens = set(item.get("tokens", []))
                
                similarity = self._calculate_jaccard_similarity(new_tokens, stored_tokens)
                if similarity >= self.similarity_threshold: 
                    print(f"[Duplicate Detected] Similarity: {similarity:.2f} | Text: {text[:30]}...")
                    return True #중복되면 True
            except Exception:
                continue
                
        return False

    # 토큰 저장
    def save(self, subject: str, level: str, text: str):
        tokens = list(self._get_tokens(text))
        if not tokens:
            return

        key = f"exercise:{subject}:{level}"
        data = {
            "tokens": tokens
        }
        
        self.redis_client.rpush(key, json.dumps(data, ensure_ascii=False))

# 싱글톤을 위한 객체 생성
deduplication_service = DeduplicationService()
