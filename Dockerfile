FROM container-registry.oracle.com/graalvm/native-image:25-ol10 AS build
WORKDIR /app
COPY settings.gradle.kts gradlew ./
COPY gradle/ gradle/
COPY gate-core/build.gradle.kts gate-core/
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :gate-core:dependencies --no-daemon -q 2>/dev/null || true
COPY gate-core/src gate-core/src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :gate-core:nativeCompile --no-daemon -q
FROM oraclelinux:10-slim
# curl はコンテナ内 Docker health-cmd で必要
RUN microdnf install -y --nodocs curl && microdnf clean all
WORKDIR /app
COPY --from=build /app/gate-core/build/native/nativeCompile/app ./app
EXPOSE 8080
ENTRYPOINT ["/app/app"]
