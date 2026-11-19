package com.ethlo.r7.tailer;

import java.time.Duration;

/**
 * The settings every tailer has, whatever it writes to: where the journals are, where its
 * progress is kept, and how long it waits between reads. A tailer's config record implements
 * this next to its output settings; these keys sit at the top level ({@code journal_dir},
 * {@code checkpoint_dir}, {@code poll_interval}).
 * <p>
 * Output settings are deliberately not here. A tailer that ships straight to a network endpoint
 * has no files to roll; one that writes local files takes {@code RollingFilesConfig} from
 * {@code r7-tailer-files} as well.
 */
public interface TailerConfig
{
    String DEFAULT_JOURNAL_DIR = "/journals";

    /**
     * Not under the journal directory, which a tailer mounts read-only, and not under any output
     * directory: a reaper reads this directory to learn which segments the tailer is done with,
     * and must not need access to the output to do it.
     */
    String DEFAULT_CHECKPOINT_DIR = "/checkpoints";

    Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);

    String journalDir();

    String checkpointDir();

    /**
     * The longest the tailer waits between reads; it wakes as soon as the gateway commits.
     */
    Duration pollInterval();
}
