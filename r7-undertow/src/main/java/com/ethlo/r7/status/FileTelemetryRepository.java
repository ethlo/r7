package com.ethlo.r7.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import com.ethlo.r7.status.dto.RouteMetricsDto;
import tools.jackson.databind.ObjectMapper;

public final class FileTelemetryRepository implements TelemetryRepository
{
    private static final ObjectMapper mapper = new ObjectMapper();
    private final Path targetFile;
    private final Path tempFile;

    public FileTelemetryRepository(Path workingDir)
    {
        this.targetFile = workingDir.resolve("telemetry.json");
        this.tempFile = workingDir.resolve("telemetry.json.tmp");
    }

    @Override
    public void save(final List<RouteMetricsDto> metrics)
    {
        mapper.writerWithDefaultPrettyPrinter().writeValue(tempFile, metrics);
        try
        {
            Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<RouteMetricsDto> load()
    {
        if (Files.exists(targetFile))
        {
            return mapper.readValue(targetFile, mapper.getTypeFactory().constructCollectionType(List.class, RouteMetricsDto.class));
        }
        return List.of();
    }
}