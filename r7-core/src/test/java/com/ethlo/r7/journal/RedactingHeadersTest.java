package com.ethlo.r7.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import com.ethlo.r7.util.RedactUtil;

class RedactingHeadersTest
{
    private static final HeaderNameSet SAFE = HeaderNameSet.of(List.of("user-agent", "accept", "if-match"));

    @Test
    void safeValuesAreKeptAndUnsafeOnesAreFingerprinted()
    {
        final GatewayHeaders redacted = new RedactingHeaders(headers(
                "user-agent", "Mozilla/5.0",
                "cookie", "session=secret"), SAFE);

        assertThat(collect(redacted)).containsExactly(
                "user-agent=Mozilla/5.0",
                "cookie=" + RedactUtil.fingerprint("session=secret"));
    }

    @Test
    void theSafeListIsMatchedRegardlessOfTheCaseTheHeaderArrivedIn()
    {
        final GatewayHeaders redacted = new RedactingHeaders(headers(
                "User-Agent", "Mozilla/5.0",
                "ACCEPT", "text/html"), SAFE);

        assertThat(collect(redacted)).containsExactly(
                "User-Agent=Mozilla/5.0",
                "ACCEPT=text/html");
    }

    /**
     * The lookup this replaced lower-cased with the default locale, where {@code I} folds to
     * {@code ı} rather than {@code i} — so under a Turkish locale a safe header stopped being
     * recognised and its value was fingerprinted instead of kept.
     */
    @Test
    void theSafeListDoesNotDependOnTheDefaultLocale()
    {
        final Locale original = Locale.getDefault();
        try
        {
            Locale.setDefault(Locale.of("tr", "TR"));
            final GatewayHeaders redacted = new RedactingHeaders(headers("IF-MATCH", "\"etag\""), SAFE);
            assertThat(collect(redacted)).containsExactly("IF-MATCH=\"etag\"");
        }
        finally
        {
            Locale.setDefault(original);
        }
    }

    @Test
    void lookupsAreRedactedToo()
    {
        final GatewayHeaders redacted = new RedactingHeaders(headers(
                "user-agent", "Mozilla/5.0",
                "authorization", "Bearer token"), SAFE);

        assertThat(redacted.getFirst("user-agent")).isEqualTo("Mozilla/5.0");
        assertThat(redacted.getFirst("authorization")).isEqualTo(RedactUtil.fingerprint("Bearer token"));
        assertThat(redacted.getAll("authorization")).containsExactly(RedactUtil.fingerprint("Bearer token"));
        assertThat(redacted.getFirst("absent")).isNull();
    }

    @Test
    void everyValueOfAMultiValuedUnsafeHeaderIsFingerprinted()
    {
        final MutableFastGatewayHeaders source = new MutableFastGatewayHeaders();
        source.add("cookie", "a=1");
        source.add("cookie", "b=2");

        assertThat(collect(new RedactingHeaders(source, SAFE))).containsExactly(
                "cookie=" + RedactUtil.fingerprint("a=1"),
                "cookie=" + RedactUtil.fingerprint("b=2"));
    }

    @Test
    void aNameOutsideAsciiIsTreatedAsUnsafeRatherThanMatchingAnything()
    {
        final HeaderNameSet safe = HeaderNameSet.of(List.of("accept"));
        assertThat(safe.contains("accept")).isTrue();
        assertThat(safe.contains("ACCEPT")).isTrue();
        assertThat(safe.contains("accept́")).isFalse();
        assertThat(safe.contains("acce")).isFalse();
        assertThat(safe.contains("accepts")).isFalse();
        assertThat(safe.contains(null)).isFalse();
    }

    @Test
    void theSetReportsEveryNameItWasBuiltFrom()
    {
        assertThat(SAFE.names()).containsExactlyInAnyOrder("user-agent", "accept", "if-match");
    }

    private static GatewayHeaders headers(final String... namesAndValues)
    {
        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        for (int i = 0; i < namesAndValues.length; i += 2)
        {
            headers.add(namesAndValues[i], namesAndValues[i + 1]);
        }
        return headers;
    }

    private static List<String> collect(final GatewayHeaders headers)
    {
        final List<String> out = new ArrayList<>();
        final int count = headers.forEach((name, value) -> out.add(name + "=" + value));
        assertThat(count).isEqualTo(out.size());
        return out;
    }
}
