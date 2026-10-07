package com.ethlo.r7.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.util.TestFingerprints;

/**
 * Journal-level rules are enforced when routes load. They were once defined but never run, so a
 * FULL request lowered by an override - which the journal cannot honour, because the body is
 * written before the status exists - loaded without complaint.
 */
class JournalConfigLoadValidationTest
{
    @TempDir
    Path dir;

    private void load(final String journal) throws IOException
    {
        final Path file = this.dir.resolve("routes.yaml");
        Files.writeString(file, """
                version: test
                routes:
                  - id: r
                    match:
                      - PathPrefix:
                          prefix: /r
                    upstream:
                      targets:
                        - url: http://localhost:1
                    journal:
                %s
                """.formatted(journal.indent(6)));
        new ConfigurationManager(TestFingerprints.engine()).load(ConfigurationManager.load(file, RoutesDefinition.class), new RouteRegistry());
    }

    @Test
    void aFullRequestLoweredByAnOverrideIsRefusedAtLoad()
    {
        assertThatThrownBy(() -> load("""
                request:
                  level: FULL
                  status_overrides:
                    404: NONE
                """))
                .hasMessageContaining("status_overrides")
                .hasMessageContaining("routes.r.journal");
    }

    @Test
    void validOverridesStillLoad()
    {
        assertThatCode(() -> load("""
                request:
                  level: HEADERS
                  status_overrides:
                    404: NONE
                response:
                  level: METADATA
                """)).doesNotThrowAnyException();
    }
}
