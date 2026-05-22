# native-image:21 は Oracle Linux 10.1 ベース (GLIBC 2.39)。
# ランタイムも OL10 に展わせることで GLIBC ミスマッチを解消。
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

# OL10 = GLIBC 2.39。native-image:21 (OL10.1ベース) と一致。
FROM oraclelinux:10-slim
WORKDIR /app
COPY --from=build /app/gate-core/build/native/nativeCompile/app ./app
EXPOSE 8080
CMD ["./app"]
