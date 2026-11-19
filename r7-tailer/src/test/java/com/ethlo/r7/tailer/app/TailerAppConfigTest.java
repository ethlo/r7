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
    void noFileMeansBodiesInWarcAndEveryExchangeAsJsonOnStandardOutput()
    {
        final TailerAppConfig config = TailerRunner.loadConfig(dir.resolve("tailer.yaml"), TailerAppConfig.class, TailerAppConfig::standard);
        assertThat(config.json().enabled()).isTrue();
        assertThat(config.json().output()).isEqualTo(JsonOutputConfig.Output.STDOUT);
        assertThat(config.json().bodies()).isEqualTo(JsonOutputConfig.Bodies.NONE);
        assertThat(config.warc().enabled()).isTrue();
        assertThat(config.warc().exchanges()).isEqualTo(WarcOutputConfig.Exchanges.WITH_BODY);
        assertThat(config.warc().bodies()).isTrue();
    }

    @Test
    void outputsAreNestedBlocks() throws IOException
    {
        final TailerAppConfig config = load("""
                warc:
                  exchanges: all
                  max_file_size: 2gb
                json:
                  output: file
                  output_dir: /var/r7/json
                """);
        assertThat(config.warc().exchanges()).isEqualTo(WarcOutputConfig.Exchanges.ALL);
        assertThat(config.warc().maxFileSize().bytes()).isEqualTo(2L * 1024 * 1024 * 1024);
        assertThat(config.json().outputDir()).isEqualTo("/var/r7/json");
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
                warc:
                  enabled: false
                json:
                  enabled: false
                """))
                .hasMessageContaining("at least one of warc and json");
    }

    /**
     * The JSON output always writes one compact object per line and leaves out fields with no
     * value; the options that changed that are gone, and a config still naming them is refused
     * rather than silently ignored.
     */
    @Test
    void theRemovedJsonLayoutOptionsAreRefused()
    {
        assertThatThrownBy(() -> load("""
                json:
                  pretty_print: true
                """))
                .hasMessageContaining("Unknown configuration option: 'pretty_print' at [json.pretty_print]");
        assertThatThrownBy(() -> load("""
                json:
                  hide_empty_fields: false
                """))
                .hasMessageContaining("Unknown configuration option: 'hide_empty_fields' at [json.hide_empty_fields]");
    }

    @Test
    void bodyFilesBesideHeadersOnlyWarc() throws IOException
    {
        final TailerAppConfig config = load("""
                warc:
                  bodies: false
                json:
                  bodies: files
                  body_files:
                    output_dir: /var/r7/bodies
                    max_file_age: 5m
                """);
        assertThat(config.json().bodies()).isEqualTo(JsonOutputConfig.Bodies.FILES);
        assertThat(config.json().bodyFiles().outputDir()).isEqualTo("/var/r7/bodies");
        assertThat(config.json().bodyFiles().maxFileAge()).hasMinutes(5);
        assertThat(config.json().bodyFiles().zstdLevel()).isEqualTo(3);
        assertThat(config.json().bodyFiles().maxFileSize().bytes()).isEqualTo(1024L * 1024 * 1024);
    }

    /**
     * A body is stored once: with WARC records holding every body, the body files would stay
     * empty.
     */
    @Test
    void bodyFilesAreRefusedBesideWarcThatStoresBodies()
    {
        assertThatThrownBy(() -> load("""
                json:
                  bodies: files
                """))
                .hasMessageContaining("[json.bodies] files cannot be combined with a WARC output that stores bodies");
    }

    @Test
    void bodyFileSettingsNeedBodyFiles()
    {
        assertThatThrownBy(() -> load("""
                warc:
                  enabled: false
                json:
                  bodies: inline
                  body_files:
                    output_dir: /bodies
                """))
                .hasMessageContaining("[json.body_files] applies only to bodies: files");
        assertThatThrownBy(() -> load("""
                warc:
                  enabled: false
                json:
                  bodies: files
                  body_files:
                    zstd_level: 0
                    max_file_size: 1kb
                """))
                .hasMessageContaining("[json.body_files.zstd_level] must be between 1 and 22")
                .hasMessageContaining("[json.body_files.max_file_size]");
    }

    /**
     * {@code bodies} was a boolean; a config still saying {@code true} is refused rather than
     * guessed at.
     */
    @Test
    void theBooleanBodiesSettingIsRefused()
    {
        assertThatThrownBy(() -> load("""
                warc:
                  enabled: false
                json:
                  bodies: true
                """))
                .hasMessageContaining("'true' is now 'inline'");
    }

    private TailerAppConfig load(final String yaml) throws IOException
    {
        final Path file = Files.writeString(dir.resolve("tailer.yaml"), yaml);
        return TailerRunner.loadConfig(file, TailerAppConfig.class, TailerAppConfig::standard);
    }
}
