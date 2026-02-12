# syntax=docker/dockerfile:1
FROM ghcr.io/graalvm/native-image-community:21 AS builder

WORKDIR /build
COPY . .

# Build everything.
# We use -Pnative to ensure the native-image-plugin is active.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw clean install -Pnative -DskipTests -pl venturi-undertow -am && \
    ./mvnw native:compile -Pnative -DskipTests -pl venturi-undertow

# STAGE 2: Runtime
FROM gcr.io/distroless/cc-debian12
WORKDIR /app

# We use a wildcard 'venturi*' to find the binary if the name is slightly different
# The name should match the <imageName> in your POM.
COPY --from=builder /build/venturi-undertow/target/venturi-server /app/server

USER 65532:65532
ENTRYPOINT ["/app/server"]