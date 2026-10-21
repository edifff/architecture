# Образ для запуска чат-сервера: сборка jar внутри Dockerfile (многостадийная).
#
# Тесты при сборке пропускаются — им нужен Docker-демон (Testcontainers),
# который внутри `docker build` недоступен. Прогоняются отдельно: `mvn test`
# или `docker compose --profile test run --rm tests`.
#
#   docker compose up --build        — PostgreSQL + чат-сервер
#   docker compose --profile test run --rm tests   — прогнать тесты в контейнере

FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /build

COPY pom.xml .
COPY proto ./proto
COPY app ./app

# -pl app -am собирает приложение вместе с зависимым модулем proto
# (контракты и кодоген gRPC); тесты пропускаются — им нужен Docker-демон.
RUN mvn -B -ntp -pl app -am -Dmaven.test.skip=true package

FROM eclipse-temurin:25-jre

WORKDIR /app

COPY --from=build /build/app/target/chat.jar app.jar

EXPOSE 8080

ENV CHAT_DB_URL="jdbc:postgresql://db:5432/chat" \
    CHAT_DB_USER="chat" \
    CHAT_DB_PASSWORD="chat" \
    CHAT_HTTP_PORT="8080"

ENTRYPOINT ["java", "-jar", "app.jar"]
