mvn clean install -DskipTests

pack build venturi-server \
    --path venturi-undertow/target/venturi-undertow-1.0-SNAPSHOT.jar \
    --builder paketobuildpacks/builder-jammy-java-tiny \
    --env BP_JVM_VERSION=25 \
    --env BP_JVM_TYPE=jre \
    --env BP_JVM_JLINK_ENABLED=true \
    --env BP_JVM_JLINK_ARGS="--add-modules java.base,java.logging,java.naming,java.desktop,java.management,java.security.jgss,java.instrument,jdk.unsupported --strip-debug --no-man-pages --no-header-files --compress=2"
