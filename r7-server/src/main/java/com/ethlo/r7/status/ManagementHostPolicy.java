package com.ethlo.r7.status;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides whether a request's {@code Host} header may reach the management endpoint.
 * <p>
 * The endpoint has no authentication and is bound to loopback by default, which a browser on the
 * same machine can still be made to reach through DNS rebinding: a page on
 * {@code attacker.example} re-resolves that name to {@code 127.0.0.1}, and its requests are then
 * same-origin with the dashboard and can read it. Such a request carries the attacker's name in
 * {@code Host}, so only names the operator expects are accepted - {@code localhost}, the bind host
 * and {@code management.allowed_hosts}. An IP literal is always accepted: rebinding needs a name
 * the attacker controls, and a browser that was given an address sends that address.
 */
public final class ManagementHostPolicy
{
    private static final Pattern IPV4 = Pattern.compile("(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");

    // Only hex digits, colons and dots (an embedded IPv4) can appear between the brackets, so no
    // registrable name can pass as an IPv6 literal.
    private static final Pattern IPV6 = Pattern.compile("\\[[0-9a-fA-F:.]+]");

    private final Set<String> allowedNames;

    public ManagementHostPolicy(final String bindHost, final List<String> allowedHosts)
    {
        this.allowedNames = new HashSet<>();
        this.allowedNames.add("localhost");
        if (bindHost != null)
        {
            this.allowedNames.add(normalize(bindHost));
        }
        for (final String allowedHost : allowedHosts)
        {
            this.allowedNames.add(normalize(allowedHost));
        }
    }

    /**
     * @param hostHeader the request's {@code Host} header, or null when it sent none. A browser
     *                   always sends one, so a request without it is not a rebinding attempt.
     */
    public boolean allows(final String hostHeader)
    {
        if (hostHeader == null)
        {
            return true;
        }
        final String host = normalize(stripPort(hostHeader.strip()));
        return IPV4.matcher(host).matches() || IPV6.matcher(host).matches() || this.allowedNames.contains(host);
    }

    private static String stripPort(final String hostHeader)
    {
        if (hostHeader.startsWith("["))
        {
            final int close = hostHeader.indexOf(']');
            return close < 0 ? hostHeader : hostHeader.substring(0, close + 1);
        }
        final int colon = hostHeader.indexOf(':');
        return colon < 0 ? hostHeader : hostHeader.substring(0, colon);
    }

    /**
     * Lower case, without the one trailing dot a fully qualified name may carry.
     */
    private static String normalize(final String host)
    {
        final String lower = host.strip().toLowerCase(Locale.ROOT);
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }
}
