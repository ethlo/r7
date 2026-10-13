package com.ethlo.r7.filters;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.api.UpstreamRequestGatewayExchange;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.filters.ReplacementTemplate.Part;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.ValidatorUtils;
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

            @Description("The replacement: $1 for a numbered group, {name} for a named one. \\{ is a literal brace.")
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
        final List<Part> parts = ReplacementTemplate.parse(replacement, regexp, false);
        for (final Part part : parts)
        {
            if (part.query() != null)
            {
                throw new IllegalArgumentException("{query." + part.query() + "} is not available in a path rewrite.");
            }
        }
        return parts;
    }

    private static final class GF implements UpstreamRequestGatewayFilter, ShortInfo
    {
        private final Pattern regexp;
        private final String replacement;
        private final List<Part> parts;

        public GF(final Config config)
        {
            this.regexp = Pattern.compile(config.regexp());
            this.replacement = config.replacement();
            this.parts = parts(this.regexp, this.replacement);
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
                    else
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

            if (matcher.find())
            {
                final String newPath = replaceAll(matcher, path);
                exchange.upstreamRequest().path(newPath);
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