package com.ethlo.r7.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.journal.api.JournalLevel;

/**
 * The end record follows the resolved levels, like the request and response records do.
 */
class RouteJournalConfigTest
{
    private static JournalDirectionConfig direction(final JournalLevel level, final Map<String, JournalLevel> overrides)
    {
        return new JournalDirectionConfig(level, JournalOverrideParser.parseOverrides(overrides));
    }

    @Test
    void overridesToNoneInBothDirectionsMeanNoEndRecord()
    {
        final RouteJournalConfig config = new RouteJournalConfig(
                direction(JournalLevel.HEADERS, Map.of("404", JournalLevel.NONE)),
                direction(JournalLevel.METADATA, Map.of("404", JournalLevel.NONE)));

        assertThat(config.isAtLeastMetadata(404)).isFalse();
        assertThat(config.isAtLeastMetadata(200)).isTrue();
    }

    @Test
    void anOverrideRaisingEitherDirectionMeansAnEndRecord()
    {
        final RouteJournalConfig config = new RouteJournalConfig(
                direction(JournalLevel.NONE, null),
                direction(JournalLevel.NONE, Map.of("5xx", JournalLevel.METADATA)));

        assertThat(config.isAtLeastMetadata(503)).isTrue();
        assertThat(config.isAtLeastMetadata(200)).isFalse();
    }

    /**
     * A FULL request is written before the status exists, so it always gets its end record,
     * even if an override that validation refuses were to slip through.
     */
    @Test
    void aFullRequestAlwaysGetsItsEndRecord()
    {
        final RouteJournalConfig config = new RouteJournalConfig(
                direction(JournalLevel.FULL, Map.of("404", JournalLevel.NONE)),
                direction(JournalLevel.NONE, null));

        assertThat(config.isAtLeastMetadata(404)).isTrue();
    }
}
