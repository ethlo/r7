package com.ethlo.r7.tailer.jsonld;

import java.time.Duration;
import java.util.Optional;

import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

/**
 * {@code jsonld-tailer.yaml} - same {@code r7-config} conventions (snake_case, {@code
 * ${VAR:default}} interpolation, human duration units) as the gateway's {@code
 * routes.yaml}/{@code server.yaml}, so an operator moving between the two never has to learn a
 * second config language. Env-var overrides for a containerized deployment still work exactly
 * as they do for the gateway - interpolate them into the YAML file, e.g. {@code
 * journal_dir: ${JOURNAL_DIR:/journals}} - rather than reading them directly in code.
 * <p>
 * There is deliberately no {@code ttl}/{@code grace_period} here: this tailer never deletes a
 * segment (see {@code TailerMain}, which constructs {@code R7Tailer} with no retention
 * parameters at all - the class has none). Retention is a dedicated reaper's job, applied
 * uniformly to every format-specific tailer sharing the journal directory; mount {@code
 * journal_dir} read-only here to make that enforced rather than merely intended.
 */
public record JsonldTailerConfig(
        String journalDir,
        String checkpointDir,
        String outputPath,
        Boolean prettyPrint,
        Duration pollInterval
) implements ValidatableConfig
{
    public static JsonldTailerConfig standard()
    {
        return new JsonldTailerConfig(null, null, null, null, null);
    }

    @Override
    public String journalDir()
    {
        return Optional.ofNullable(this.journalDir).orElse("/journals");
    }

    @Override
    public String checkpointDir()
    {
        // Not under journalDir(): a secondary tailer (one not responsible for retention -
        // R7Tailer itself never deletes anything, see the class javadoc) must be free to
        // mount journalDir read-only, which a checkpoint file living inside it would rule
        // out. outputPath() is not a reliable fallback directory either - it defaults to
        // stdout - so this gets its own dedicated, always-writable location instead.
        return Optional.ofNullable(this.checkpointDir).orElse("/checkpoints");
    }

    /**
     * {@code -} or {@code stdout} (the default) means standard output, so it can be picked up
     * by whatever log forwarder the deployment already runs.
     */
    @Override
    public String outputPath()
    {
        return Optional.ofNullable(this.outputPath).orElse("-");
    }

    @Override
    public Boolean prettyPrint()
    {
        return Optional.ofNullable(this.prettyPrint).orElse(false);
    }

    @Override
    public Duration pollInterval()
    {
        return Optional.ofNullable(this.pollInterval).orElse(Duration.ofSeconds(1));
    }

    @Override
    public void validate(final ValidationResult result)
    {
        // Nothing to validate: every remaining field is either a plain string/boolean or
        // already range-checked by HumanUnits at parse time.
    }
}
