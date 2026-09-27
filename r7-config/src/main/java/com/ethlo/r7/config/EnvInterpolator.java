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
                final int[] pos = getLineAndColumn(matcher, input);
                throw new ConfigurationException("Missing required environment variable '" + envName + "' for config on line " + pos[0] + ", column " + pos[1]);
            }
        }

        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Calculates the 1-based line and column number of the current matcher position.
     *
     * @param matcher The matcher, which must have already found a match.
     * @param text    The original multiline string.
     * @return An int array where index 0 is the line and index 1 is the column.
     */
    public static int[] getLineAndColumn(final Matcher matcher, final String text)
    {
        final int matchStart = matcher.start();
        int line = 1;
        int lineStartIndex = 0;

        for (int i = 0; i < matchStart; i++)
        {
            final char c = text.charAt(i);

            if (c == '\r')
            {
                line++;
                if ((i + 1) < matchStart && text.charAt(i + 1) == '\n')
                {
                    i++;
                }
                lineStartIndex = i + 1;
            }
            else if (c == '\n')
            {
                line++;
                lineStartIndex = i + 1;
            }
        }

        final int column = matchStart - lineStartIndex + 1;
        return new int[]{line, column};
    }
}