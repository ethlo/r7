package com.ethlo.r7.filters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.MutableGatewayAttributes;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import com.ethlo.r7.util.ShortCircuitGatewayResponse;
import com.ethlo.r7.util.constants.HttpHeaders;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.r7.validation.ValidationResult;

class BasicAuthFactoryTest
{
    /**
     * Produced by {@code htpasswd -nbBC 4 alice secret}, so the fixture is external ground truth
     * rather than whatever this codebase happens to compute. Cost 4 keeps the suite fast.
     */
    private static final String ALICE = "alice:$2y$04$agcM9nDVmZGTJPT.ldejs.zoYitvQGSKw4FIG2Bt9bpsYf89eaeLG";

    /**
     * {@code htpasswd -nbBC 5 bob 'p4ssw0rd!'}
     */
    private static final String BOB = "bob:$2y$05$wbODsbwkzHjWDgCS7gUvlunDRPVKM/SkpFGNsu.q5.s9HSJJRB5hi";

    /**
     * {@code htpasswd -nbBC 4 carol 'pa:ss:word'} - the password itself contains the separator,
     * which is legal per RFC 7617: only the username is colon-free.
     */
    private static final String CAROL = "carol:$2y$04$Tj3RYx8ExzaiQ1.wz5WN1.mT5Fd9JMPr68pXKVerAPZtYN1tCc4.O";

    private final BasicAuthFactory factory = new BasicAuthFactory();

    @Test
    void credentialsFromHtpasswdVerifyAndTheUserIsRecorded()
    {
        final ClientRequestGatewayExchange exchange = exchange("Basic " + encode("alice:secret"));

        filter(ALICE, BOB).onClientRequest(exchange);

        verify(exchange, never()).shortCircuit(any());
        verify(exchange.attributes()).set(BasicAuthFactory.AUTHENTICATED_USER_KEY, "alice");
    }

    /**
     * The split must be on the <em>first</em> colon only. Splitting on the last, or with
     * {@code String.split(":")}, would hand bcrypt the truncated "pa" and reject a valid user.
     */
    @Test
    void aPasswordContainingTheSeparatorIsNotTruncated()
    {
        final ClientRequestGatewayExchange exchange = exchange("Basic " + encode("carol:pa:ss:word"));

        filter(ALICE, CAROL).onClientRequest(exchange);

        verify(exchange, never()).shortCircuit(any());
        verify(exchange.attributes()).set(BasicAuthFactory.AUTHENTICATED_USER_KEY, "carol");
    }

    /**
     * The mirror of the above: only the full password authenticates, so a split on the last colon
     * (or any other mangling that happens to pass the test above) still fails here.
     */
    @Test
    void aPrefixOfASeparatorContainingPasswordIsRejected()
    {
        final ClientRequestGatewayExchange exchange = exchange("Basic " + encode("carol:pa"));

        filter(ALICE, CAROL).onClientRequest(exchange);

        verify(exchange).shortCircuit(any());
        verify(exchange.attributes(), never()).set(any(String.class), any(String.class));
    }

    /**
     * The scheme token is case-insensitive per RFC 7235. Comparing it by lowercasing the header is
     * what this guards against: {@code toLowerCase()} without a locale maps 'I' to a dotless 'i'
     * under a Turkish default locale, which would silently reject every request.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Basic ", "basic ", "BASIC ", "BaSiC "})
    void theSchemeIsMatchedCaseInsensitively(final String scheme)
    {
        final ClientRequestGatewayExchange exchange = exchange(scheme + encode("alice:secret"));

        filter(ALICE).onClientRequest(exchange);

        verify(exchange, never()).shortCircuit(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "alice:wrong",
            "alice:",
            "carol:secret",
            "alice",
            ":secret",
            "ALICE:secret"
    })
    void badCredentialsAreRejected(final String credentials)
    {
        assertRejected(exchange("Basic " + encode(credentials)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "MISSING",
            "Basic ",
            "Basic !!!not-base64!!!",
            "Bearer abcdef",
            "Basicalice"
    })
    void malformedAuthorizationHeadersAreRejected(final String header)
    {
        assertRejected(exchange("MISSING".equals(header) ? null : header));
    }

    /**
     * A backslash must be escaped too, and before the quotes. A realm ending in one would otherwise
     * escape the closing quote and let a client parse the rest of the header as further
     * auth-params; an interior one is silently swallowed as a quoted-pair.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "My \"Realm\"      | Basic realm=\"My \\\"Realm\\\"\"",
            "back\\slash       | Basic realm=\"back\\\\slash\"",
            "trailing\\        | Basic realm=\"trailing\\\\\"",
            "both\\and\"quote  | Basic realm=\"both\\\\and\\\"quote\""
    })
    void theChallengeEscapesQuotedStringMetacharacters(final String realm, final String expected)
    {
        final ClientRequestGatewayExchange exchange = exchange(null);

        factory.create(new BasicAuthFactory.Config(List.of(ALICE), realm.strip()), null)
                .onClientRequest(exchange);

        assertThat(captureRejection(exchange).headers().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo(expected.strip());
    }

    /**
     * A CR or LF would split the 401 response, and anything above ISO-8859-1 cannot be encoded in a
     * header at all. Both are refused at startup rather than on every rejected request.
     */
    @ParameterizedTest
    @ValueSource(strings = {"bad\rrealm", "bad\nrealm", "bad\u0000realm", "bad\u007Frealm", "caf\u4E2D"})
    void aRealmThatCannotBeSentInAHeaderIsRejected(final String realm)
    {
        final ValidationResult result = new ValidationResult();

        new BasicAuthFactory.Config(List.of(ALICE), realm).validate(result);

        assertThat(result.hasErrors()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"My \"Realm\"", "back\\slash", "trailing\\", "Se\u00f1ores", "with\ttab"})
    void aRealmThatCanBeSentInAHeaderIsAccepted(final String realm)
    {
        final ValidationResult result = new ValidationResult();

        new BasicAuthFactory.Config(List.of(ALICE), realm).validate(result);

        assertThat(result.hasErrors()).isFalse();
    }

