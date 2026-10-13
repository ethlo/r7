package com.ethlo.r7.filters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.MutableGatewayRequest;
import com.ethlo.r7.api.UpstreamRequestGatewayExchange;
import com.ethlo.r7.validation.ValidationResult;

class RewritePathFactoryTest
{
    private static String rewrite(final String regexp, final String replacement, final String path)
    {
        final GatewayRequest request = mock(GatewayRequest.class);
        when(request.path()).thenReturn(path);
        final MutableGatewayRequest upstream = mock(MutableGatewayRequest.class);
        final UpstreamRequestGatewayExchange exchange = mock(UpstreamRequestGatewayExchange.class);
        when(exchange.clientRequest()).thenReturn(request);
        when(exchange.upstreamRequest()).thenReturn(upstream);

        new RewritePathFactory().create(new RewritePathFactory.Config(regexp, replacement), null).onUpstreamRequest(exchange);

        final ArgumentCaptor<String> newPath = ArgumentCaptor.forClass(String.class);
        verify(exchange.upstreamRequest(), org.mockito.Mockito.atMost(1)).path(newPath.capture());
        return newPath.getAllValues().isEmpty() ? null : newPath.getValue();
    }

    private static List<String> errors(final String regexp, final String replacement)
    {
        final ValidationResult result = new ValidationResult();
        new RewritePathFactory.Config(regexp, replacement).validate(result);
        return result.getErrors();
    }

    @Test
    void numberedAndNamedGroupsAreReplaced()
    {
        assertThat(rewrite("^/api/(.*)$", "/v2/$1", "/api/users/42")).isEqualTo("/v2/users/42");
        assertThat(rewrite("^/api/(?<rest>.*)$", "/v2/{rest}", "/api/users/42")).isEqualTo("/v2/users/42");
    }

    @Test
    void everyMatchIsReplaced()
    {
        assertThat(rewrite("/+", "/", "/a//b///c")).isEqualTo("/a/b/c");
        assertThat(rewrite("^/x(?<n>[0-9])", "/y{n}", "/x1")).isEqualTo("/y1");
    }

    @Test
    void noMatchLeavesThePathAlone()
    {
        assertThat(rewrite("^/api/(.*)$", "/v2/$1", "/other")).isNull();
    }

    /**
     * The path is encoded when it is set, so a literal brace reaches the wire as %7B; here the
     * filter hands over the decoded character.
     */
    @Test
    void anEscapedBraceIsALiteralBrace()
    {
        assertThat(rewrite("^/a$", "/a\\{b\\}", "/a")).isEqualTo("/a{b}");
        assertThat(errors("^/a$", "/a\\{b")).isEmpty();
    }

    @Test
    void theOldDollarBraceFormIsRefused()
    {
        assertThat(errors("^/(?<n>.*)$", "/x/${n}")).singleElement().asString().contains("Write a named group as {name}, not $${name}.");
    }

    @Test
    void aBraceNamingNoGroupIsRefused()
    {
        assertThat(errors("^/(?<a>.*)$", "/x/{b}")).singleElement().asString().contains("{b} needs a source pattern with that named group");
        assertThat(errors("^/(.*)$", "/x/{")).singleElement().asString().contains("Unterminated {");
        assertThat(errors("^/(.*)$", "/x/$2")).singleElement().asString().contains("$2 needs a source pattern with that many groups.");
    }
}
