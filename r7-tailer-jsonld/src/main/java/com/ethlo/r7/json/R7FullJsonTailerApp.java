package com.ethlo.r7.json;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

import com.ethlo.r7.r7f.R7Tailer;

public class R7FullJsonTailerApp
{
    public static void main(String[] args) throws Exception
    {
        final Path logDir = Paths.get("/tmp/r7/journal");
        final Path auditFile = Paths.get("/tmp/r7/audit_trail.jsonl");

        OutputStream out = new BufferedOutputStream(Files.newOutputStream(auditFile,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND
        ));

        final DebugJsonWriter jsonWriter = new DebugJsonWriter(out, false);
        final R7Tailer tailer = new R7Tailer(logDir, Duration.ofHours(1), jsonWriter);
        System.out.println("Writing JSONEachRow to: " + auditFile);

        while (!Thread.currentThread().isInterrupted())
        {
            tailer.runTick();
            Thread.sleep(1_000);
        }
    }
}