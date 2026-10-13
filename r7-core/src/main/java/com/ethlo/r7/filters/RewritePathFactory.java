package com.ethlo.r7.filters;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.ethlo.r7.api.MutableQueryParams;
import com.ethlo.r7.api.QueryParams;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.api.StateKey;
import com.ethlo.r7.api.UpstreamRequestGatewayExchange;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.filters.ReplacementTemplate.Part;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.ShortCircuitGatewayResponse;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.r7.util.constants.MediaTypes;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.ethlo.r7.util.RegexBudget;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayFilterFactory.class)
@Description("Rewrites the request path using a regular expression replacement.")
public final class RewritePathFactory implements GatewayFilterFactory<RewritePathFactory.Config>
{
    private static final String FILTER_NAME = "RewritePath";

    /** What a rewrite did to the query, so a later rewrite can put it back. */
    private static final StateKey<Earlier> EARLIER = new StateKey<>("rewrite_path.query");

    /**
     * The upstream query before a rewrite, as names and values in turn, and the parameters the
     * rewrite set with the values it left them.
     */
    private record Earlier(List<String> before, Map<String, List<String>> written)
    {
    }

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
    public UpstreamRequestGatewayFilter create(final Config config, final FilterCreationContext filterCreationContext)
    {
        return new GF(config);
    }

