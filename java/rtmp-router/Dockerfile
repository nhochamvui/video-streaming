# syntax=docker/dockerfile:1

FROM ghcr.io/graalvm/native-image-community:21-ol9 AS native-builder
RUN microdnf install -y findutils && microdnf clean all
WORKDIR /build
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY src src
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && \
    ./gradlew nativeCompile --no-daemon

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=native-builder /build/build/native/nativeCompile/rtmp-router /app/rtmp-router
RUN chmod +x /app/rtmp-router
EXPOSE 1935 9100
ENTRYPOINT ["/app/rtmp-router"]
