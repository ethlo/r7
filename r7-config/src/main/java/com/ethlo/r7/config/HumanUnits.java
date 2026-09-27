package com.ethlo.r7.config;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ethlo.r7.config.model.DataSize;

/**
 * The single, canonical implementation of r7's human-readable duration/data-size grammar
 * ({@code 10ms, 5s, 2m, 1h, 3d} / {@code 1024, 8kb, 200mb, 1gb}), used by {@code
 * YamlConfigSupport}'s Jackson deserializers for every r7 YAML config file - the gateway's
 * {@code routes.yaml}/{@code server.yaml} and each tailer's {@code <name>-tailer.yaml} alike -
 * so all of them share one unit system and a bug fixed here is fixed everywhere at once.
 */
public final class HumanUnits
{
    private static final Pattern DURATION_PATTERN = Pattern.compile("^(\\d+)(ms|s|m|h|d)$");
    private static final Pattern DATA_SIZE_PATTERN = Pattern.compile("^(\\d+)(b|kb|mb|gb)?$", Pattern.CASE_INSENSITIVE);

    private HumanUnits()
    {
    }

    /**
     * @param text a duration such as {@code 10ms}, {@code 5s}, {@code 2m}, {@code 1h} or
     *             {@code 3d}; the unit is required - unlike an unbounded switch on an optional
     *             unit, a required unit can never resolve to a {@code null} switch selector.
     */
    public static Duration parseDuration(final String text)
    {
        final String trimmed = text.trim().toLowerCase();
        final Matcher matcher = DURATION_PATTERN.matcher(trimmed);
        if (!matcher.matches())
        {
            throw new ConfigurationException("Invalid duration format: '" + text + "'. Supported formats: 10ms, 5s, 2m, 1h, 3d");
        }

        final long amount = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2))
        {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> throw new ConfigurationException("Unknown duration unit in '" + text + "'");
        };
    }

    /**
     * @param text a data size such as {@code 1024}, {@code 8kb}, {@code 200mb} or {@code 1gb};
     *             a bare number is bytes
     */
    public static DataSize parseDataSize(final String text)
    {
        final String trimmed = text.trim();
        final Matcher matcher = DATA_SIZE_PATTERN.matcher(trimmed);
        if (!matcher.matches())
        {
            throw new ConfigurationException("Invalid data size format: '" + text + "'. Supported formats: 1024, 8kb, 200mb, 1gb");
        }

        final long amount = Long.parseLong(matcher.group(1));
        final String unit = matcher.group(2) != null ? matcher.group(2).toLowerCase() : "b";
        return switch (unit)
        {
            case "b" -> DataSize.ofBytes(amount);
            case "kb" -> DataSize.ofKilobytes(amount);
            case "mb" -> DataSize.ofMegabytes(amount);
            case "gb" -> DataSize.ofGigabytes(amount);
            default -> throw new ConfigurationException("Unknown data size unit in '" + text + "'");
        };
    }
}