    public record Config(
            @Description("The regular expression to match against the path.")
            String regexp,

            @Description("The replacement: $1 for a numbered group, {name} for a named one, {query.NAME} for a query parameter of the request. A literal ? starts the query the upstream receives, whose parameters each need a name and whose captures and query values must be parameter values. \\{, \\?, \\& and \\= are literal characters.")
            String replacement) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            new ValidatorUtils(result)
                    .requiredRegexp("regexp", this.regexp())
                    .required("replacement", this.replacement());
            if (this.regexp() == null || this.replacement() == null)
            {
                return;
            }
            try
            {
                parts(Pattern.compile(this.regexp()), this.replacement());
            }
            catch (final PatternSyntaxException ignored)
            {
                // requiredRegexp() reports the pattern itself.
            }
            catch (final IllegalArgumentException e)
            {
                result.addError("replacement", e.getMessage());
            }
        }
    }

    /**
     * The parsed replacement, refused when request text could name a parameter: a capture or
     * {@code {query.NAME}} where a name goes, or a query that ends in a name, where the rest of
     * the path would land. A parameter with an empty name is refused too: the upstream's parser
     * would drop it or keep it, and r7 can not say which.
     */
    private static List<Part> parts(final Pattern regexp, final String replacement)
    {
        final List<Part> parts = ReplacementTemplate.parse(replacement, regexp, false);
        boolean inQuery = false;
        boolean inValue = false;
        boolean named = false;
        for (final Part part : parts)
        {
            if (part.startsQuery())
            {
                inQuery = true;
            }
            else if (!inQuery)
            {
                continue;
            }
            else if (part.data())
            {
                named |= !inValue;
            }
            else if (part.isLiteral())
            {
                for (int i = 0; i < part.literal().length(); i++)
                {
                    final char c = part.literal().charAt(i);
                    if (c == '&' || (c == '=' && !inValue))
                    {
                        if (!inValue && !named)
                        {
                            throw new IllegalArgumentException("Every parameter after the ? needs a name: ?=x, ?&a=1 and ?a=1&&b=2 each have an empty one.");
                        }
                        inValue = c == '=';
                        named = false;
                    }
                    else
                    {
                        named |= !inValue;
                    }
                }
            }
            else if (!inValue)
            {
                throw new IllegalArgumentException("A capture or {query.NAME} after the ? must be in a parameter value (name=$1), so a request can not name a parameter.");
            }
        }
        if (inQuery && !inValue)
        {
            throw new IllegalArgumentException("The query of the replacement must end in a parameter value (name=...), where the rest of the path goes.");
        }
        return parts;
    }

    /**
     * Splits a query into parameters by what the template wrote: its own unescaped {@code &} and
     * the first unescaped {@code =} after it are structure, and anything taken from the request,
     * or escaped in the template, is data, whatever characters it holds.
     */
    private static final class QueryBuilder
    {
        private final List<String[]> pairs = new ArrayList<>();
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder value = new StringBuilder();
        private boolean inValue;

        void syntax(final String text)
        {
            for (int i = 0; i < text.length(); i++)
            {
                final char c = text.charAt(i);
                if (c == '&')
                {
                    this.endPair();
                }
                else if (c == '=' && !this.inValue)
                {
                    this.inValue = true;
                }
                else
                {
                    (this.inValue ? this.value : this.name).append(c);
                }
            }
        }

        void data(final String text)
        {
            (this.inValue ? this.value : this.name).append(text);
        }

        private void endPair()
        {
            // parts() refuses a template with an empty name, and request text is never a name.
            this.pairs.add(new String[]{this.name.toString(), this.value.toString()});
            this.name.setLength(0);
            this.value.setLength(0);
            this.inValue = false;
        }

        List<String[]> pairs()
        {
            this.endPair();
            return this.pairs;
        }
    }

    private static final class GF implements UpstreamRequestGatewayFilter, ShortInfo
    {
        private final Pattern regexp;
        private final String replacement;
        private final List<Part> parts;
        private final boolean setsQuery;
        private final ByteBuffer missingValueBody = ByteBuffer.wrap("Query parameter needed to rewrite the request is missing or ambiguous".getBytes(StandardCharsets.UTF_8));

        public GF(final Config config)
        {
            this.regexp = Pattern.compile(config.regexp());
            this.replacement = config.replacement();
            this.parts = parts(this.regexp, this.replacement);
            this.setsQuery = this.parts.stream().anyMatch(Part::startsQuery);
        }

        /**
         * Every match replaced, as {@link Matcher#replaceAll} does, with the template's parts.
         * The result is decoded text: the path is encoded when it is set.
         */
        private String replaceAll(final UpstreamRequestGatewayExchange exchange, final Matcher matcher, final String path)
        {
            final StringBuilder out = new StringBuilder(path.length() + this.replacement.length());
            int last = 0;
            do
            {
                out.append(path, last, matcher.start());
                for (final Part part : this.parts)
                {
                    if (part.isLiteral())
                    {
                        out.append(part.literal());
                    }
                    else if (part.query() != null)
                    {
                        final String value = ReplacementTemplate.queryValue(exchange.clientRequest().queryParams(), part.query());
                        if (value == null)
                        {
                            return null;
                        }
                        out.append(value);
                    }
                    else if (part.isCapture())
                    {
                        final String captured = matcher.group(part.group());
                        if (captured != null)
                        {
                            out.append(captured);
                        }
                    }
                }
                last = matcher.end();
            }
            while (matcher.find());
            return out.append(path, last, path.length()).toString();
        }

        @Override
        public void onUpstreamRequest(final UpstreamRequestGatewayExchange exchange)
        {
            final String path = exchange.clientRequest().path();
            final Matcher matcher = RegexBudget.matcher(this.regexp, path);

            if (!matcher.find())
            {
                return;
            }
            if (this.setsQuery)
            {
                this.rewriteWithQuery(exchange, matcher, path);
                return;
            }
            final String rewritten = replaceAll(exchange, matcher, path);
            if (rewritten == null)
            {
                this.refuse(exchange);
                return;
            }
            restoreEarlierRewrite(exchange);
            exchange.upstreamRequest().path(rewritten);
        }

        private void refuse(final UpstreamRequestGatewayExchange exchange)
        {
            exchange.shortCircuit(new ShortCircuitGatewayResponse(HttpStatuses.BAD_REQUEST, MediaTypes.TEXT_PLAIN_UTF8, this.missingValueBody.asReadOnlyBuffer()));
        }

        /**
         * Every rewrite tests the path the client sent, so the last match wins; the parameters an
         * earlier rewrite set go back to what they were before it, or the earlier one would leak
         * into the later one's result. A parameter another filter has changed since is that
         * filter's now, and is left as it is. What goes back goes back where it was: after the
         * parameter it followed before the earlier rewrite, or the nearest one before that which
         * is still there.
         */
        private static void restoreEarlierRewrite(final UpstreamRequestGatewayExchange exchange)
        {
            final Earlier earlier = exchange.getAttachment(EARLIER);
            if (earlier == null)
            {
                return;
            }
            exchange.setAttachment(EARLIER, null);
            final MutableQueryParams params = exchange.upstreamRequest().queryParams();
            final List<String> current = pairs(params);
            final Set<String> restored = new HashSet<>();
            earlier.written().forEach((name, written) ->
            {
                if (values(current, name).equals(written))
                {
                    restored.add(name);
                }
            });
            if (restored.isEmpty())
            {
                return;
            }

            final List<String> kept = new ArrayList<>(current.size());
            for (int i = 0; i < current.size(); i += 2)
            {
                if (!restored.contains(current.get(i)))
                {
                    kept.add(current.get(i));
                    kept.add(current.get(i + 1));
                }
            }
            final List<String> merged = new ArrayList<>(kept.size() + earlier.before().size());
            final Map<String, Integer> seen = new HashMap<>();
            int next = 0;
            for (int i = 0; i < earlier.before().size(); i += 2)
            {
                final String name = earlier.before().get(i);
                if (restored.contains(name))
                {
                    merged.add(name);
                    merged.add(earlier.before().get(i + 1));
                    continue;
                }
                final int at = occurrence(kept, name, seen.merge(name, 1, Integer::sum) - 1);
                for (; next <= at; next += 2)
                {
                    merged.add(kept.get(next));
                    merged.add(kept.get(next + 1));
                }
            }
            merged.addAll(kept.subList(next, kept.size()));

            for (int i = 0; i < current.size(); i += 2)
            {
                params.remove(current.get(i));
            }
            for (int i = 0; i < merged.size(); i += 2)
            {
                params.add(merged.get(i), merged.get(i + 1));
            }
        }

        /**
         * The index of the name of the given occurrence of a parameter, or -1 if there are fewer.
         */
        private static int occurrence(final List<String> pairs, final String name, final int occurrence)
        {
            int found = 0;
            for (int i = 0; i < pairs.size(); i += 2)
            {
                if (pairs.get(i).equals(name) && found++ == occurrence)
                {
                    return i;
                }
            }
            return -1;
        }

        private static List<String> pairs(final QueryParams params)
        {
            final List<String> pairs = new ArrayList<>();
            params.forEach((name, value) ->
            {
                pairs.add(name);
                pairs.add(value);
            });
            return pairs;
        }

        private static List<String> values(final List<String> pairs, final String name)
        {
            final List<String> values = new ArrayList<>();
            for (int i = 0; i < pairs.size(); i += 2)
            {
                if (pairs.get(i).equals(name))
                {
                    values.add(pairs.get(i + 1));
                }
            }
            return values;
        }

        /**
         * The replacement starts a query, so the first match is rewritten and whatever follows it
         * goes on the end of the query's last value, as {@code TemplateRedirect} carries it over:
         * where the template ends is where the rest of the path lands. Request text, captured or
         * carried, is only ever a value: it cannot add a parameter. The parameters the rewrite
         * sets replace every occurrence the client sent; the client's others are untouched.
         */
        private void rewriteWithQuery(final UpstreamRequestGatewayExchange exchange, final Matcher matcher, final String path)
        {
            final StringBuilder newPath = new StringBuilder(path.length() + this.replacement.length()).append(path, 0, matcher.start());
            final QueryBuilder query = new QueryBuilder();
            boolean inQuery = false;
            for (final Part part : this.parts)
            {
                final String text;
                if (part.startsQuery())
                {
                    inQuery = true;
                    continue;
                }
                else if (part.data())
                {
                    query.data(part.literal());
                    continue;
                }
                else if (part.isLiteral())
                {
                    if (inQuery)
                    {
                        query.syntax(part.literal());
                    }
                    else
                    {
                        newPath.append(part.literal());
                    }
                    continue;
                }
                else if (part.query() != null)
                {
                    text = ReplacementTemplate.queryValue(exchange.clientRequest().queryParams(), part.query());
                    if (text == null)
                    {
                        this.refuse(exchange);
                        return;
                    }
                }
                else
                {
                    text = matcher.group(part.group());
                }
                if (text != null)
                {
                    if (inQuery)
                    {
                        query.data(text);
                    }
                    else
                    {
                        newPath.append(text);
                    }
                }
            }
            query.data(path.substring(matcher.end()));

            restoreEarlierRewrite(exchange);
            exchange.upstreamRequest().path(newPath.toString());
            final MutableQueryParams params = exchange.upstreamRequest().queryParams();
            final List<String> before = pairs(params);
            final Map<String, List<String>> written = new LinkedHashMap<>();
            for (final String[] pair : query.pairs())
            {
                final List<String> values = written.get(pair[0]);
                if (values == null)
                {
                    params.set(pair[0], pair[1]);
                    written.put(pair[0], new ArrayList<>(List.of(pair[1])));
                }
                else
                {
                    params.add(pair[0], pair[1]);
                    values.add(pair[1]);
                }
            }
            exchange.setAttachment(EARLIER, new Earlier(before, written));
        }

        @Override
        public String name()
        {
            return FILTER_NAME;
        }

        @Override
        public String summary()
        {
            return FILTER_NAME + ": " + this.regexp.pattern() + " -> " + this.replacement;
        }
    }
}