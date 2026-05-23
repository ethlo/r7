package com.ethlo.r7.undertow;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
    public static final String STATIC_CONTENT_SERVED_SUCCESSFULLY = "Static content served successfully!";
    protected static final Logger logger = LoggerFactory.getLogger(AbstractR7IntegrationTest.class);
    // We bind in-process to 8888 to match the internal container port for consistency
    protected static final int GATEWAY_PORT = 8888;
    protected static WireMockServer UPSTREAM_SERVER;
    protected static HttpClient HTTP_CLIENT;
    // Docker State
    protected static GenericContainer<?> R7_GATEWAY;
    // In-Process State
    protected static R7Main IN_PROCESS_SERVER;
    protected static Path IN_PROCESS_CONFIG_FILE;
    protected static Path IN_PROCESS_SERVER_FILE;

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
            R7_GATEWAY = null;
        }

        if (IN_PROCESS_SERVER != null)
        {
            try
            {
                IN_PROCESS_SERVER.stop();
            }
            catch (final Exception e)
            {
                logger.warn("Failed to cleanly stop in-process R7Main", e);
            }
            IN_PROCESS_SERVER = null;
        }

        if (UPSTREAM_SERVER != null)
        {
            UPSTREAM_SERVER.stop();
            UPSTREAM_SERVER = null;
        }
    }

    protected static void startGateway(final String configClasspath)
    {
        final String runMode = System.getProperty("r7.test.mode", "in-process");

        try
        {
            switch (runMode)
            {
                case "native-docker" -> startDockerContainer("docker.io/library/r7-native:latest", configClasspath);
                case "jvm-docker" -> startDockerContainer("docker.io/library/r7-jvm:latest", configClasspath);
                case "in-process" -> startInProcessEngine(configClasspath);
                default -> throw new IllegalArgumentException("Unknown r7.test.mode: " + runMode);
            }
        }
        catch (final Exception e)
        {
            throw new RuntimeException("Failed to boot gateway in mode: " + runMode, e);
        }

        setupRestAssured();
    }

    private static void startDockerContainer(final String image, final String configClasspath)
    {
        R7_GATEWAY = new GenericContainer<>(DockerImageName.parse(image))
                .withExposedPorts(GATEWAY_PORT, 18888)
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource(configClasspath),
                        "/app/config/routes.yaml"
                )
                // Injecting a test file for the static content route
                .withCopyToContainer(
                        Transferable.of(STATIC_CONTENT_SERVED_SUCCESSFULLY),
                        "/tmp/test.txt"
                )
                .withEnv("WIREMOCK_PORT", String.valueOf(UPSTREAM_SERVER.port()))
                .withEnv("UPSTREAM_HOST", "${UPSTREAM_HOST}")
                .withEnv("R7_ROUTES_CONFIG", "/app/config/routes.yaml")
                .withLogConsumer(new Slf4jLogConsumer(logger).withPrefix("R7-DOCKER"))
                .waitingFor(Wait.forHttp("/")
                        .forPort(GATEWAY_PORT)
                        .forStatusCodeMatching(code -> code >= 200 && code <= 500));

        R7_GATEWAY.start();
    }

    private static void startInProcessEngine(final String configClasspath) throws Exception
    {
        System.setProperty("WIREMOCK_PORT", String.valueOf(UPSTREAM_SERVER.port()));
        System.setProperty("UPSTREAM_HOST", "localhost");

        // Extract the routing config to a physical temp file for the HotReloadService
        IN_PROCESS_CONFIG_FILE = Files.createTempFile("r7-routes-", ".yaml");
        try (final InputStream is = AbstractR7IntegrationTest.class.getClassLoader().getResourceAsStream(configClasspath))
        {
            if (is == null)
            {
                throw new IllegalArgumentException("Could not find file on classpath: " + configClasspath);
            }
            Files.copy(is, IN_PROCESS_CONFIG_FILE, StandardCopyOption.REPLACE_EXISTING);
        }

        // Extract or generate the server.yaml for Undertow bindings
        IN_PROCESS_SERVER_FILE = Files.createTempFile("r7-server-", ".yaml");
        try (final InputStream is = AbstractR7IntegrationTest.class.getClassLoader().getResourceAsStream("configs/default-server.yaml"))
        {
            if (is != null)
            {
                Files.copy(is, IN_PROCESS_SERVER_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
            else
            {
                // Fallback to a minimal generated server.yaml if a default isn't mapped
                final String defaultServerYaml = "server:\n  port: " + GATEWAY_PORT + "\n  host: 0.0.0.0\n";
                Files.writeString(IN_PROCESS_SERVER_FILE, defaultServerYaml, StandardCharsets.UTF_8);
            }
        }

        Files.writeString(Paths.get("/tmp/test.txt"), STATIC_CONTENT_SERVED_SUCCESSFULLY, StandardOpenOption.CREATE);

        IN_PROCESS_SERVER = new R7Main(IN_PROCESS_CONFIG_FILE, IN_PROCESS_SERVER_FILE);
    }

    protected static void setupRestAssured()
    {
        RestAssured.baseURI = "http://localhost";

        if (R7_GATEWAY != null)
        {
            RestAssured.port = R7_GATEWAY.getMappedPort(GATEWAY_PORT);
        }
        else
        {
            RestAssured.port = GATEWAY_PORT;
        }

        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    protected static String getProxyBaseUrl()
    {
        if (R7_GATEWAY != null)
        {
            return "http://localhost:" + R7_GATEWAY.getMappedPort(GATEWAY_PORT);
        }

        return "http://localhost:" + GATEWAY_PORT;
    }

    protected void triggerHotReload(final String configClasspath) throws Exception
    {
        final String configContent;
        try (final InputStream is = getClass().getClassLoader().getResourceAsStream(configClasspath))
        {
            if (is == null)
            {
                throw new IllegalArgumentException("Could not find file on classpath: " + configClasspath);
            }
            configContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        if (R7_GATEWAY != null)
        {
            // Replace inside the container
            R7_GATEWAY.copyFileToContainer(
                    Transferable.of(configContent),
                    "/app/config/routes.yaml"
            );
        }
        else if (IN_PROCESS_CONFIG_FILE != null)
        {
            // Overwrite the physical file on the host OS to trigger java.nio.file.WatchService
            Files.writeString(IN_PROCESS_CONFIG_FILE, configContent, StandardCharsets.UTF_8);
        }
    }

    protected HttpResponse<String> sendGet(final String path) throws Exception
    {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(getProxyBaseUrl() + path))
                .GET()
                .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }
}