package com.ethlo.r7.filters;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.QueryParams;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.api.TextValues;
import com.ethlo.r7.config.model.HttpStatus;
import com.ethlo.r7.doc.DefaultValue;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.doc.Nullable;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.ShortCircuitGatewayResponse;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import com.ethlo.r7.util.PathEncoder;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.util.constants.HttpHeaders;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.r7.util.constants.MediaTypes;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.ethlo.r7.util.RegexBudget;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayFilterFactory.class)
@Description("Redirects requests based on a path pattern match using a template for the target URL.")
public final class TemplateRedirectFactory implements GatewayFilterFactory<TemplateRedirectFactory.Config>
{
    private static final String FILTER_NAME = "TemplateRedirect";
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final Pattern NO_GROUPS = Pattern.compile("");
    private static final String QUERY_PREFIX = "{query.";
    private static final ByteBuffer EMPTY_BODY = ByteBuffer.allocateDirect(0);

    @Override
    public String name()
    {
        return FILTER_NAME;
    }

    @Override
    public Class<Config> configClass()
    {
        return Config.class;
    }

    @Override
    public ClientRequestGatewayFilter create(final Config config, final FilterCreationContext filterCreationContext)
    {
        return new GF(config);
    }

    public record Config(
            @Description("The regular expression pattern to match against the request path. Left out, the filter answers every request it sees, and the target can use only {query.NAME} placeholders.")
            @Nullable
            String source,

            @Description("The replacement template for the target URL (e.g., /new/$1). Write a named group as $${name}: ${...} alone is environment variable interpolation. {query.NAME} is the value of a query parameter.")
            String target,

            @Description("The HTTP status code to return for the redirect: 301, 302, 303, 307 or 308.")
            @DefaultValue("302")
            HttpStatus status) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            final ValidatorUtils validator = new ValidatorUtils(result)
                    .notBlank("target", this.target())
                    .safeHeaderText("target", this.target())
                    .validRegexReplacement("target", this.source(), this.target());
            if (this.source() != null)
            {
                validator.requiredRegexp("source", this.source());
            }

            if (this.status() != null && this.status().code() != null && !REDIRECT_STATUSES.contains(this.status().code()))
            {
                validator.invalid("status", this.status().code(), "must be a redirect status: 301, 302, 303, 307 or 308");
            }

