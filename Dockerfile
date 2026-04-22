# --- Stage 1: Build ---
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

COPY gradle/ gradle/
COPY gradlew settings.gradle.kts ./
COPY gate-mapping/ gate-mapping/
COPY gate-core/ gate-core/
COPY app/ app/

RUN chmod +x gradlew && ./gradlew :app:shadowJar --no-daemon --quiet

# --- Stage 2: Custom JRE ---
FROM eclipse-temurin:21-jdk-alpine AS jre-builder

RUN jlink \
    --add-modules java.base,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.security.jgss,java.sql,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=zip-6 \
    --output /custom-jre

# --- Stage 3: Runtime ---
FROM alpine:3.20

RUN addgroup -S gate && adduser -S gate -G gate

ENV JAVA_HOME=/opt/java
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY --from=jre-builder /custom-jre $JAVA_HOME

WORKDIR /app

COPY --from=builder --chown=gate:gate /build/app/build/libs/app.jar app.jar

USER gate

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/json || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
