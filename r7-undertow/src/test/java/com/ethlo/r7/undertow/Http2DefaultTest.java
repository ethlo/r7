package com.ethlo.r7.undertow;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.ethlo.r7.server.config.ServerConfig;
import io.restassured.RestAssured;

/**
 * HTTP/2 over the plaintext listener (h2c) is opt-in: a client that asks for it gets HTTP/1.1.
 */
public class Http2DefaultTest extends AbstractR7IntegrationTest
{
    @BeforeAll
    public static void setupTopology()
    {
        startGateway("configs/e2e/e2e-routes.yaml");
    }

    @Test
    public void http2IsOffByDefault()
    {
        assertThat(ServerConfig.standard().http().enableHttp2()).isFalse();
    }

    @Test
    public void aClientAskingForH2cIsServedHttp11() throws Exception
    {
        UPSTREAM_SERVER.stubFor(get(urlPathEqualTo("/h2c-check")).willReturn(aResponse().withStatus(200)));

        final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
        final HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + RestAssured.port + "/h2c-check")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
    }
}
