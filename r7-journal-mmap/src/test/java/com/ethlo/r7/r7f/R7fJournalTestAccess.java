package com.ethlo.r7.r7f;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;

/**
 * What tests outside this package need of {@link R7fJournal}'s internals.
 */
public final class R7fJournalTestAccess
{
    private R7fJournalTestAccess()
    {
    }

    public static int stageSize()
    {
        return R7fJournal.STAGE_SIZE;
    }

    /** See {@link R7fJournal#holdBatchesUntilFull()}. */
    public static R7fJournal holdBatchesUntilFull(final R7fJournal journal)
    {
        journal.holdBatchesUntilFull();
        return journal;
    }

    /**
     * Makes the active segment refuse writes, as a mapping that has gone bad does: the next
     * batch the writer thread places fails.
     */
    public static void breakSegment(final R7fJournal journal)
    {
        try
        {
            final Field field = R7fJournal.class.getDeclaredField("segment");
            field.setAccessible(true);
            synchronized (journal)
            {
                field.set(journal, ((MemorySegment) field.get(journal)).asReadOnly());
            }
        }
        catch (final ReflectiveOperationException e)
        {
            throw new IllegalStateException(e);
        }
    }
}
