ARG MODULE=api
FROM maven:3.9.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /src
COPY . .
RUN mvn -B -q -DskipTests package
RUN cp "${MODULE}/target/${MODULE}.jar" /app.jar

FROM maven:3.9.9-eclipse-temurin-21 AS test
WORKDIR /src
COPY . .
RUN mvn -B -q -DskipTests package
CMD ["mvn", "-B", "test"]

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app /data \
    && useradd --uid 10001 --home-dir /app --shell /usr/sbin/nologin app \
    && chown -R app:app /app /data
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
USER app
ENV ARCHLABS_DB_URL="jdbc:h2:file:/data/chat;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"
ENV ARCHLABS_HTTP_PORT=8080
ENV ARCHLABS_METRICS_PORT=9100
ENV HEALTH_PORT=9100
EXPOSE 8080 9091 9092 9100 9101 9102
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
    CMD curl -fsS "http://localhost:${HEALTH_PORT}/metrics" >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
