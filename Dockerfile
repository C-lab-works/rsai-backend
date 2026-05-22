# native-image:21-ol10 をビルド・ランタイム共通で使用。
# 同じベースイメージなので GLIBC バージョンが常に一致し、ミスマッチが永続的に発生しない。
# GFTC は商用・本番利用も無料 (2023年以降): https://www.oracle.com/downloads/licenses/graal-free-license.html
FROM container-registry.oracle.com/graalvm/native-image:21-ol10 AS build
WORKDIR /app
COPY settings.gradle.kts gradlew ./
COPY gradle/ gradle/
COPY gate-core/build.gradle.kts gate-core/
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :gate-core:dependencies --no-daemon -q 2>/dev/null || true
COPY gate-core/src gate-core/src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :gate-core:nativeCompile --no-daemon -q

# ランタイムも同じ OL10 イメージ。GLIBC ミスマッチが構造上発生しない。
FROM container-registry.oracle.com/graalvm/native-image:21-ol10
WORKDIR /app
COPY --from=build /app/gate-core/build/native/nativeCompile/app ./app
EXPOSE 8080
CMD ["./app"]
