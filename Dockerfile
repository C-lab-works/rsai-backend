# Oracle GraalVM (native-image:21) はデフォルトで Oracle Linux 9 ベース (GLIBC 2.34)。
# ランタイムも OL9 に展わせることで GLIBC ミスマッチを解消。
# GFTC は商用・本番利用も無料 (2023年以降): https://www.oracle.com/downloads/licenses/graal-free-license.html
FROM container-registry.oracle.com/graalvm/native-image:21 AS build
WORKDIR /app
COPY settings.gradle.kts gradlew ./
COPY gradle/ gradle/
COPY gate-core/build.gradle.kts gate-core/
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :gate-core:dependencies --no-daemon -q 2>/dev/null || true
COPY gate-core/src gate-core/src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :gate-core:nativeCompile --no-daemon -q

# OL9 = GLIBC 2.34。native-image:21 (OL9ベース) と一致する。
FROM oraclelinux:9-slim
WORKDIR /app
COPY --from=build /app/gate-core/build/native/nativeCompile/app ./app
EXPOSE 8080
CMD ["./app"]
