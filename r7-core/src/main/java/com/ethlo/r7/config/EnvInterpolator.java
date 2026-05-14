package com.ethlo.r7.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EnvInterpolator
{
    // Matches ${VAR_NAME} or ${VAR_NAME:default_value}
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private EnvInterpolator()
    {
        // Utility class
    }

    public static String interpolate(final String input)
    {
        if (input == null || input.isEmpty())
        {
            return input;
        }

        final Matcher matcher = ENV_PATTERN.matcher(input);
        final StringBuilder sb = new StringBuilder(input.length() + 64);

        while (matcher.find())
        {
            final String group = matcher.group(1);
            final int separatorIndex = group.indexOf(':');
            final String envName;
            final String defaultValue;

            if (separatorIndex != -1)
            {
                envName = group.substring(0, separatorIndex);
                defaultValue = group.substring(separatorIndex + 1);
            }
            else
            {
                envName = group;
                defaultValue = null;
            }

            // Check OS Environment Variables
            String envValue = System.getenv(envName);

            // Fallback to JVM System Properties (-Dmy.prop=value)
            if (envValue == null)
            {
                envValue = System.getProperty(envName);
            }

            if (envValue != null)
            {
                // quoteReplacement ensures passwords with $ or \ don't break the regex engine
                matcher.appendReplacement(sb, Matcher.quoteReplacement(envValue));
            }
            else if (defaultValue != null)
            {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(defaultValue));
            }
            else
            {
                throw new IllegalArgumentException("Missing required environment variable '" + envName + "'");
            }
        }

        matcher.appendTail(sb);
        return sb.toString();
    }
}