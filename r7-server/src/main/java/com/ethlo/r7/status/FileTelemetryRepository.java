package com.ethlo.r7.status;

import com.ethlo.r7.status.dto.RouteMetricsDto;
import com.ethlo.r7.util.JsonUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public final class FileTelemetryRepository implements TelemetryRepository
{
    private final Path workingDir;
    private final Path targetFile;

    public FileTelemetryRepository(Path workingDir)
    {
        this.workingDir = workingDir;
        this.targetFile = workingDir.resolve("telemetry.json");
    }

    @Override
    public void save(final List<RouteMetricsDto> metrics)
    {
        // A temporary file of its own for each save: two writers sharing one (two gateways on the
        // same work_dir, as the integration tests run them) interleave their bytes, and the
        // corrupt result is moved into place and fails the next startup.
        Path tempFile = null;
        try
        {
            tempFile = Files.createTempFile(workingDir, "telemetry.json.", ".tmp");
            JsonUtil.writeValue(tempFile, metrics);
            Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
        finally
        {
            if (tempFile != null)
            {
                try
                {
                    Files.deleteIfExists(tempFile);
                }
                catch (IOException ignored)
                {
                    // Best effort: a failed save already reported its own error
                }
            }
        }
    }

    @Override
    public List<RouteMetricsDto> load()
    {
        if (Files.exists(targetFile))
        {
            return JsonUtil.readValue(targetFile, JsonUtil.collectionType(List.class, RouteMetricsDto.class));
        }
        return List.of();
    }
}