package com.ethlo.r7.undertow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.InvalidTextValueException;
import com.ethlo.r7.api.IpSource;
import io.undertow.server.HttpServerExchange;

class UndertowGatewayRequestTest
{
    @Test
    void recognisesOnlyALeadingSchemeAsAbsoluteForm()
    {
        assertThat(UndertowGatewayRequest.isAbsoluteForm("http://host/a")).isTrue();
        assertThat(UndertowGatewayRequest.isAbsoluteForm("HTTPS://host")).isTrue();
        assertThat(UndertowGatewayRequest.isAbsoluteForm("svn+ssh://host/a")).isTrue();

        assertThat(UndertowGatewayRequest.isAbsoluteForm("/redirect?next=http://example.test/a")).isFalse();
        assertThat(UndertowGatewayRequest.isAbsoluteForm("/foo://bar")).isFalse();
        assertThat(UndertowGatewayRequest.isAbsoluteForm("*")).isFalse();
        assertThat(UndertowGatewayRequest.isAbsoluteForm("")).isFalse();
        assertThat(UndertowGatewayRequest.isAbsoluteForm("mailto:a@b")).isFalse();
        assertThat(UndertowGatewayRequest.isAbsoluteForm("1http://host")).isFalse();
        assertThat(UndertowGatewayRequest.isAbsoluteForm("a b://host")).isFalse();
    }

    /**
     * The URI becomes the journaled upstream start line, which is ISO-8859-1. A value outside it
     * is refused where the filter sets it, rather than truncated to its low bytes in the record.
     */
    @Test
    void aUriOutsideLatin1IsRefusedRatherThanRewrittenInTheJournal()
    {
        final HttpServerExchange exchange = new HttpServerExchange(null);
        exchange.setRequestURI("/before");
        final UndertowGatewayRequest request = new UndertowGatewayRequest(exchange, InetAddress.getLoopbackAddress(), IpSource.SOCKET);

        assertThatThrownBy(() -> request.uri("/caf\u00e9\u2014x"))
                .isInstanceOf(InvalidTextValueException.class)
                .hasMessageContaining("U+2014");
        assertThat(exchange.getRequestURI()).isEqualTo("/before");

        request.uri("/caf%C3%A9");
        assertThat(exchange.getRequestURI()).isEqualTo("/caf%C3%A9");
    }
}
