package com.ethlo.r7.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Writes the Prometheus text exposition format, version 0.0.4: each metric's {@code # HELP} and
 * {@code # TYPE} once, then its samples. Label values are escaped; names are this class's
 * caller's constants, never data.
 */
final class PrometheusText
{
    static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private final StringBuilder out = new StringBuilder(8192);
    private final Set<String> declared = new HashSet<>();

    /**
     * Declares a metric. Its samples must follow before the next metric is declared: the format
     * wants a metric's samples together.
     */
    PrometheusText metric(final String name, final String type, final String help)
    {
        if (!this.declared.add(name))
        {
            throw new IllegalStateException("Metric declared twice: " + name);
        }
        this.out.append("# HELP ").append(name).append(' ').append(help).append('\n');
        this.out.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        return this;
    }

    /**
     * @param labels name and value pairs
     */
    PrometheusText sample(final String name, final long value, final String... labels)
    {
        writeName(name, labels);
        this.out.append(value).append('\n');
        return this;
    }

    PrometheusText sample(final String name, final BigDecimal value, final String... labels)
    {
        writeName(name, labels);
        this.out.append(value.toPlainString()).append('\n');
        return this;
    }

    byte[] toBytes()
    {
        return this.out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void writeName(final String name, final String... labels)
    {
        this.out.append(name);
        if (labels.length > 0)
        {
            this.out.append('{');
            for (int i = 0; i < labels.length; i += 2)
            {
                if (i > 0)
                {
                    this.out.append(',');
                }
                this.out.append(labels[i]).append("=\"");
                escape(labels[i + 1]);
                this.out.append('"');
            }
            this.out.append('}');
        }
        this.out.append(' ');
    }

    // Backslash, double quote and line feed are the three characters a label value must escape
    private void escape(final String value)
    {
        for (int i = 0; i < value.length(); i++)
        {
            final char c = value.charAt(i);
            switch (c)
            {
                case '\\' -> this.out.append("\\\\");
                case '"' -> this.out.append("\\\"");
                case '\n' -> this.out.append("\\n");
                default -> this.out.append(c);
            }
        }
    }
}
