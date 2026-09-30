#!/bin/bash
# Debug build of the EXPERIMENTAL Helidon gateway image (Dockerfile.helidon.jvm): the jar, then
# the image. Tagged r7-gateway-helidon.
set -e
./mvnw clean package -DskipTests -pl r7-helidon -am
docker build -t r7-gateway-helidon -f Dockerfile.helidon.jvm .
