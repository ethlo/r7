#!/bin/bash
# Debug build of the gateway image, the same way CI builds it (build-push.yml): the jar, then
# Dockerfile.jvm. Tagged r7-gateway, which the jvm-docker integration tests run against.
set -e
./mvnw clean package -DskipTests -pl r7-undertow -am
docker build -t r7-gateway -f Dockerfile.jvm .
