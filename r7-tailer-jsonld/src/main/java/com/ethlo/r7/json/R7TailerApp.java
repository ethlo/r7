package com.ethlo.r7.json;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

import com.ethlo.r7.vlf.R7Tailer;

public class R7TailerApp
{
    public static void main(String[] args) throws Exception
    {
        Path logDir = Paths.get("/tmp/r7");
        Path auditFile = Paths.get("/tmp/r7/audit_trail.jsonl");

        // 1. The Output Sink (Jackson 3)
        // We use a BufferedOutputStream for the RAID1 disks to ensure large sequential writes
        OutputStream out = new BufferedOutputStream(Files.newOutputStream(auditFile,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND
        ));
        //ClickHouseJsonEachRowWriter jsonWriter = new ClickHouseJsonEachRowWriter(out);
        final DebugJsonWriter jsonWriter = new DebugJsonWriter(out, false);

        // 3. The Management Layer (File Watcher)
        R7Tailer tailer = new R7Tailer(logDir, Duration.ofHours(1), jsonWriter);

        System.out.println("🚀 R7 Tailer Live. Watching SSD journals...");
        System.out.println("📂 Writing JSONEachRow to: " + auditFile);

        while (!Thread.currentThread().isInterrupted())
        {
            tailer.runTick();
            Thread.sleep(1_000);
        }
    }
}