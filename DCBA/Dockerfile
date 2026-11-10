FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM gradle:9.3.0-jdk21 AS builder
WORKDIR /app
COPY . .
RUN gradle bootJar --no-daemon --build-cache

FROM runtime AS ci
COPY app.jar app.jar

FROM runtime AS final
COPY --from=builder /app/build/libs/app.jar app.jar
