package com.ethlo.r7.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.ethlo.r7.config.model.DataSize;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.exc.ValueInstantiationException;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;
import tools.jackson.dataformat.yaml.JacksonYAMLParseException;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The generic, engine-agnostic half of what used to be {@code ConfigurationManager}: the YAML
 * mapper conventions (snake_case naming, unknown-property/single-value-as-array handling, the
 * {@code Duration}/{@code DataSize} deserializers) and the readable-error formatting around
 * Jackson's exceptions.
 * <p>
 * Anything that needs to know about routes, filters, or predicates stays in {@code r7-core}'s
 * {@code ConfigurationManager}, which layers its own deserializers (e.g. {@code HttpStatus}) on
 * top of {@link #baseMapperBuilder()}.
 */
public final class YamlConfigSupport
{
    private YamlConfigSupport()
    {
    }

    /**
     * A {@link YAMLMapper} builder with r7's shared config conventions already applied - snake
     * case property names, single-value-as-array, fail-on-unknown-properties, and the
     * {@code Duration}/{@code DataSize} human-unit deserializers. Callers add their own
     * deserializers/modules on top before calling {@code build()}.
     */
    public static YAMLMapper.Builder baseMapperBuilder()
    {
        final SimpleModule module = new SimpleModule();
        module.addDeserializer(Duration.class, new HumanDurationDeserializer());
        module.addDeserializer(DataSize.class, new HumanDataSizeDeserializer());

        return YAMLMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .addModule(module);
    }

    /**
     * Reads a YAML file, parses it, interpolates ({@code ${VAR:default}}) each scalar value in
     * the resulting tree, then binds it to {@code type} using {@code mapper}, converting
     * Jackson's exceptions into the same readable, field-naming {@link ConfigurationException}
     * messages regardless of caller.
     * <p>
     * Interpolation deliberately happens on the parsed tree rather than the raw text: a single
     * regex pass over the whole file would let an environment value that contains YAML
     * metacharacters (a colon, a newline) add or rewrite nodes the author never wrote, instead of
     * only filling in the scalar it was substituted into. Interpolating per scalar value confines
     * a substituted value to being text, wherever in the tree it lands.
     */
    public static <T> T load(final ObjectMapper mapper, final Path yamlFile, final Class<T> type)
    {
        final String contents;
        try
        {
            contents = Files.readString(yamlFile);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }

        try
        {
            final JsonNode root = mapper.readTree(contents);
            interpolateTree(root);
            return mapper.treeToValue(root, type);
        }
        catch (JacksonYAMLParseException e)
        {
            throw new ConfigurationException(formatYamlSyntaxError(yamlFile, e));
        }
        catch (UnrecognizedPropertyException e)
        {
            throw new ConfigurationException(formatUnknownProperty(yamlFile, e));
        }
        catch (InvalidFormatException e)
        {
            throw new ConfigurationException(formatMappingError(yamlFile, e));
        }
        catch (ValueInstantiationException e)
        {
            // A ConfigurationException thrown by a @JsonCreator (e.g. rejecting a malformed
            // shorthand) arrives wrapped; unwrap it so callers see the readable message, not
            // Jackson's construction-failure noise around it.
            if (e.getCause() instanceof ConfigurationException configurationException)
            {
                throw configurationException;
            }
            throw e;
        }
    }

    /**
     * Interpolates every textual scalar in the tree in place. Object keys are left untouched -
     * only values carry operator-supplied content that env interpolation is for.
     */
    private static void interpolateTree(final JsonNode node)
    {
        if (node instanceof ObjectNode objectNode)
        {
            for (final var entry : objectNode.properties())
            {
                interpolateChild(objectNode::set, entry.getKey(), entry.getValue());
            }
        }
        else if (node instanceof ArrayNode arrayNode)
        {
            for (int i = 0; i < arrayNode.size(); i++)
            {
                final int index = i;
                interpolateChild((key, value) -> arrayNode.set(index, value), null, arrayNode.get(i));
            }
        }
    }

    private interface NodeSetter
    {
        void set(String key, JsonNode value);
    }

    private static void interpolateChild(final NodeSetter setter, final String key, final JsonNode value)
    {
        if (value.isTextual())
        {
            setter.set(key, StringNode.valueOf(EnvInterpolator.interpolate(value.asString())));
        }
        else
        {
            interpolateTree(value);
        }
    }

    /**
     * @param file the source file, for the error message; may be {@code null} when the input
     *             being bound did not come directly from a file (e.g. inline filter arguments)
     */
    public static String formatYamlSyntaxError(
            final Path file,
            final JacksonYAMLParseException e)
    {
        final TokenStreamLocation loc = e.getLocation();

        // Extract the highly readable SnakeYAML explanation, cutting off Jackson's internal trace info
        String cleanMessage = e.getMessage();
        final int sourceTraceIndex = cleanMessage.indexOf("\n at [Source");
        if (sourceTraceIndex != -1)
        {
            cleanMessage = cleanMessage.substring(0, sourceTraceIndex);
        }

        final StringBuilder sb = new StringBuilder();

        sb.append("Invalid YAML syntax in configuration file: ")
                .append(file)
                .append(System.lineSeparator())
                .append(System.lineSeparator());

        sb.append(cleanMessage)
                .append(System.lineSeparator())
                .append(System.lineSeparator());

        if (loc != null)
        {
            sb.append("Fix the structural error around line ")
                    .append(loc.getLineNr())
                    .append(", column ")
                    .append(loc.getColumnNr())
                    .append(".");
        }

        return sb.toString();
    }

    public static String formatMappingError(
            final Path file,
            final MismatchedInputException e)
    {
        final TokenStreamLocation loc = e.getLocation();

        final StringBuilder sb = new StringBuilder();

        sb.append("Configuration mapping error in ")
                .append(file);

        if (loc != null)
        {
            sb.append(" at line ")
                    .append(loc.getLineNr())
                    .append(", column ")
                    .append(loc.getColumnNr());
        }

        sb.append(System.lineSeparator());

        if (!e.getPath().isEmpty())
        {
            sb.append("Property path: ");

            for (JacksonException.Reference ref : e.getPath())
            {
                sb.append(ref.getPropertyName()).append(".");
            }

            sb.setLength(sb.length() - 1);

            sb.append(System.lineSeparator());
        }

        sb.append(e.getOriginalMessage());

        return sb.toString();
    }

    public static String formatUnknownProperty(
            final Path file,
            final UnrecognizedPropertyException e)
    {
        final TokenStreamLocation loc = e.getLocation();
        final StringBuilder sb = new StringBuilder();

        sb.append("Invalid configuration file: ")
                .append(file)
                .append(System.lineSeparator())
                .append(System.lineSeparator());

        sb.append("Unknown configuration option: '")
                .append(e.getPropertyName())
                .append("'");

        if (!e.getPath().isEmpty())
        {
            sb.append(" at [");
            final StringBuilder pathBuilder = new StringBuilder();

            for (final JacksonException.Reference ref : e.getPath())
            {
                if (ref.getPropertyName() != null)
                {
                    if (!pathBuilder.isEmpty() && pathBuilder.charAt(pathBuilder.length() - 1) != ']')
                    {
                        pathBuilder.append(".");
                    }
                    pathBuilder.append(ref.getPropertyName());
                }
                else if (ref.getIndex() >= 0)
                {
                    pathBuilder.append("[").append(ref.getIndex()).append("]");
                }
            }
            sb.append(pathBuilder).append("]");
        }

        sb.append(System.lineSeparator());

        if (loc != null)
        {
            sb.append("Location: line ")
                    .append(loc.getLineNr())
                    .append(", column ")
                    .append(loc.getColumnNr())
                    .append(System.lineSeparator());
        }

        sb.append(System.lineSeparator());

        if (e.getKnownPropertyIds() != null && !e.getKnownPropertyIds().isEmpty())
        {
            sb.append("Known properties are: ")
                    .append(e.getKnownPropertyIds())
                    .append(System.lineSeparator())
                    .append(System.lineSeparator());
        }

        sb.append("Remove the option or check for spelling mistakes.");

        return sb.toString();
    }

    public static final class HumanDurationDeserializer extends StdDeserializer<Duration>
    {
        public HumanDurationDeserializer()
        {
            super(Duration.class);
        }

        @Override
        public Duration deserialize(final JsonParser p, final DeserializationContext ctxt)
        {
            return HumanUnits.parseDuration(p.getString());
        }
    }

    public static final class HumanDataSizeDeserializer extends StdDeserializer<DataSize>
    {
        public HumanDataSizeDeserializer()
        {
            super(DataSize.class);
        }

        @Override
        public DataSize deserialize(final JsonParser p, final DeserializationContext ctxt)
        {
            return HumanUnits.parseDataSize(p.getString());
        }
    }
}
