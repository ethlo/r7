package com.ethlo.r7.tailer;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses environment variable values using the same human-readable duration and data size
 * grammar as the gateway's YAML config (see
 * {@code ConfigurationManager.HumanDurationDeserializer}/{@code HumanDataSizeDeserializer} in
 * {@code r7-core}), so an operator moving a setting between {@code routes.yaml}/{@code
 * server.yaml} and a tailer's environment never has to remember two different unit systems.
 */
public final class EnvConfig
{
    private static final Pattern DURATION_PATTERN = Pattern.compile("^(\\d+)(ms|s|m|h|d)$");
    private static final Pattern DATA_SIZE_PATTERN = Pattern.compile("^(\\d+)(b|kb|mb|gb)?$", Pattern.CASE_INSENSITIVE);

    private EnvConfig()
    {
    }

    /**
     * @param text a duration such as {@code 10ms}, {@code 5s}, {@code 2m}, {@code 1h} or
     *             {@code 3d}; the unit is required
     */
    public static Duration parseDuration(final String text)
    {
        final String trimmed = text.trim().toLowerCase();
        final Matcher matcher = DURATION_PATTERN.matcher(trimmed);
        if (!matcher.matches())
        {
            throw new IllegalArgumentException("Invalid duration format: '" + text + "'. Supported formats: 10ms, 5s, 2m, 1h, 3d");
        }

        final long amount = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2))
        {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException("Unknown duration unit in '" + text + "'");
        };
    }

    public static Duration duration(final Map<String, String> env, final String key, final String defaultValue)
    {
        return parseDuration(env.getOrDefault(key, defaultValue));
    }

    /**
     * @param text a data size such as {@code 1024}, {@code 8kb}, {@code 200mb} or {@code 1gb};
     *             a bare number is bytes
     * @return the size in bytes
     */
    public static long parseDataSize(final String text)
    {
        final String trimmed = text.trim();
        final Matcher matcher = DATA_SIZE_PATTERN.matcher(trimmed);
        if (!matcher.matches())
        {
            throw new IllegalArgumentException("Invalid data size format: '" + text + "'. Supported formats: 1024, 8kb, 200mb, 1gb");
        }

        final long amount = Long.parseLong(matcher.group(1));
        final String unit = matcher.group(2) != null ? matcher.group(2).toLowerCase() : "b";
        return switch (unit)
        {
            case "b" -> amount;
            case "kb" -> Math.multiplyExact(amount, 1024L);
            case "mb" -> Math.multiplyExact(amount, 1024L * 1024L);
            case "gb" -> Math.multiplyExact(amount, 1024L * 1024L * 1024L);
            default -> throw new IllegalArgumentException("Unknown data size unit in '" + text + "'");
        };
    }

    public static long dataSizeBytes(final Map<String, String> env, final String key, final String defaultValue)
    {
        return parseDataSize(env.getOrDefault(key, defaultValue));
    }
}
