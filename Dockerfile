FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app /data \
    && useradd --uid 10001 --home-dir /app --shell /usr/sbin/nologin app \
    && chown -R app:app /app /data
WORKDIR /app
COPY --from=build /src/app/target/app.jar /app/app.jar
USER app
ENV ARCHLABS_DB_URL="jdbc:h2:file:/data/chat;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"
ENV ARCHLABS_HTTP_PORT=8080
ENV ARCHLABS_METRICS_PORT=9100
EXPOSE 8080 9100
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS "http://localhost:${ARCHLABS_METRICS_PORT}/metrics" >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