    /**
     * bcrypt costs tens of milliseconds by design, which is far too long to hold an XNIO I/O thread.
     */
    @Test
    void verificationIsDispatchedOffTheIoThread()
    {
        assertThat(filter(ALICE).requiresDispatch()).isTrue();
    }

    @Test
    void repeatedRequestsWithTheSameCredentialsKeepAuthenticating()
    {
        final ClientRequestGatewayFilter filter = filter(ALICE);

        for (int i = 0; i < 3; i++)
        {
            final ClientRequestGatewayExchange exchange = exchange("Basic " + encode("alice:secret"));
            filter.onClientRequest(exchange);
            verify(exchange, never()).shortCircuit(any());
            verify(exchange.attributes()).set(BasicAuthFactory.AUTHENTICATED_USER_KEY, "alice");
        }
    }

    @Test
    void aWellFormedUserListValidates()
    {
        assertThat(validate(List.of(ALICE, BOB))).isEmpty();
    }

    @Test
    void anEmptyUserListIsRejected()
    {
        assertThat(validate(List.of())).anyMatch(error -> error.contains("users"));
        assertThat(validate(null)).anyMatch(error -> error.contains("users"));
    }

    /**
     * Every one of these used to be dropped silently, so an operator typo locked a user out with no
     * diagnostic at all, and a non-bcrypt hash threw on every single request instead of once at
     * startup.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "alice",
            "alice:",
            ":$2y$04$agcM9nDVmZGTJPT.ldejs.zoYitvQGSKw4FIG2Bt9bpsYf89eaeLG",
            "alice:plaintext-password",
            "alice:$apr1$lZL6V/ki$eIMz/iKDkbtys/uptKgVw.",
            "   "
    })
    void malformedUserEntriesAreReportedAtStartup(final String entry)
    {
        assertThat(validate(List.of(entry))).anyMatch(error -> error.contains("users[0]"));
    }

    @Test
    void duplicateUsernamesAreReportedRatherThanSilentlyOverwritten()
    {
        assertThat(validate(List.of(ALICE, ALICE))).anyMatch(error -> error.contains("duplicate username"));
    }

    @Test
    void aValidationErrorNeverEchoesTheHash()
    {
        assertThat(validate(List.of("alice:plaintext-password")))
                .noneMatch(error -> error.contains("plaintext-password"));
    }

    @Test
    void theRealmDefaultsWhenAbsentOrBlank()
    {
        assertThat(new BasicAuthFactory.Config(List.of(), null).realm()).isEqualTo("Secure Area");
        assertThat(new BasicAuthFactory.Config(List.of(), "  ").realm()).isEqualTo("Secure Area");
        assertThat(new BasicAuthFactory.Config(List.of(), "Admin").realm()).isEqualTo("Admin");
    }

    private List<String> validate(final List<String> users)
    {
        final ValidationResult result = new ValidationResult();
        new BasicAuthFactory.Config(users, null).validate(result);
        return result.getErrors();
    }

    private ClientRequestGatewayFilter filter(final String... users)
    {
        return factory.create(new BasicAuthFactory.Config(List.of(users), null), null);
    }

    private void assertRejected(final ClientRequestGatewayExchange exchange)
    {
        filter(ALICE, BOB).onClientRequest(exchange);

        assertThat(captureRejection(exchange).status()).isEqualTo(HttpStatuses.UNAUTHORIZED);
        verify(exchange.attributes(), never()).set(any(), any(String.class));
    }

    private ShortCircuitGatewayResponse captureRejection(final ClientRequestGatewayExchange exchange)
    {
        final ArgumentCaptor<ShortCircuitGatewayResponse> captor = ArgumentCaptor.forClass(ShortCircuitGatewayResponse.class);
        verify(exchange).shortCircuit(captor.capture());
        return captor.getValue();
    }

    private static String encode(final String credentials)
    {
        return Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static ClientRequestGatewayExchange exchange(final String authorizationHeader)
    {
        final MutableGatewayHeaders headers = new MutableFastGatewayHeaders();
        if (authorizationHeader != null)
        {
            headers.set(HttpHeaders.AUTHORIZATION, authorizationHeader);
        }

        final GatewayRequest request = mock(GatewayRequest.class);
        when(request.headers()).thenReturn(headers);

        final ClientRequestGatewayExchange exchange = mock(ClientRequestGatewayExchange.class);
        when(exchange.clientRequest()).thenReturn(request);
        when(exchange.attributes()).thenReturn(mock(MutableGatewayAttributes.class));
        return exchange;
    }
}
