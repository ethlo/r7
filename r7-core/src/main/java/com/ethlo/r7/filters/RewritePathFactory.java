package com.ethlo.r7.filters;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.ethlo.r7.api.MutableQueryParams;
import com.ethlo.r7.api.ShortInfo;
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

            @Description("The replacement: $1 for a numbered group, {name} for a named one, {query.NAME} for a query parameter of the request. A literal ? starts the query the upstream receives. \\{ and \\? are literal characters.")
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

    private static List<Part> parts(final Pattern regexp, final String replacement)
    {
        return ReplacementTemplate.parse(replacement, regexp, false);
    }

    /**
     * Splits a query into parameters by what the template wrote: its own {@code &} and the first
     * {@code =} after it are structure, and anything taken from the request is data, whatever
     * characters it holds.
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
                    this.data(String.valueOf(c));
                }
            }
        }

        void data(final String text)
        {
            (this.inValue ? this.value : this.name).append(text);
        }

        private void endPair()
        {
            if (!this.name.isEmpty())
            {
                this.pairs.add(new String[]{this.name.toString(), this.value.toString()});
            }
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
        private String replaceAll(final Matcher matcher, final String path)
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
            exchange.upstreamRequest().path(replaceAll(matcher, path));
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
                        exchange.shortCircuit(new ShortCircuitGatewayResponse(HttpStatuses.BAD_REQUEST, MediaTypes.TEXT_PLAIN_UTF8, this.missingValueBody.asReadOnlyBuffer()));
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

            exchange.upstreamRequest().path(newPath.toString());
            final MutableQueryParams params = exchange.upstreamRequest().queryParams();
            final Set<String> set = new HashSet<>();
            for (final String[] pair : query.pairs())
            {
                if (set.add(pair[0]))
                {
                    params.set(pair[0], pair[1]);
                }
                else
                {
                    params.add(pair[0], pair[1]);
                }
            }
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