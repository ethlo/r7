package com.ethlo.r7.tailer.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.tailer.TailerRunner;

class TailerAppConfigTest
{
    @TempDir
    Path dir;

    @Test
    void noFileMeansJsonOnStandardOutputAndNoWarc()
    {
        final TailerAppConfig config = TailerRunner.loadConfig(dir.resolve("tailer.yaml"), TailerAppConfig.class, TailerAppConfig::standard);
        assertThat(config.json().enabled()).isTrue();
        assertThat(config.json().output()).isEqualTo(JsonOutputConfig.Output.STDOUT);
        assertThat(config.warc().enabled()).isFalse();
    }

    @Test
    void outputsAreNestedBlocks() throws IOException
    {
        final TailerAppConfig config = load("""
                warc:
                  enabled: true
                  exchanges: with_body
                  max_file_size: 2gb
                json:
                  output: file
                  output_dir: /var/r7/json
                """);
        assertThat(config.warc().exchanges()).isEqualTo(WarcOutputConfig.Exchanges.WITH_BODY);
        assertThat(config.warc().maxFileSize().bytes()).isEqualTo(2L * 1024 * 1024 * 1024);
        assertThat(config.json().outputDir()).isEqualTo("/var/r7/json");
        assertThat(config.json().bodies()).as("on unless turned off").isTrue();
    }

    @Test
    void errorsNameTheOutputTheyBelongTo()
    {
        assertThatThrownBy(() -> load("""
                warc:
                  enabled: true
                  max_file_size: 1kb
                json:
                  output_dir: /json
                """))
                .hasMessageContaining("[warc.max_file_size]")
                .hasMessageContaining("[json.output]");
    }

    @Test
    void zstdLevelIsBoundedByWhatTheWriterAccepts()
    {
        assertThatThrownBy(() -> load("""
                warc:
                  enabled: true
                  zstd_level: 23
                """))
                .hasMessageContaining("[warc.zstd_level] must be between 1 and 22");
    }

    @Test
    void anOutputMustBeEnabled()
    {
        assertThatThrownBy(() -> load("""
                json:
                  enabled: false
                """))
                .hasMessageContaining("at least one of warc and json");
    }

    @Test
    void prettyPrintedFilesAreRefused()
    {
        assertThatThrownBy(() -> load("""
                json:
                  output: file
                  pretty_print: true
                """))
                .hasMessageContaining("[json.pretty_print]");
    }

    private TailerAppConfig load(final String yaml) throws IOException
    {
        final Path file = Files.writeString(dir.resolve("tailer.yaml"), yaml);
        return TailerRunner.loadConfig(file, TailerAppConfig.class, TailerAppConfig::standard);
    }
}
