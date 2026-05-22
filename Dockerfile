# G1 GC は glibc 環境専用。musl は不可。
# GFTC は商用・本番利用も無料 (2023年以降): https://www.oracle.com/downloads/licenses/graal-free-license.html
FROM container-registry.oracle.com/graalvm/native-image:21 AS build
WORKDIR /app

# ビルド環境の OS / GLIBC バージョンをログに出力して確定する。
RUN cat /etc/os-release && ldd --version 2>&1 | head -3

COPY settings.gradle.kts gradlew ./
COPY gradle/ gradle/
COPY gate-core/build.gradle.kts gate-core/
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :gate-core:dependencies --no-daemon -q 2>/dev/null || true
COPY gate-core/src gate-core/src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :gate-core:nativeCompile --no-daemon -q

# TODO: 上の RUN のログで GLIBC バージョンを確認したら、
#       合致するランタイムイメージに固定する。
FROM oraclelinux:9-slim
WORKDIR /app
COPY --from=build /app/gate-core/build/native/nativeCompile/app ./app
EXPOSE 8080
CMD ["./app"]
