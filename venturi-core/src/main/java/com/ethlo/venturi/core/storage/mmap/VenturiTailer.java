package com.ethlo.venturi.core.storage.mmap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VenturiTailer
{
    private static final Logger logger = LoggerFactory.getLogger(VenturiTailer.class);
    private static final String CHECKPOINT_FILE = ".venturi_checkpoints";

    private final Map<Path, Long> checkpoints = new HashMap<>();
    private final Path logDir;
    private final JournalEventListener reassembler;
    private final Path checkpointPath;

    public VenturiTailer(Path logDir, ExchangeCompletionListener output)
    {
        this.logDir = logDir;
        this.reassembler = new ExchangeReassembler(output);
        this.checkpointPath = logDir.resolve(CHECKPOINT_FILE);
        loadCheckpoints();
    }

    public void runTick() throws IOException
    {
        try (Stream<Path> s = Files.list(logDir))
        {
            s.filter(p -> p.toString().endsWith(".raw") || p.toString().endsWith(".active"))
                    .sorted()
                    .forEach(path -> {
                        try
                        {
                            processFile(path);

                            // ONLY delete if it's a finished .raw file AND we've reached the end
                            if (path.toString().endsWith(".raw") && isFullyProcessed(path))
                            {
                                Files.delete(path);
                                checkpoints.remove(path);
                            }
                        }
                        catch (IOException e)
                        {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
        saveCheckpoints();
    }

    private boolean isFullyProcessed(Path path) throws IOException
    {
        Long offset = checkpoints.get(path);
        return offset != null && offset >= Files.size(path);
    }

    private void processFile(Path path) throws IOException
    {
        long offset = checkpoints.getOrDefault(path, 0L);

        // If the file was JUST moved/renamed by the writer, Files.size might throw NoSuchFileException
        long fileSize;
        try
        {
            fileSize = Files.size(path);
        }
        catch (NoSuchFileException e)
        {
            return; // It's being rotated, we'll catch it by its new name in the next tick
        }

        if (fileSize <= offset)
        {
            return;
        }

        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r"))
        {
            long fileLength = raf.length();

            // Skip if we've already processed this static .raw file to the end
            if (fileLength <= offset)
            {
                return;
            }

            MappedByteBuffer buffer = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, fileLength);

            buffer.order(ByteOrder.BIG_ENDIAN);
            buffer.position((int) offset);

            if (offset == 0 && buffer.remaining() > 0)
            {
                byte version = buffer.get();
                if (version != Marker.VERSION)
                {
                    logger.error("Version mismatch in {}: expected {}, got {}", path, Marker.VERSION, version);
                }
            }

            JournalDecoder.decode(buffer, reassembler);

            final long progress = buffer.position() - offset;
            if (progress > 0)
            {
                logger.debug("{} bytes processed from {}", progress, path);
            }

            // Update local state
            checkpoints.put(path, (long) buffer.position());
        }
    }

    private void loadCheckpoints()
    {
        if (!Files.exists(checkpointPath)) return;

        try (InputStream in = Files.newInputStream(checkpointPath))
        {
            Properties props = new Properties();
            props.load(in);
            props.forEach((k, v) -> checkpoints.put(Paths.get((String) k), Long.parseLong((String) v)));
            logger.info("Restored {} checkpoints from {}", checkpoints.size(), checkpointPath);
        }
        catch (IOException e)
        {
            logger.error("Could not load checkpoint file: {}", e.getMessage());
        }
    }

    private void saveCheckpoints()
    {
        if (checkpoints.isEmpty()) return;

        Path tempFile = checkpointPath.resolveSibling(CHECKPOINT_FILE + ".tmp");
        try
        {
            Properties props = new Properties();
            checkpoints.forEach((path, pos) -> props.setProperty(path.toString(), pos.toString()));

            try (OutputStream out = Files.newOutputStream(tempFile,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
            ))
            {
                props.store(out, "Venturi Tailer Progress");
            }

            // Atomic move is the secret sauce for crash consistency
            Files.move(tempFile, checkpointPath,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
            );

        }
        catch (IOException e)
        {
            logger.error("Failed to save checkpoints: {}", e.getMessage());
        }
    }
}