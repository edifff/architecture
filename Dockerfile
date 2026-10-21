FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /build

COPY pom.xml .
COPY proto proto
COPY core core
COPY storage-service storage-service
COPY chat-service chat-service
COPY api api

RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre AS storage

WORKDIR /app
COPY --from=build /build/storage-service/target/storage-service.jar app.jar

ENV CHAT_DB_URL="jdbc:postgresql://db:5432/chat" \
    CHAT_DB_USER="chat" \
    CHAT_DB_PASSWORD="chat" \
    STORAGE_GRPC_PORT="50051"

EXPOSE 50051
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM eclipse-temurin:25-jre AS chat

WORKDIR /app
COPY --from=build /build/chat-service/target/chat-service.jar app.jar

ENV CHAT_GRPC_PORT="50052" \
    STORAGE_HOST="storage" \
    STORAGE_GRPC_PORT="50051"

EXPOSE 50052
ENTRYPOINT ["java", "-jar", "app.jar"]
