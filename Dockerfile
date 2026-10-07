# Образ для запуска чат-сервера.
#
# Сам jar собирается отдельно (Maven из IDE или сервис `builder` в compose.yaml),
# потому что во время generate-sources плагин jOOQ поднимает контейнер PostgreSQL
# через Testcontainers, а внутри `docker build` Docker-демон недоступен.
#
#   docker compose --profile build run --rm builder   # собрать target/chat.jar
#   docker build -t arch-labs-chat .
FROM eclipse-temurin:25-jre

WORKDIR /app

COPY target/chat.jar app.jar

EXPOSE 8080

ENV CHAT_DB_URL="jdbc:postgresql://db:5432/chat" \
    CHAT_DB_USER="chat" \
    CHAT_DB_PASSWORD="chat" \
    CHAT_HTTP_PORT="8080"

ENTRYPOINT ["java", "-jar", "app.jar"]