            final String target = this.target();
            if (target == null)
            {
                return;
            }
            if (this.source() == null || this.source().isBlank())
            {
                try
                {
                    parse(target, NO_GROUPS);
                }
                catch (final IllegalArgumentException e)
                {
                    validator.invalid("target", target, e.getMessage());
                }
            }
            final int schemeSeparator = target.indexOf("://");
            if (!target.isEmpty() && (isAsciiWhitespace(target.charAt(0)) || isAsciiWhitespace(target.charAt(target.length() - 1))))
            {
                validator.invalid("target", target, "a redirect target must not start or end with whitespace, which browsers strip before following it");
            }
            else if (schemeSeparator > 0 && isInSchemePosition(target, schemeSeparator) && hasPlaceholder(target.substring(0, schemeSeparator)))
            {
                validator.invalid("target", target, "the scheme of a redirect target must be literal: "
                        + "a capture group or query value there lets any request choose where it is redirected to");
            }
            else if (originEnd(target) == NO_AUTHORITY)
            {
                validator.invalid("target", target, "a redirect target must be a path, //host... or http(s)://host...: "
                        + "other schemes (javascript:, data:), a scheme without '//' and a host, or an empty host are not a safe origin");
            }
            else if (originEnd(target) >= 0 && hasPlaceholder(target.substring(0, originEnd(target))))
            {
                validator.invalid("target", target, "the scheme and host of a redirect target must be literal: "
                        + "a capture group or query value there lets any request choose where it is redirected to");
            }
        }
    }

    private static boolean hasPlaceholder(final String text)
    {
        return text.indexOf('$') >= 0 || text.contains(QUERY_PREFIX);
    }

    /**
     * {@link #originEnd} of a value that starts with a scheme but not {@code scheme://}: no host
     * that could be kept, and forms like {@code https:\\evil} or {@code javascript:} that
     * browsers act on.
     */
    static final int NO_AUTHORITY = -2;

    /**
     * End of the origin ({@code http(s)://authority} or {@code //authority}) at the start of a
     * target, -1 when it has none - that is, when it is a path - or {@link #NO_AUTHORITY} when it
     * has one that cannot be kept: a scheme other than http or https ({@code javascript://} can
     * still run script), a scheme without {@code //}, or an empty authority ({@code ///x} and
     * {@code //\x} are read by browsers as a host of their own).
     */
    static int originEnd(final String target)
    {
        final int start;
        if (target.startsWith("//"))
        {
            start = 2;
        }
        else
        {
            final int schemeEnd = schemeEnd(target);
            if (schemeEnd < 0)
            {
                return -1;
            }
            final boolean web = target.regionMatches(true, 0, "http", 0, schemeEnd) && schemeEnd == 4
                    || target.regionMatches(true, 0, "https", 0, schemeEnd) && schemeEnd == 5;
            if (!web || !target.startsWith("//", schemeEnd + 1))
            {
                return NO_AUTHORITY;
            }
            start = schemeEnd + 3;
        }
        int end = target.length();
        for (int i = start; i < target.length(); i++)
        {
            final char c = target.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == '\\')
            {
                end = i;
                break;
            }
        }
        return end > start ? end : NO_AUTHORITY;
    }

    /**
     * Whether "://" at {@code separator} ends a scheme, rather than appearing later in a path,
     * query or fragment ({@code /go/$1://fixed} is a path).
     */
    private static boolean isInSchemePosition(final String target, final int separator)
    {
        for (int i = 0; i < separator; i++)
        {
            final char c = target.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == '\\')
            {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiWhitespace(final char c)
    {
        return c == ' ' || c == '\t' || c == '\n' || c == '\f' || c == '\r';
    }

    /**
     * Index of the ':' ending a leading RFC 3986 scheme, or -1.
     */
    private static int schemeEnd(final String value)
    {
        if (value.isEmpty() || !Character.isLetter(value.charAt(0)) || value.charAt(0) > 0x7F)
        {
            return -1;
        }
        for (int i = 1; i < value.length(); i++)
        {
            final char c = value.charAt(i);
            if (c == ':')
            {
                return i;
            }
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.'))
            {
                return -1;
            }
        }
        return -1;
    }

    /**
     * Whether a computed location stays where its template says it goes. A capture group is filled
     * in from the request path, so {@code /old/(.*)} to {@code /$1} turns a request for
     * {@code /old//evil.example} into {@code Location: //evil.example} - off-site. A path
     * template must therefore yield a path (no scheme, and not {@code //} or {@code /\}, which
     * browsers read as a host), and a template with a literal origin must keep exactly that origin.
     */
    static boolean staysOnTemplateOrigin(final String template, final String location)
    {
        // Browsers strip leading and trailing whitespace before parsing a Location, so " //evil"
        // is followed as //evil: nothing checked below would see what the browser sees.
        if (!location.isEmpty() && (isAsciiWhitespace(location.charAt(0)) || isAsciiWhitespace(location.charAt(location.length() - 1))))
        {
            return false;
        }
        final int templateOriginEnd = originEnd(template);
        if (templateOriginEnd == NO_AUTHORITY)
        {
            return false;
        }
        if (templateOriginEnd < 0)
        {
            return schemeEnd(location) < 0
                    && !location.startsWith("//")
                    && !location.startsWith("/\\")
                    && !location.startsWith("\\");
        }
        final int locationOriginEnd = originEnd(location);
        return locationOriginEnd == templateOriginEnd
                && location.regionMatches(true, 0, template, 0, templateOriginEnd);
    }

    /**
     * The URI component a piece of request text lands in, which decides how it is encoded.
     */
    enum Component
    {
        PATH, QUERY_OR_FRAGMENT;

        String encode(final String decoded)
        {
            return this == PATH ? PathEncoder.encode(decoded) : PathEncoder.encodeQueryValue(decoded);
        }
    }

    /**
     * One piece of a parsed target: literal template text, a capture group, or a query parameter
     * ({@code query} set), the last two with the component their position in the template puts
     * them in.
     */
    record Part(String literal, int group, String query, Component component)
    {
        static Part literal(final String text, final Component component)
        {
            return new Part(text, -1, null, component);
        }

        boolean isLiteral()
        {
            return this.literal != null;
        }
    }

    /**
     * Parses a target in {@link Matcher#appendReplacement} syntax ({@code $n}, {@code ${name}},
     * {@code \x}) so each capture group can be encoded for where it lands, which
     * {@link Matcher#replaceFirst} cannot do. A group is in the query or fragment once a literal
     * {@code ?} or {@code #} precedes it; request text can never move that boundary, because it
     * is encoded before it is spliced in.
     */
    static List<Part> parse(final String template, final Pattern pattern)
    {
        final int groupCount = pattern.matcher("").groupCount();
        final List<Part> parts = new ArrayList<>();
        final StringBuilder literal = new StringBuilder();
        Component component = Component.PATH;
        int i = 0;
        while (i < template.length())
        {
            final char c = template.charAt(i);
            if (c == '\\' && i + 1 < template.length())
            {
                literal.append(template.charAt(i + 1));
                component = componentAfter(template.charAt(i + 1), component);
                i += 2;
            }
            else if (c == '$' && i + 1 < template.length())
            {
                final int group;
                if (template.charAt(i + 1) == '{')
                {
                    final int close = template.indexOf('}', i + 2);
                    final Integer named = close < 0 ? null : pattern.namedGroups().get(template.substring(i + 2, close));
                    if (named == null)
                    {
                        throw new IllegalArgumentException(close < 0 ? "Unterminated ${ in: " + template
                                : "${" + template.substring(i + 2, close) + "} needs a source pattern with a group of that name.");
                    }
                    group = named;
                    i = close + 1;
                }
                else
                {
                    // Same rule as Matcher.appendReplacement: take further digits only while the
                    // number they make is still a group of the pattern.
                    int ref = template.charAt(i + 1) - '0';
                    if (ref < 0 || ref > 9)
                    {
                        throw new IllegalArgumentException("Illegal group reference in: " + template);
                    }
                    if (ref > groupCount)
                    {
                        throw new IllegalArgumentException("$" + ref + " needs a source pattern with that many groups.");
                    }
                    i += 2;
                    while (i < template.length() && Character.isDigit(template.charAt(i)))
                    {
                        final int next = ref * 10 + (template.charAt(i) - '0');
                        if (next > groupCount)
                        {
                            break;
                        }
                        ref = next;
                        i++;
                    }
                    group = ref;
                }
                if (!literal.isEmpty())
                {
                    parts.add(Part.literal(literal.toString(), component));
                    literal.setLength(0);
                }
                parts.add(new Part(null, group, null, component));
            }
            else if (c == '{' && template.startsWith(QUERY_PREFIX, i) && template.indexOf('}', i) > i + QUERY_PREFIX.length())
            {
                final int close = template.indexOf('}', i);
                if (!literal.isEmpty())
                {
                    parts.add(Part.literal(literal.toString(), component));
                    literal.setLength(0);
                }
                parts.add(new Part(null, -1, template.substring(i + QUERY_PREFIX.length(), close), component));
                i = close + 1;
            }
            else
            {
                literal.append(c);
                component = componentAfter(c, component);
                i++;
            }
        }
        // A trailing part carries the component the template ends in, which is where the
        // unmatched remainder of the path is appended.
        parts.add(Part.literal(literal.toString(), component));
        return List.copyOf(parts);
    }

    private static Component componentAfter(final char c, final Component current)
    {
        return c == '?' || c == '#' ? Component.QUERY_OR_FRAGMENT : current;
    }

    private static final class GF implements ClientRequestGatewayFilter, ShortInfo
    {
        private final Pattern sourcePattern;
        private final String targetTemplate;
        private final List<Part> targetParts;
        private final int responseStatus;
        private final ByteBuffer invalidLocationBody;

        public GF(final Config config)
        {
            this.sourcePattern = config.source() == null || config.source().isBlank() ? null : Pattern.compile(config.source());

            // Consume the standard regex string exactly as provided
            this.targetTemplate = config.target();
            this.targetParts = parse(config.target(), this.sourcePattern != null ? this.sourcePattern : NO_GROUPS);

            if (config.status() != null)
            {
                this.responseStatus = config.status().code();
            }
            else
            {
                this.responseStatus = HttpStatuses.FOUND;
            }

            this.invalidLocationBody = ByteBuffer.wrap(
                    "Redirect target could not be computed for this request".getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void onClientRequest(final ClientRequestGatewayExchange exchange)
        {
            final String currentPath = exchange.clientRequest().path();
            final Matcher matcher = this.sourcePattern != null ? RegexBudget.matcher(this.sourcePattern, currentPath) : null;

            if (matcher == null || matcher.find())
            {
                // Two renderings of the same substitution, as Matcher.replaceFirst would build
                // it: the request text as the server decoded it, and percent-encoded for the
                // component it lands in. Only the encoded one is sent - left raw, a decoded %3F
                // or %23 in a capture group would start a query or fragment of its own in the
                // Location, and a '&' add a parameter to a query the template started. The raw
                // one is still checked, because a request whose capture group would take the
                // redirect off the template's origin once decoded is refused rather than served.
                final StringBuilder raw = new StringBuilder(currentPath.length() + this.targetTemplate.length());
                final StringBuilder encoded = new StringBuilder(currentPath.length() + this.targetTemplate.length());
                if (matcher != null)
                {
                    final String before = currentPath.substring(0, matcher.start());
                    raw.append(before);
                    encoded.append(Component.PATH.encode(before));
                }
                Component last = Component.PATH;
                for (final Part part : this.targetParts)
                {
                    if (part.isLiteral())
                    {
                        raw.append(part.literal());
                        encoded.append(part.literal());
                    }
                    else if (part.query() != null)
                    {
                        final String value = queryValue(exchange.clientRequest().queryParams(), part.query());
                        if (value == null)
                        {
                            this.refuse(exchange);
                            return;
                        }
                        raw.append(value);
                        encoded.append(part.component().encode(value));
                    }
                    else
                    {
                        final String captured = matcher.group(part.group());
                        if (captured != null)
                        {
                            raw.append(captured);
                            encoded.append(part.component().encode(captured));
                        }
                    }
                    last = part.component();
                }
                if (matcher != null)
                {
                    final String after = currentPath.substring(matcher.end());
                    raw.append(after);
                    encoded.append(last.encode(after));
                }

                final String location = encoded.toString();

                // The encoded location is what is sent, so it is the one that must be a well-formed
                // header value on the template's origin. Encoding turns request-supplied control
                // characters, whitespace and non-Latin-1 text into %XX, so those checks cannot be
                // failed by a capture group, only by a template built without validation.
                //
                // The decoded form is checked for its origin alone: a request whose capture group
                // would take the redirect off-site once some hop decodes it is hostile, and is
                // refused rather than served. It is trimmed first, as a browser would, so
                // " //evil" still counts as //evil - but a space or non-Latin-1 character in it is
                // data, not a reason to refuse (the encoded form carries it safely).
                final String decodedLocation = stripAsciiWhitespace(raw.toString());
                if (!staysOnTemplateOrigin(this.targetTemplate, decodedLocation)
                        || !isSafeLocation(location) || !staysOnTemplateOrigin(this.targetTemplate, location))
                {
                    this.refuse(exchange);
                    return;
                }

                final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders(1);
                headers.set(HttpHeaders.LOCATION, location);

                exchange.shortCircuit(new ShortCircuitGatewayResponse(
                        headers,
                        this.responseStatus,
                        EMPTY_BODY.slice()
                ));
            }
        }

        private void refuse(final ClientRequestGatewayExchange exchange)
        {
            exchange.shortCircuit(new ShortCircuitGatewayResponse(
                    HttpStatuses.BAD_REQUEST,
                    MediaTypes.TEXT_PLAIN_UTF8,
                    this.invalidLocationBody.asReadOnlyBuffer()
            ));
        }

        /**
         * The parameter's value, or null when there is none to redirect with: it is missing, or it
         * is repeated with values that differ, so no single value is the client's meaning.
         */
        private static String queryValue(final QueryParams params, final String name)
        {
            String value = null;
            for (final String candidate : params.getAll(name))
            {
                if (value != null && !value.equals(candidate))
                {
                    return null;
                }
                value = candidate;
            }
            return value;
        }

        private static String stripAsciiWhitespace(final String value)
        {
            int start = 0;
            int end = value.length();
            while (start < end && isAsciiWhitespace(value.charAt(start)))
            {
                start++;
            }
            while (end > start && isAsciiWhitespace(value.charAt(end - 1)))
            {
                end--;
            }
            return value.substring(start, end);
        }

        private static boolean isSafeLocation(final String location)
        {
            // Unlike a header value, a Location carries a URI-reference; RFC 3986 has no
            // "obs-fold" concept, so unlike safeHeaderText, HTAB is not treated as safe here.
            for (int i = 0, len = location.length(); i < len; i++)
            {
                final char character = location.charAt(i);
                if (character > TextValues.MAX_STORABLE || character == 0x7F || character < 0x20)
                {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String name()
        {
            return FILTER_NAME;
        }

        @Override
        public String summary()
        {
            return FILTER_NAME + ": " + this.responseStatus + " -> " + this.targetTemplate;
        }
    }
}