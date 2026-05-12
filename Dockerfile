FROM ghcr.io/graalvm/native-image-community:21 AS build
RUN microdnf install -y findutils && microdnf clean all
WORKDIR /app
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :gate-core:nativeCompile --no-daemon -q

FROM gcr.io/distroless/cc-debian12
WORKDIR /app
COPY --from=build /app/gate-core/build/native/nativeCompile/app ./app
EXPOSE 8080
CMD ["./app"]
