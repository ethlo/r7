package com.ethlo.venturi.undertow;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

import com.ethlo.venturi.core.storage.json.ClickHouseJsonEachRowWriter;
import com.ethlo.venturi.mmap.VenturiTailer;

public class VenturiTailerApp
{
    public static void main(String[] args) throws Exception
    {
        Path logDir = Paths.get("/tmp/venturi");
        Path auditFile = Paths.get("/tmp/venturi/audit_trail.jsonl");

        // 1. The Output Sink (Jackson 3)
        // We use a BufferedOutputStream for the RAID1 disks to ensure large sequential writes
        OutputStream out = new BufferedOutputStream(Files.newOutputStream(auditFile,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND
        ));
        ClickHouseJsonEachRowWriter jsonWriter = new ClickHouseJsonEachRowWriter(out);

        // 3. The Management Layer (File Watcher)
        VenturiTailer tailer = new VenturiTailer(logDir, Duration.ofMinutes(1), jsonWriter);

        System.out.println("🚀 Venturi Tailer Live. Watching SSD journals...");
        System.out.println("📂 Writing JSONEachRow to: " + auditFile);

        while (!Thread.currentThread().isInterrupted())
        {
            tailer.runTick();
            // A 500ms sleep is a good balance for the i5's interrupt budget
            Thread.sleep(500);
        }
    }
}