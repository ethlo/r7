package com.ethlo.r7.undertow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
public class R7NginxHttp2IsolationTest
{
    private static final Network NETWORK = Network.newNetwork();

    // NGINX configured explicitly for h2c (cleartext HTTP/2)
    private static final String NGINX_CONF = """
            events {}
            http {
                server {
                    listen 80 http2;
                    location / {
                        default_type text/plain;
                        return 200 'HTTP/2 routing to NGINX successful!';
                    }
                }
            }
            """;

    @Container
    public static final GenericContainer<?> NGINX_BACKEND = new GenericContainer<>(DockerImageName.parse("nginx:alpine"))
            .withNetwork(NETWORK)
            .withNetworkAliases("nginx-h2c")
            .withExposedPorts(80)
            .withCopyToContainer(Transferable.of(NGINX_CONF), "/etc/nginx/nginx.conf");

    // Simple route pointing to the NGINX alias
    private static final String ROUTES_YAML = """
            routes:
              - id: h2c-route
                match:
                  - PathStartsWith:
                      prefix: /
                upstream:
                  targets:
                    - url: "http://nginx-h2c:80"
            """;

    @Container
    public static final GenericContainer<?> R7_GATEWAY = new GenericContainer<>(DockerImageName.parse("docker.io/library/r7-native:latest"))
            .withNetwork(NETWORK)
            .withExposedPorts(8888)
            .withCopyToContainer(Transferable.of(ROUTES_YAML), "/app/config/routes.yaml")
            .withEnv("R7_ROUTES_CONFIG", "/app/config/routes.yaml")
            .dependsOn(NGINX_BACKEND);

    @Test
    public void testHttp2CleartextRouting() throws Exception
    {
        // 1. Force the Java test client to strictly require HTTP/2
        final HttpClient http2Client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        final URI targetUri = URI.create("http://localhost:" + R7_GATEWAY.getMappedPort(8888) + "/");
        final HttpRequest request = HttpRequest.newBuilder(targetUri).GET().build();

        final HttpResponse<String> response = http2Client.send(request, HttpResponse.BodyHandlers.ofString());

        // Verify the payload made it through the proxy
        assertEquals(200, response.statusCode());
        assertEquals("HTTP/2 routing to NGINX successful!", response.body());
        
        // Verify r7 actually maintained the HTTP/2 connection with the client
        assertEquals(HttpClient.Version.HTTP_2, response.version(), "Connection was unexpectedly downgraded to HTTP/1.1");
    }
}