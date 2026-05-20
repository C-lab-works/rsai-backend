FROM ghcr.io/graalvm/native-image-community:21 AS build
WORKDIR /app
# Copy build config first — changes rarely, keeps dependency layer cached
COPY settings.gradle.kts gradlew ./
COPY gradle/ gradle/
COPY gate-core/build.gradle.kts gate-core/
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :gate-core:dependencies --no-daemon -q 2>/dev/null || true
# Copy source — invalidates cache only when code changes
COPY gate-core/src gate-core/src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :gate-core:nativeCompile --no-daemon -q

FROM gcr.io/distroless/base-debian12
WORKDIR /app
COPY --from=build /app/gate-core/build/native/nativeCompile/app ./app
EXPOSE 8080
CMD ["./app"]
