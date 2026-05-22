# -muslib タグで musl libc を使った完全静的リンクのネイティブバイナリを生成。
# ランタイムの GLIBC バージョンに一切依存しない。
# GFTC は商用・本番利用も無料 (2023年以降): https://www.oracle.com/downloads/licenses/graal-free-license.html
FROM container-registry.oracle.com/graalvm/native-image:21-muslib AS build
WORKDIR /app
COPY settings.gradle.kts gradlew ./
COPY gradle/ gradle/
COPY gate-core/build.gradle.kts gate-core/
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :gate-core:dependencies --no-daemon -q 2>/dev/null || true
COPY gate-core/src gate-core/src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :gate-core:nativeCompile --no-daemon -q

# 完全静的リンクなので glibc 不要。distroless/static で最小構成。
FROM gcr.io/distroless/static-debian12
WORKDIR /app
COPY --from=build /app/gate-core/build/native/nativeCompile/app ./app
EXPOSE 8080
CMD ["./app"]
