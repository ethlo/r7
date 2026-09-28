package com.ethlo.r7.undertow;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.restassured.RestAssured;

/**
 * What the upstream receives when a client, not a trusted proxy, sends hop-by-hop and
 * forwarding headers. Sent over a raw socket because HTTP clients manage Connection themselves.
 */
public class UpstreamHeaderHygieneTest extends AbstractR7IntegrationTest
{
    @BeforeAll
    public static void setupTopology()
    {
        startGateway("configs/e2e/e2e-routes.yaml");
    }

    @Test
    public void clientCannotSpoofIdentityOrStripForwardingHeadersUpstream() throws IOException
    {
        UPSTREAM_SERVER.stubFor(get(urlEqualTo("/hygiene")).willReturn(aResponse().withStatus(200)));

        final String statusLine = sendRaw("GET /hygiene HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: keep-alive, X-Forwarded-For, X-Forwarded-Host\r\n"
                + "Keep-Alive: timeout=5\r\n"
                + "Proxy-Authorization: Basic c2VjcmV0\r\n"
                + "X-Forwarded-For: 6.6.6.6\r\n"
                + "X-Real-IP: 6.6.6.6\r\n"
                + "Forwarded: for=6.6.6.6\r\n"
                + "X-Original-URL: /admin\r\n"
                + "\r\n");

        Assertions.assertTrue(statusLine.startsWith("HTTP/1.1 200"), statusLine);
        UPSTREAM_SERVER.verify(getRequestedFor(urlEqualTo("/hygiene"))
                // Written by the gateway from the socket peer, and not stripped on the client's say-so
                .withHeader("X-Forwarded-For", matching("127\\.0\\.0\\.1|0:0:0:0:0:0:0:1|::1|172\\..*"))
                .withHeader("X-Forwarded-Host", equalTo("localhost"))
                .withHeader("X-Real-IP", absent())
                .withHeader("Forwarded", absent())
                .withHeader("X-Original-URL", absent())
                .withHeader("Keep-Alive", absent())
                .withHeader("Proxy-Authorization", absent()));
    }

    private static String sendRaw(final String request) throws IOException
    {
        try (final Socket socket = new Socket("localhost", RestAssured.port))
        {
            socket.setSoTimeout(5_000);
            final OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            final InputStream in = socket.getInputStream();
            final StringBuilder line = new StringBuilder();
            int b;
            while ((b = in.read()) != -1 && b != '\r')
            {
                line.append((char) b);
            }
            return line.toString();
        }
    }
}
