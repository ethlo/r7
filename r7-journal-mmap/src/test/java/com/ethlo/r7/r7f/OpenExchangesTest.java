package com.ethlo.r7.r7f;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.Test;

class OpenExchangesTest
{
    /**
     * A request id can come from a client header, so it may hold any separator a list
     * would use. Each one survives being written and read back exactly, and the per-segment
     * checkpoints beside them are left for the tailer.
     */
    @Test
    void resumePointsSurviveTheCheckpointFileWhateverTheRequestIds() throws IOException
    {
        final Set<String> ids = Set.of("plain", "a,b", "c:d", "e=f", "g h", "line\nbreak", "#hash", "\\back");
        final OpenExchanges.Start start = new OpenExchanges.Start(3, 17L, 40_960L, 42);

        final Properties written = new Properties();
        written.setProperty("journal-3-17", "50000:60");
        OpenExchanges.write(Map.of(3, new OpenExchanges.Resume(start, ids)), written);
        final StringWriter file = new StringWriter();
        written.store(file, null);

        final Properties loaded = new Properties();
        loaded.load(new StringReader(file.toString()));
        final List<String> problems = new ArrayList<>();
        final Map<Integer, OpenExchanges.Resume> read = OpenExchanges.read(loaded, problems);

        assertThat(problems).isEmpty();
        assertThat(read).containsOnlyKeys(3);
        assertThat(read.get(3).from()).isEqualTo(start);
        assertThat(read.get(3).requestIds()).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(loaded.stringPropertyNames()).containsExactly("journal-3-17");
    }

    @Test
    void anUnreadableRecordIsReportedAndDropped()
    {
        final Properties loaded = new Properties();
        loaded.setProperty("open.1", "not-a-start");
        loaded.setProperty("open.1.0", "id");
        loaded.setProperty("open.2.0", "orphan-id");

        final List<String> problems = new ArrayList<>();
        assertThat(OpenExchanges.read(loaded, problems)).isEmpty();
        // The bad start, the ids it leaves without one, and the ids of a shard with no start.
        assertThat(problems).hasSize(3);
        assertThat(loaded).isEmpty();
    }
}
