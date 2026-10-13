package com.ethlo.r7.filters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.MutableGatewayRequest;
import com.ethlo.r7.api.MutableQueryParams;
import com.ethlo.r7.util.ShortCircuitGatewayResponse;
import com.ethlo.r7.util.constants.HttpStatuses;
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

    /**
     * Query parameters as a list of pairs, with the replace-every-occurrence rule of the server's.
     */
    private static final class Params implements MutableQueryParams
    {
        final List<String[]> pairs = new ArrayList<>();

        Params(final String... namesAndValues)
        {
            for (int i = 0; i < namesAndValues.length; i += 2)
            {
                this.pairs.add(new String[]{namesAndValues[i], namesAndValues[i + 1]});
            }
        }

        @Override
        public void set(final String name, final String value)
        {
            this.remove(name);
            this.add(name, value);
        }

        @Override
        public void add(final String name, final String value)
        {
            this.pairs.add(new String[]{name, value});
        }

        @Override
        public void remove(final String name)
        {
            this.pairs.removeIf(pair -> pair[0].equals(name));
        }

        @Override
        public String getFirst(final String name)
        {
            return this.pairs.stream().filter(pair -> pair[0].equals(name)).map(pair -> pair[1]).findFirst().orElse(null);
        }

        @Override
        public Iterable<String> getAll(final String name)
        {
            return this.pairs.stream().filter(pair -> pair[0].equals(name)).map(pair -> pair[1]).toList();
        }

        @Override
        public String toQueryString()
        {
            return this.pairs.stream().map(pair -> pair[0] + "=" + pair[1]).collect(java.util.stream.Collectors.joining("&"));
        }
    }

    /**
     * Path and query the upstream would get, or the status the rewrite answered with.
     */
    private record Rewritten(String path, String query, Integer refusedWith, List<String[]> params)
    {
    }

    private static Rewritten rewriteQuery(final String regexp, final String replacement, final String path, final String... clientQuery)
    {
        final Params client = new Params(clientQuery);
        final GatewayRequest request = mock(GatewayRequest.class);
        when(request.path()).thenReturn(path);
        when(request.queryParams()).thenReturn(client);
        final Params upstreamParams = new Params(clientQuery);
        final MutableGatewayRequest upstream = mock(MutableGatewayRequest.class);
        when(upstream.queryParams()).thenReturn(upstreamParams);
        final UpstreamRequestGatewayExchange exchange = mock(UpstreamRequestGatewayExchange.class);
        when(exchange.clientRequest()).thenReturn(request);
        when(exchange.upstreamRequest()).thenReturn(upstream);

        new RewritePathFactory().create(new RewritePathFactory.Config(regexp, replacement), null).onUpstreamRequest(exchange);

        final ArgumentCaptor<String> newPath = ArgumentCaptor.forClass(String.class);
        verify(upstream, org.mockito.Mockito.atMost(1)).path(newPath.capture());
        final ArgumentCaptor<ShortCircuitGatewayResponse> refusal = ArgumentCaptor.forClass(ShortCircuitGatewayResponse.class);
        verify(exchange, org.mockito.Mockito.atMost(1)).shortCircuit(refusal.capture());
        return new Rewritten(newPath.getAllValues().isEmpty() ? null : newPath.getValue(), upstreamParams.toQueryString(),
                refusal.getAllValues().isEmpty() ? null : refusal.getValue().status(), upstreamParams.pairs);
    }

    @Test
    void aQuestionMarkStartsTheQuery()
    {
        final Rewritten out = rewriteQuery("^/old/(.*)$", "/new/$1?a=1&b=2", "/old/x");
        assertThat(out.path()).isEqualTo("/new/x");
        assertThat(out.query()).isEqualTo("a=1&b=2");
    }

    @Test
    void anEscapedQuestionMarkStaysInThePath()
    {
        final Rewritten out = rewriteQuery("^/old$", "/new\\?x", "/old");
        assertThat(out.path()).isEqualTo("/new?x");
        assertThat(out.query()).isEmpty();
    }

    /**
     * ?uid=123&uid=456 rewritten to ?uid={query.refid} reaches the upstream as one uid, and the
     * client's other parameters keep their occurrences and order.
     */
    @Test
    void aParameterTheRewriteSetsReplacesEveryOccurrenceTheClientSent()
    {
        final Rewritten out = rewriteQuery("^/old$", "/new?uid={query.refid}", "/old", "x", "1", "uid", "123", "y", "2", "uid", "456", "x", "3", "refid", "R");
        assertThat(out.query()).isEqualTo("x=1&y=2&x=3&refid=R&uid=R");
    }

    /**
     * Request text is a value: neither a capture nor a query value can add a parameter or turn
     * one into another.
     */
    @Test
    void requestTextCanNotAddAParameter()
    {
        final Rewritten captured = rewriteQuery("^/old/(.*)$", "/new?q=$1", "/old/a&admin=true");
        assertThat(captured.params()).hasSize(1);
        assertThat(captured.params().get(0)).containsExactly("q", "a&admin=true");
        assertThat(captured.path()).isEqualTo("/new");

        final Rewritten value = rewriteQuery("^/old$", "/new?q={query.v}", "/old", "v", "1&admin=true");
        assertThat(value.params()).hasSize(2);
        assertThat(value.params().get(1)).containsExactly("q", "1&admin=true");
    }

    @Test
    void whatFollowsTheMatchIsTheEndOfTheLastValue()
    {
        final Rewritten out = rewriteQuery("^/old", "/new?from=", "/old/a&b");
        assertThat(out.query()).isEqualTo("from=/a&b");
    }

    @Test
    void aMissingOrAmbiguousQueryValueIsRefused()
    {
        assertThat(rewriteQuery("^/old$", "/new?u={query.refid}", "/old").refusedWith()).isEqualTo(HttpStatuses.BAD_REQUEST);
        assertThat(rewriteQuery("^/old$", "/new?u={query.refid}", "/old", "refid", "1", "refid", "2").refusedWith()).isEqualTo(HttpStatuses.BAD_REQUEST);
        assertThat(rewriteQuery("^/old$", "/new?u={query.refid}", "/old", "refid", "1", "refid", "1").refusedWith()).isNull();
    }

    @Test
    void aLiteralBraceInTheQueryIsADecodedCharacter()
    {
        assertThat(rewriteQuery("^/old$", "/new?v=\\{1\\}", "/old").query()).isEqualTo("v={1}");
    }

    @Test
    void aQueryValueInThePathIsResolved()
    {
        assertThat(rewriteQuery("^/old$", "/new/{query.id}", "/old", "id", "7").path()).isEqualTo("/new/7");
        assertThat(rewriteQuery("^/old$", "/new/{query.id}", "/old").refusedWith()).isEqualTo(HttpStatuses.BAD_REQUEST);
    }

    @Test
    void theLastMatchingRewriteDoesNotInheritAnEarlierOnesQuery()
    {
        final Params client = new Params("keep", "1");
        final GatewayRequest request = mock(GatewayRequest.class);
        when(request.path()).thenReturn("/old");
        when(request.queryParams()).thenReturn(client);
        final Params upstreamParams = new Params("keep", "1");
        final MutableGatewayRequest upstream = mock(MutableGatewayRequest.class);
        when(upstream.queryParams()).thenReturn(upstreamParams);
        final UpstreamRequestGatewayExchange exchange = mock(UpstreamRequestGatewayExchange.class);
        when(exchange.clientRequest()).thenReturn(request);
        when(exchange.upstreamRequest()).thenReturn(upstream);
        final java.util.Map<Object, Object> attachments = new java.util.HashMap<>();
        org.mockito.Mockito.doAnswer(call ->
        {
            attachments.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(exchange).setAttachment(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        when(exchange.getAttachment(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> attachments.get(call.getArgument(0)));

        new RewritePathFactory().create(new RewritePathFactory.Config("^/old$", "/a?a=1&keep=2"), null).onUpstreamRequest(exchange);
        new RewritePathFactory().create(new RewritePathFactory.Config("^/old$", "/b?b=2"), null).onUpstreamRequest(exchange);

        assertThat(upstreamParams.toQueryString()).isEqualTo("keep=1&b=2");
    }
}
