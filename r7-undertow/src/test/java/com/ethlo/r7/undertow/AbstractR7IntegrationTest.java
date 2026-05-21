package com.ethlo.r7.undertow;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.ConsoleNotifier;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.restassured.RestAssured;

public abstract class AbstractR7IntegrationTest
{
    private static final Logger logger = LoggerFactory.getLogger(AbstractR7IntegrationTest.class);
    protected static WireMockServer UPSTREAM_SERVER;
    protected static GenericContainer<?> R7_GATEWAY;
    protected static HttpClient HTTP_CLIENT;

    @BeforeAll
    public static void initBaseEnvironment()
    {
        HTTP_CLIENT = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        UPSTREAM_SERVER = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .notifier(new ConsoleNotifier(false)));
        UPSTREAM_SERVER.start();

        Testcontainers.exposeHostPorts(UPSTREAM_SERVER.port());
    }

    @AfterAll
    public static void tearDownEnvironment()
    {
        if (R7_GATEWAY != null)
        {
            R7_GATEWAY.stop();
        }
        if (UPSTREAM_SERVER != null)
        {
            UPSTREAM_SERVER.stop();
        }
    }

    protected static void startGateway(final String configClasspath)
    {
        R7_GATEWAY = new GenericContainer<>(DockerImageName.parse("docker.io/library/r7-native:latest"))
                .withExposedPorts(8888, 18888)
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource(configClasspath),
                        "/app/config/routes.yaml"
                )
                .withEnv("WIREMOCK_PORT", String.valueOf(UPSTREAM_SERVER.port()))
                .withEnv("R7_ROUTES_CONFIG", "/app/config/routes.yaml")
                .withLogConsumer(new Slf4jLogConsumer(logger).withPrefix("R7-GATEWAY"))
                .waitingFor(Wait.forHttp("/")
                        .forPort(8888)
                        .forStatusCodeMatching(code -> code >= 200 && code <= 500));
        ;

        R7_GATEWAY.start();

        RestAssured.baseURI = "http://localhost";
        RestAssured.port = R7_GATEWAY.getMappedPort(8888);
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    /**
     * Safely triggers the HotReloadService by reading a file from the classpath
     * and injecting it as a fresh byte stream to guarantee inode/timestamp changes.
     */
    protected void triggerHotReload(final String configClasspath) throws Exception
    {
        try (final InputStream is = getClass().getClassLoader().getResourceAsStream(configClasspath))
        {
            if (is == null)
            {
                throw new IllegalArgumentException("Could not find file on classpath: " + configClasspath);
            }

            final String configContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            R7_GATEWAY.copyFileToContainer(
                    Transferable.of(configContent),
                    "/app/config/routes.yaml"
            );
        }
    }

    protected String getProxyBaseUrl()
    {
        return "http://localhost:" + R7_GATEWAY.getMappedPort(8888);
    }

    protected HttpResponse<String> sendGet(final String path) throws Exception
    {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(getProxyBaseUrl() + path))
                .GET()
                .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }
}