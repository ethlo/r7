package com.ethlo.r7.filters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.QueryParams;
import com.ethlo.r7.config.model.HttpStatus;
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
        final ShortCircuitGatewayResponse ok = redirect("^/x/(.*)$", "https://good.example/$1", "/x/p/q");
        assertThat(ok.status()).isEqualTo(HttpStatuses.FOUND);
        assertThat(ok.headers().getFirst(HttpHeaders.LOCATION)).isEqualTo("https://good.example/p/q");

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
    @CsvSource(delimiter = '|', value = {"https://$1/", "//$1.example.com/", "https://good.example$1", "https://$1@good.example/", "javascript:$1", "https:\\\\$1", "mailto:$1", "javascript://fixed.example/%0A$1", "///$1", "//\\\\$1", "$1://good.example/", "h$1://good.example/", "https:///$1", "' /x/$1'"})
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

    /**
     * Browsers strip leading whitespace from a Location before parsing it: a decoded %20 in
     * front of //evil must not get past the path-template check.
     */
    @Test
    void leadingWhitespaceCannotSmuggleAHost()
    {
        assertThat(redirect("^/go(.*)$", "$1", "/go //evil.example").status()).isEqualTo(HttpStatuses.BAD_REQUEST);
        assertThat(redirect("^/go(.*)$", "$1", "/go\t//evil.example").status()).isEqualTo(HttpStatuses.BAD_REQUEST);
    }

    /**
     * Refused at request time as well, for configs built without validation.
     */
    @Test
    void emptyAuthoritiesAndNonWebSchemesNeverRedirect()
    {
        assertThat(redirect("^/x/(.*)$", "///$1", "/x/evil.example").status()).isEqualTo(HttpStatuses.BAD_REQUEST);
        assertThat(redirect("^/x/(.*)$", "//\\\\$1", "/x/evil.example").status()).isEqualTo(HttpStatuses.BAD_REQUEST);
        assertThat(redirect("^/x/(.*)$", "javascript://fixed.example/%0A$1", "/x/alert(1)").status()).isEqualTo(HttpStatuses.BAD_REQUEST);
    }

    /**
     * "://" after the path has started is data, not a scheme: such a path template is valid, and
     * the request-time check keeps what it produces on site.
     */
    @Test
    void aSchemeSeparatorInThePathIsNotAScheme()
    {
        final ValidationResult result = new ValidationResult();
        new TemplateRedirectFactory.Config("^/go/(.*)$", "/go/$1://fixed", null).validate(result);
        assertThat(result.hasErrors()).isFalse();

        final ShortCircuitGatewayResponse response = redirect("^/go/(.*)$", "/go/$1://fixed", "/go/a");
        assertThat(response.status()).isEqualTo(HttpStatuses.FOUND);
        assertThat(response.headers().getFirst(HttpHeaders.LOCATION)).isEqualTo("/go/a://fixed");
    }

    private static String location(final String source, final String target, final String path)
    {
        final ShortCircuitGatewayResponse response = redirect(source, target, path);
        assertThat(response.status()).isEqualTo(HttpStatuses.FOUND);
        return response.headers().getFirst(HttpHeaders.LOCATION);
    }

    /**
     * The path arrives decoded, so a client's %3F / %23 is a real '?' / '#' here, and its %253F
     * a literal "%3F". Spliced in raw, either would start a query or fragment the template never
     * had (or be decoded into one by the next hop); encoded, it stays data in the path.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "/old/a?admin=true      | /new/a%3Fadmin=true",
            "/old/a#frag            | /new/a%23frag",
            "/old/a%3Fadmin=true    | /new/a%253Fadmin=true",
            "/old/a%23frag          | /new/a%2523frag",
            "/old/a b               | /new/a%20b",
            "/old/caf\u00e9         | /new/caf%C3%A9"
    })
    void aCaptureGroupCannotAddAQueryOrFragmentToAPath(final String path, final String expected)
    {
        final String location = location("^/old/(.*)$", "/new/$1", path);
        assertThat(location).isEqualTo(expected);
        assertThat(location).doesNotContain("?").doesNotContain("#");
    }

    @Test
    void anAbsoluteTargetKeepsItsPathQueryAndFragmentStructure()
    {
        final String location = location("^/x/(.*)$", "https://good.example/$1?keep=1#top", "/x/p?evil=1#other");
        assertThat(location).isEqualTo("https://good.example/p%3Fevil=1%23other?keep=1#top");
    }

    /**
     * After a literal '?' the group is a query value: '&', '=' and '#' must not add a parameter
     * or end the query.
     */
    @Test
    void aCaptureGroupInTheQueryStaysOneValue()
    {
        final String location = location("^/search/(.*)$", "/find?q=$1&page=1", "/search/x&admin=true#f");
        assertThat(location).isEqualTo("/find?q=x%26admin%3Dtrue%23f&page=1");

        final String injected = location("^/search/(.*)$", "/find?q=$1", "/search/x%26admin%3Dtrue");
        assertThat(injected).isEqualTo("/find?q=x%2526admin%253Dtrue");
    }

    @Test
    void aCaptureGroupInTheFragmentIsEncoded()
    {
        assertThat(location("^/doc/(.*)$", "/docs#$1", "/doc/a#b?c")).isEqualTo("/docs#a%23b%3Fc");
    }

    /**
     * The part of the path the source did not match is carried over, as replaceFirst does, and
     * encoded as well.
     */
    @Test
    void theUnmatchedRestOfThePathIsEncodedToo()
    {
        assertThat(location("/old", "/new", "/old/a?b")).isEqualTo("/new/a%3Fb");
        assertThat(location("/old", "/new?from=", "/old/a&b")).isEqualTo("/new?from=/a%26b");
    }

    @Test
    void escapesAndMultiDigitGroupsFollowMatcherSyntax()
    {
        assertThat(location("^/(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)(k)$", "/$11/$10/$1\\$1", "/abcdefghijk")).isEqualTo("/k/j/a$1");
        assertThat(location("^/(a)$", "/$10", "/a")).isEqualTo("/a0");
        assertThat(location("^/(?<name>.*)$", "/n/{name}", "/x?y")).isEqualTo("/n/x%3Fy");
    }

    /**
     * Text that is not a safe header value decoded - non-Latin-1, or whitespace at the end - is
     * still ordinary data once encoded, and must not turn a legitimate redirect into a 400.
     */
    @Test
    void textThatIsOnlyUnsafeDecodedIsRedirectedEncoded()
    {
        assertThat(location("^/old/(.*)$", "/new/$1", "/old/\u65e5\u672c")).isEqualTo("/new/%E6%97%A5%E6%9C%AC");
        assertThat(location("^/search/(.*)$", "/find?q=$1", "/search/x ")).isEqualTo("/find?q=x%20");
    }

    private static ShortCircuitGatewayResponse redirectWithQuery(final String source, final String target, final String path, final String... nameValues)
    {
        final GatewayRequest request = mock(GatewayRequest.class);
        when(request.path()).thenReturn(path);
        final QueryParams params = mock(QueryParams.class);
        when(params.getAll(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call ->
        {
            final List<String> values = new java.util.ArrayList<>();
            for (int i = 0; i < nameValues.length; i += 2)
            {
                if (nameValues[i].equals(call.getArgument(0)))
                {
                    values.add(nameValues[i + 1]);
                }
            }
            return values;
        });
        when(request.queryParams()).thenReturn(params);
        final ClientRequestGatewayExchange exchange = mock(ClientRequestGatewayExchange.class);
        when(exchange.clientRequest()).thenReturn(request);

        new TemplateRedirectFactory().create(new TemplateRedirectFactory.Config(source, target, null), null).onClientRequest(exchange);

        final ArgumentCaptor<ShortCircuitGatewayResponse> response = ArgumentCaptor.forClass(ShortCircuitGatewayResponse.class);
        verify(exchange).shortCircuit(response.capture());
        return response.getValue();
    }

    private static List<String> errors(final String source, final String target, final Integer status)
    {
        final ValidationResult result = new ValidationResult();
        new TemplateRedirectFactory.Config(source, target, status == null ? null : new HttpStatus(status)).validate(result);
        return result.getErrors();
    }

    @Test
    void aQueryValueIsEncodedForWhereItLands()
    {
        final ShortCircuitGatewayResponse response = redirectWithQuery(null, "/?uid=urn:example:{query.refid}", "/anything", "refid", "a&b?c");
        assertThat(response.status()).isEqualTo(HttpStatuses.FOUND);
        assertThat(response.headers().getFirst(HttpHeaders.LOCATION)).isEqualTo("/?uid=urn:example:a%26b%3Fc");
    }

    @Test
    void aQueryValueInThePathCannotLeaveIt()
    {
        final ShortCircuitGatewayResponse response = redirectWithQuery(null, "/go/{query.to}", "/x", "to", "a/b?c");
        assertThat(response.headers().getFirst(HttpHeaders.LOCATION)).isEqualTo("/go/a/b%3Fc");
    }

    @Test
    void aRepeatedQueryParameterIsRefusedUnlessEveryOccurrenceAgrees()
    {
        assertThat(redirectWithQuery(null, "/x/{query.id}", "/p", "id", "1", "id", "1").headers().getFirst(HttpHeaders.LOCATION)).isEqualTo("/x/1");
        assertThat(redirectWithQuery(null, "/x/{query.id}", "/p", "id", "1", "id", "2").status()).isEqualTo(HttpStatuses.BAD_REQUEST);
    }

    @Test
    void aMissingQueryParameterIsRefused()
    {
        assertThat(redirectWithQuery(null, "/x/{query.id}", "/p", "other", "1").status()).isEqualTo(HttpStatuses.BAD_REQUEST);
    }

    @Test
    void aQueryValueIsCombinedWithCaptureGroups()
    {
        final ShortCircuitGatewayResponse response = redirectWithQuery("^/old/(.*)$", "/new/$1?ref={query.r}", "/old/a", "r", "7");
        assertThat(response.headers().getFirst(HttpHeaders.LOCATION)).isEqualTo("/new/a?ref=7");
    }

    @Test
    void withoutASourceACaptureReferenceIsRefusedAtLoad()
    {
        assertThat(errors(null, "/x/$1", null)).singleElement().asString().contains("$1 needs a source pattern with that many groups.");
        assertThat(errors(null, "/x/{name}", null)).singleElement().asString().contains("{name} needs a source pattern with that named group");
        assertThat(errors(null, "/?uid={query.refid}", null)).isEmpty();
    }

    @Test
    void aQueryValueInTheOriginIsRefusedAtLoad()
    {
        assertThat(errors(null, "https://{query.h}.example/x", null)).singleElement().asString().contains("literal");
        assertThat(errors(null, "https://good.example/{query.h}", null)).isEmpty();
    }

    @Test
    void onlyRedirectStatusesAreAccepted()
    {
        for (final int ok : new int[]{301, 302, 303, 307, 308})
        {
            assertThat(errors(null, "/x", ok)).isEmpty();
        }
        for (final int bad : new int[]{200, 300, 304, 404})
        {
            assertThat(errors(null, "/x", bad)).singleElement().asString().contains("status", "301, 302, 303, 307 or 308");
        }
    }

    @Test
    void theOldDollarBraceFormIsRefusedWithItsReplacement()
    {
        assertThat(errors("^/(?<name>.*)$", "/n/${name}", null)).singleElement().asString().contains("Write a named group as {name}, not $${name}.");
    }

    @Test
    void aBraceNamingNoGroupOrNeverClosedIsRefusedAtLoad()
    {
        assertThat(errors("^/(?<a>.*)$", "/n/{b}", null)).singleElement().asString().contains("{b} needs a source pattern with that named group");
        assertThat(errors("^/(.*)$", "/n/{", null)).singleElement().asString().contains("Unterminated {");
    }

    /**
     * A literal brace is a character of the result, not URI syntax, and r7 never emits a raw one.
     */
    @Test
    void aLiteralBraceIsPercentEncodedInThePathAndInTheQuery()
    {
        assertThat(location("^/a$", "/a\\{b", "/a")).isEqualTo("/a%7Bb");
        assertThat(location("^/a$", "/a\\{b\\}", "/a")).isEqualTo("/a%7Bb%7D");
        assertThat(location("^/a$", "/x?v=\\{1\\}", "/a")).isEqualTo("/x?v=%7B1%7D");
        assertThat(location("^/a$", "/x}", "/a")).isEqualTo("/x%7D");
        assertThat(errors("^/a$", "/a\\{b", null)).isEmpty();
    }

    @Test
    void aNamedGroupInBracesIsEncodedForWhereItLands()
    {
        assertThat(location("^/(?<rest>.*)$", "/n/{rest}", "/x?y")).isEqualTo("/n/x%3Fy");
        assertThat(location("^/(?<rest>.*)$", "/n?r={rest}", "/x&y")).isEqualTo("/n?r=x%26y");
    }
}
