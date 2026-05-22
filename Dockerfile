# Oracle GraalVM (native-image) を使用。
# GraalVM Community Edition では G1 GC が使えないため、
# G1 GC を有効化するために Oracle GraalVM (GFTC ライセンス) に切り替え。
# GFTC は商用・本番利用も無料 (2023年以降): https://www.oracle.com/downloads/licenses/graal-free-license.html
FROM container-registry.oracle.com/graalvm/native-image:21 AS build
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

# ビルドイメージ (Oracle Linux 8 / GLIBC 2.38) と同じ GLIBC を持つランタイムイメージを使用。
# distroless/base-debian12 は GLIBC 2.36 止まりのため、ネイティブバイナリが起動できない。
FROM oraclelinux:8-slim
WORKDIR /app
COPY --from=build /app/gate-core/build/native/nativeCompile/app ./app
EXPOSE 8080
CMD ["./app"]
