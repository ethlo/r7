# syntax=docker/dockerfile:1
FROM ghcr.io/graalvm/native-image-community:25 AS builder

WORKDIR /build

# Install unzip and download the flatc binary
ARG FLATC_VERSION=25.2.10
RUN microdnf install -y unzip && \
    curl -L -o flatc.zip "https://github.com/google/flatbuffers/releases/download/v${FLATC_VERSION}/Linux.flatc.binary.clang++-18.zip" && \
    unzip flatc.zip -d /usr/local/bin/ && \
    chmod +x /usr/local/bin/flatc && \
    rm flatc.zip

COPY . .

# Build everything.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw clean install -Pnative -DskipTests -pl venturi-undertow -am && \
    ./mvnw native:compile -Pnative -DskipTests -pl venturi-undertow

# STAGE 2: Runtime
FROM gcr.io/distroless/cc-debian12
WORKDIR /app

COPY --from=builder /build/venturi-undertow/target/venturi-server /app/server

USER 65532:65532
ENTRYPOINT ["/app/server"]