package com.ethlo.r7.tailer;

import java.io.IOException;

/**
 * What {@link TailerRunner} needs from a tailer's output besides the exchanges themselves: a
 * hook after each read, and a close on shutdown.
 */
@FunctionalInterface
public interface TailerOutput extends AutoCloseable
{
    /**
     * Runs after every read, including one that found nothing: a quiet journal must still let
     * an output seal a file that has reached its age limit.
     */
    default void afterTick() throws IOException
    {
    }

    /**
     * Flushes or seals whatever is pending. Runs on shutdown, after the checkpoint is saved.
     */
    @Override
    void close() throws IOException;
}
