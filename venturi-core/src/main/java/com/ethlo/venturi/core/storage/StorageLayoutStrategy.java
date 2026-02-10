package com.ethlo.venturi.core.storage;

import java.nio.file.Path;

public interface StorageLayoutStrategy {
    /**
     * Resolves and ensures the directory for a specific request exists.
     * * @param baseDir The root storage directory
     * @param requestId The unique ID of the exchange
     * @return The path to the directory where files for this ID should be stored
     */
    Path resolveAndPrepare(Path baseDir, String requestId);
}