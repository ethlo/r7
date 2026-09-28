package com.ethlo.r7.filters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.util.ShortCircuitGatewayResponse;
import com.ethlo.r7.util.constants.HttpHeaders;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.r7.validation.ValidationResult;

class TemplateRedirectFactoryTest
{
    private static ShortCircuitGatewayResponse redirect(final String source, final String target, final String path)
    {
        final GatewayRequest request = mock(GatewayRequest.class);
        when(request.path()).thenReturn(path);
        final ClientRequestGatewayExchange exchange = mock(ClientRequestGatewayExchange.class);
        when(exchange.clientRequest()).thenReturn(request);

        new TemplateRedirectFactory().create(new TemplateRedirectFactory.Config(source, target, null), null).onClientRequest(exchange);

        final ArgumentCaptor<ShortCircuitGatewayResponse> response = ArgumentCaptor.forClass(ShortCircuitGatewayResponse.class);
        verify(exchange).shortCircuit(response.capture());
        return response.getValue();
    }

    /**
     * The capture group comes from the request path, so a path template must not be turned into
     * an off-site redirect by what the client puts in it.
     */
    @ParameterizedTest
    @CsvSource({
            "^/go(.*)$, $1, /go//evil.example",
            "^/go(.*)$, $1, /go/\\evil.example",
            "^/go/(.*)$, $1, /go/https://evil.example",
            "^/go/(.*)$, $1, /go/javascript:alert(1)",
            "^/go(.*)$, $1, /go\\\\evil.example"
    })
    void aPathTemplateNeverRedirectsOffSite(final String source, final String target, final String path)
    {
        final ShortCircuitGatewayResponse response = redirect(source, target, path);
        assertThat(response.status()).isEqualTo(HttpStatuses.BAD_REQUEST);
    }

    @Test
    void aPathTemplateStillRedirectsWithinTheSite()
    {
        final ShortCircuitGatewayResponse response = redirect("^/old/(.*)$", "/new/$1", "/old/a/b");
        assertThat(response.status()).isEqualTo(HttpStatuses.FOUND);
        assertThat(response.headers().getFirst(HttpHeaders.LOCATION)).isEqualTo("/new/a/b");
    }

    /**
     * An absolute template keeps its literal origin; whatever the capture group adds lands in the
     * path, even when it looks like userinfo or another host.
     */
    @Test
    void anAbsoluteTemplateKeepsItsOrigin()
    {
        final ShortCircuitGatewayResponse ok = redirect("^/x/(.*)$", "https://good.example/$1", "/x/p?q=1");
        assertThat(ok.status()).isEqualTo(HttpStatuses.FOUND);
        assertThat(ok.headers().getFirst(HttpHeaders.LOCATION)).isEqualTo("https://good.example/p?q=1");

        final ShortCircuitGatewayResponse tricky = redirect("^/x/(.*)$", "https://good.example/$1", "/x/@evil.example");
        assertThat(tricky.headers().getFirst(HttpHeaders.LOCATION)).startsWith("https://good.example/");
    }

    /**
     * Without validation (as a hand-built config would be), a capture group straight after the
     * host is still caught at request time: the origin it produces is not the template's.
     */
    @Test
    void aCaptureGroupExtendingTheHostIsCaughtAtRequestTime()
    {
        assertThat(redirect("^/x(.*)$", "https://good.example$1", "/x.evil.example/p").status()).isEqualTo(HttpStatuses.BAD_REQUEST);
    }

    @ParameterizedTest
    @CsvSource({"https://$1/", "//$1.example.com/", "https://good.example$1", "https://$1@good.example/", "javascript:$1", "https:\\\\$1", "mailto:$1"})
    void aCaptureGroupInTheOriginIsRejectedAtStartup(final String target)
    {
        final ValidationResult result = new ValidationResult();
        new TemplateRedirectFactory.Config("^/(.*)$", target, null).validate(result);
        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void aLiteralOriginWithCaptureGroupsInThePathIsAccepted()
    {
        final ValidationResult result = new ValidationResult();
        new TemplateRedirectFactory.Config("^/(.*)$", "https://good.example/$1", null).validate(result);
        assertThat(result.hasErrors()).isFalse();
    }

    /**
     * A scheme without '//' has no host to keep: browsers read https:\\evil as https://evil,
     * and javascript: executes. Refused at request time too, for a config built without validation.
     */
    @Test
    void aSchemeOnlyTemplateNeverRedirects()
    {
        assertThat(redirect("^/x/(.*)$", "https:\\\\$1", "/x/evil.example").status()).isEqualTo(HttpStatuses.BAD_REQUEST);
        assertThat(redirect("^/x/(.*)$", "javascript:$1", "/x/alert(1)").status()).isEqualTo(HttpStatuses.BAD_REQUEST);
    }
}
