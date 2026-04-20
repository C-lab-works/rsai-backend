# --- Stage 1: Build ---
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

COPY gradle/ gradle/
COPY gradlew settings.gradle.kts ./
COPY gate-mapping/ gate-mapping/
COPY gate-core/ gate-core/
COPY app/ app/

RUN chmod +x gradlew && ./gradlew :app:shadowJar --no-daemon --quiet

# --- Stage 2: Runtime ---
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S gate && adduser -S gate -G gate

WORKDIR /app

COPY --from=builder --chown=gate:gate /build/app/build/libs/app.jar app.jar

USER gate

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/json || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
