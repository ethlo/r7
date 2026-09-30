package com.ethlo.r7.undertow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.server.UpstreamHeaderSanitizer;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import io.undertow.util.HttpString;

/**
 * The server-neutral {@link UpstreamHeaderSanitizer} must do exactly what the {@code HeaderMap}
 * version did when it ran in production, on Undertow's own header view - the one the proxy copies
 * upstream. Header sets are generated rather than hand-picked, from a vocabulary chosen to reach
 * every rule: hop-by-hop names, Connection lists naming other headers (protected ones included, with
 * optional whitespace and empty entries), TE variants, websocket and other upgrades, and the
 * forwarding family in random casing with hyphen and underscore separators.
 */
class UpstreamHeaderSanitizerEquivalenceTest
{
    private static final int CASES = 20_000;

    private static final String[] NAMES = {
            "Connection", "Keep-Alive", "Proxy-Connection", "Proxy-Authorization", "TE", "Upgrade",
            "Host", "Content-Length", "Transfer-Encoding", "Authorization", "Accept", "Cookie",
            "Forwarded", "X-Real-IP", "X-Client-IP", "True-Client-IP", "X-Cluster-Client-IP",
            "X-Original-URL", "X-Rewrite-URL",
            "X-Forwarded-For", "X-Forwarded-Proto", "X-Forwarded-Host", "X-Forwarded-User", "X-Forwarded-",
            "X-Forwardedx", "X-Forward-For", "XX-Forwarded-For", "X-Custom", "X-Other"
    };

    private static final String[] CONNECTION_TOKENS = {
            "keep-alive", "close", "upgrade", "Upgrade", "X-Custom", "X-Other", "X-Forwarded-For", "Host",
            "content-length", "Transfer-Encoding", "Accept", "TE", "Connection", "", " "
    };

    private static final String[] UPGRADES = {"websocket", "WebSocket", "h2c", "websocket, h2c"};
    private static final String[] TES = {"trailers", "Trailers ", "gzip", "trailers, gzip", ""};

    @Test
    void doesExactlyWhatTheHeaderMapVersionDid()
    {
        final Random random = new Random(20260930L);
        for (int i = 0; i < CASES; i++)
        {
            final List<String[]> lines = randomLines(random);
            final boolean trusted = random.nextBoolean();

            final HeaderMap expected = toHeaderMap(lines);
            LegacyHeaderMapSanitizer.sanitize(expected, trusted);

            final HeaderMap actual = toHeaderMap(lines);
            UpstreamHeaderSanitizer.sanitize(new UndertowGatewayHeaders(actual), trusted);

            assertThat(render(actual))
                    .as("case %d, trusted=%s, input %s", i, trusted, render(toHeaderMap(lines)))
                    .isEqualTo(render(expected));
        }
    }

    private static List<String[]> randomLines(final Random random)
    {
        final List<String[]> lines = new ArrayList<>();
        final int count = random.nextInt(12);
        for (int n = 0; n < count; n++)
        {
            final String name = spell(random, NAMES[random.nextInt(NAMES.length)]);
            lines.add(new String[]{name, valueFor(random, name)});
        }
        return lines;
    }

    private static String valueFor(final Random random, final String name)
    {
        if (name.equalsIgnoreCase("Connection"))
        {
            final StringBuilder list = new StringBuilder();
            final int tokens = 1 + random.nextInt(4);
            for (int t = 0; t < tokens; t++)
            {
                if (t > 0)
                {
                    list.append(random.nextBoolean() ? "," : " ,\t");
                }
                list.append(spell(random, CONNECTION_TOKENS[random.nextInt(CONNECTION_TOKENS.length)]));
            }
            return list.toString();
        }
        if (name.equalsIgnoreCase("Upgrade"))
        {
            return UPGRADES[random.nextInt(UPGRADES.length)];
        }
        if (name.equalsIgnoreCase("TE"))
        {
            return TES[random.nextInt(TES.length)];
        }
        return "v" + random.nextInt(100);
    }

    /**
     * The name in random letter case, and for the forwarding family, each separator
     * independently as {@code -} or {@code _}.
     */
    private static String spell(final Random random, final String name)
    {
        final StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++)
        {
            char c = name.charAt(i);
            if (Character.isLetter(c))
            {
                c = random.nextBoolean() ? Character.toUpperCase(c) : Character.toLowerCase(c);
            }
            else if (c == '-' && name.regionMatches(true, 0, "X-Forward", 0, 9) && random.nextInt(3) == 0)
            {
                c = '_';
            }
            out.append(c);
        }
        return out.toString();
    }

    private static HeaderMap toHeaderMap(final List<String[]> lines)
    {
        final HeaderMap map = new HeaderMap();
        for (final String[] line : lines)
        {
            map.add(new HttpString(line[0]), line[1]);
        }
        return map;
    }

    /**
     * Every remaining field-line, in the map's order, with the name as stored. Order and spelling
     * are part of what must match: the proxy copies the map as it is.
     */
    private static List<String> render(final HeaderMap map)
    {
        final List<String> out = new ArrayList<>();
        for (final HeaderValues values : map)
        {
            for (final String value : values)
            {
                out.add(values.getHeaderName() + "=" + value);
            }
        }
        return out;
    }
}
