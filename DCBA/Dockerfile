# 1단계
FROM gradle:8.14-jdk21 AS builder
WORKDIR /app
COPY . .
# build 진행
RUN gradle clean build -x test --no-daemon

# 테스트
FROM builder AS tester
RUN gradle test --no-daemon

# 아티팩트 가져와서 실행
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]