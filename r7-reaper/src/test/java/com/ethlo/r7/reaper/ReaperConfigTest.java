package com.ethlo.r7.reaper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.validation.ValidationResult;

class ReaperConfigTest
{
    @Test
    void standardConfigResolvesDocumentedDefaults()
    {
        final ReaperConfig config = ReaperConfig.standard();

        assertThat(config.journalDir()).isEqualTo("/journals");
        assertThat(config.ttl()).isEqualTo(Duration.ofDays(7));
        assertThat(config.pollInterval()).isEqualTo(Duration.ofMinutes(1));

        final ValidationResult result = new ValidationResult();
        config.validate(result);
        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void rejectsNonPositiveTtl()
    {
        final ReaperConfig config = new ReaperConfig(null, Duration.ZERO, null);

        final ValidationResult result = new ValidationResult();
        config.validate(result);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("ttl"));
    }

    @Test
    void rejectsNonPositivePollInterval()
    {
        final ReaperConfig config = new ReaperConfig(null, null, Duration.ofSeconds(-1));

        final ValidationResult result = new ValidationResult();
        config.validate(result);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("poll_interval"));
    }

    @Test
    void rejectsBlankJournalDir()
    {
        final ReaperConfig config = new ReaperConfig("   ", null, null);

        final ValidationResult result = new ValidationResult();
        config.validate(result);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("journal_dir"));
    }
}
