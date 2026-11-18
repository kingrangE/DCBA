# ETC 폴더
- Threshold 지점을 찾거나, 각 기능의 간단 구현을 테스트 해보는 용도의 코드들의 모임입니다.

# similarity_test
- ChromaDB를 이용한 중복 검사에서 적절한 Threshold를 구하기 위한 유사도 테스트 코드입니다.
### Logic
1. 원본 데이터 없을 시, 각 분야/수준별로 원본 데이터 생성 후 Chroma DB에 저장
2. 각 분야/수준별로 