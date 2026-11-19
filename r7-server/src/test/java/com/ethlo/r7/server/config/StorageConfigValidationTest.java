package com.ethlo.r7.server.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.ShardedJournalWriter;
import com.ethlo.r7.config.model.DataSize;
import com.ethlo.r7.r7f.R7fJournalProvider;
import com.ethlo.r7.validation.ValidationResult;

/**
 * Storage settings whose bounds are enforced somewhere other than validation: a shard count
 * that is not a power of two is refused by {@link ShardedJournalWriter}'s constructor, and a
 * shard size outside the segment bounds by {@link R7fJournalProvider}'s. Those used to be the
 * first things to notice a bad value, so a typo in the configuration surfaced as a stack trace
 * during startup rather than as a validation error naming the field.
 * <p>
 * Each bound therefore has a test asserting validation agrees with the constructor it fronts. A
 * check placed in front of another check is only worth having while the two cannot drift.
 */
class StorageConfigValidationTest
{
    @Test
    void aShardCountThatIsNotAPowerOfTwoIsRefusedWithTheNearestValidValues()
    {
        final List<String> errors = errorsFor(10);

        assertEquals(1, errors.size(), () -> "expected exactly one error, got " + errors);
        final String error = errors.getFirst();
        for (final String expected : List.of("shard_count", "power of two", "10", "8", "16"))
        {
            assertTrue(error.contains(expected), () -> "error should mention '" + expected + "': " + error);
        }
    }

    /**
     * The default follows the CPU count, so it is the one shard count no operator typed: it has
     * to pass the same validation as a configured one on every host, from one CPU to many.
     */
    @Test
    void theDefaultShardCountFollowsTheCpusAndIsAlwaysValid()
    {
        assertEquals(2, ServerConfig.StorageConfig.defaultShardCount(1));
        assertEquals(2, ServerConfig.StorageConfig.defaultShardCount(3));
        assertEquals(4, ServerConfig.StorageConfig.defaultShardCount(4));
        assertEquals(8, ServerConfig.StorageConfig.defaultShardCount(12));
        assertEquals(16, ServerConfig.StorageConfig.defaultShardCount(16));
        assertEquals(16, ServerConfig.StorageConfig.defaultShardCount(96));
        for (int cpus = 1; cpus <= 256; cpus++)
        {
            final int cores = cpus;
            final int count = ServerConfig.StorageConfig.defaultShardCount(cores);
            assertTrue(errorsFor(count).isEmpty(), () -> cores + " CPUs gave an invalid default of " + count);
        }
    }

    /**
     * The check has to agree with the constructor it exists to front, or it would trade one
     * confusing failure for another.
     */
    @Test
    void everyValueValidationAcceptsIsAlsoAcceptedByTheWriter()
    {
        for (int shardCount = 1; shardCount <= 64; shardCount++)
        {
            final int count = shardCount;
            if (errorsFor(count).isEmpty())
            {
                assertDoesNotThrow(() -> new ShardedJournalWriter<>(count, shard -> null),
                        () -> "validation accepted " + count + ", so the writer must too");
            }
            else
            {
                assertThrows(IllegalArgumentException.class,
                        () -> new ShardedJournalWriter<>(count, shard -> null),
                        () -> "validation rejected " + count + ", so the writer must too");
            }
        }
    }

    @Test
    void zeroAndNegativeAreStillRejectedAsBefore()
    {
        assertTrue(errorsFor(0).getFirst().contains(">= 1"));
        assertTrue(errorsFor(-4).getFirst().contains(">= 1"));
    }

    @Test
    void aShardSizeBelowTheSegmentMinimumIsRefused()
    {
        final List<String> errors = errorsForSize(DataSize.ofKilobytes(1));

        assertEquals(1, errors.size(), () -> "expected exactly one error, got " + errors);
        final String error = errors.getFirst();
        assertTrue(error.contains("shard_size"), () -> error);
        assertTrue(error.contains(Long.toString(R7fJournalProvider.MIN_SEGMENT_SIZE)), () -> error);
    }

    @Test
    void aShardSizeBeyondWhatReadersCanAddressIsRefused()
    {
        final List<String> errors = errorsForSize(DataSize.ofGigabytes(4));

        assertEquals(1, errors.size(), () -> "expected exactly one error, got " + errors);
        assertTrue(errors.getFirst().contains("shard_size"), () -> errors.getFirst());
    }

    /**
     * Journal segments are cut into fixed blocks, so a size that is not a whole number of them
     * is named here rather than refused by the provider at startup.
     */
    @Test
    void aShardSizeThatIsNotAWholeNumberOfBlocksIsRefused()
    {
        final List<String> errors = errorsForSize(DataSize.ofKilobytes(100));

        assertEquals(1, errors.size(), () -> "expected exactly one error, got " + errors);
        assertTrue(errors.getFirst().contains("shard_size"), () -> errors.getFirst());
        assertTrue(errors.getFirst().contains("multiple of " + R7fJournalProvider.SEGMENT_SIZE_MULTIPLE), () -> errors.getFirst());
        assertTrue(errorsForSize(DataSize.ofKilobytes(96)).isEmpty(), "a whole number of blocks is accepted");
    }

    /**
     * As with shard_count, the check is only worth having if it agrees with the constructor it
     * fronts — a value validation accepts must not then be refused during startup.
     */
    @Test
    void theShardSizeBoundsMatchTheProviderExactly()
    {
        assertTrue(errorsForSize(DataSize.ofBytes(R7fJournalProvider.MIN_SEGMENT_SIZE)).isEmpty(),
                "the minimum itself must be accepted");
        assertFalse(errorsForSize(DataSize.ofBytes(R7fJournalProvider.MIN_SEGMENT_SIZE - 1)).isEmpty(),
                "one byte below the minimum must be refused");
        assertTrue(errorsForSize(DataSize.ofBytes(R7fJournalProvider.MAX_SEGMENT_SIZE)).isEmpty(),
                "the maximum itself must be accepted");
        assertFalse(errorsForSize(DataSize.ofBytes(R7fJournalProvider.MAX_SEGMENT_SIZE + 1)).isEmpty(),
                "one byte above the maximum must be refused");
        assertFalse(errorsForSize(DataSize.ofBytes(R7fJournalProvider.MAX_SEGMENT_SIZE + R7fJournalProvider.SEGMENT_SIZE_MULTIPLE)).isEmpty(),
                "one block above the maximum must be refused");
    }

    @Test
    void theDefaultShardCountAndSizeAreValid()
    {
        assertEquals(List.of(), errorsForConfig(new ServerConfig.StorageConfig("journals", null, null, null, null, null, null)));
    }

    private static List<String> errorsFor(final int shardCount)
    {
        return errorsForConfig(new ServerConfig.StorageConfig("journals", shardCount, null, null, null, null, null));
    }

    @Test
    void compressionIsZstdAtLevelOneByDefault()
    {
        final ServerConfig.StorageConfig storage = new ServerConfig.StorageConfig("journals", null, null, null, null, null, null);
        assertEquals("zstd", storage.compression());
        assertEquals(1, storage.journalCompressionLevel());
    }

    @Test
    void compressionNoneMeansLevelZeroWhateverTheLevelSays()
    {
        final ServerConfig.StorageConfig storage = new ServerConfig.StorageConfig("journals", null, null, null, null, "None", 5);
        assertEquals(List.of(), errorsForConfig(storage));
        assertEquals(0, storage.journalCompressionLevel());
    }

    @Test
    void anUnknownCompressionIsRefusedByName()
    {
        final List<String> errors = errorsForConfig(new ServerConfig.StorageConfig("journals", null, null, null, null, "gzip", null));
        assertEquals(1, errors.size(), () -> "expected exactly one error, got " + errors);
        assertTrue(errors.getFirst().contains("compression"), () -> errors.getFirst());
    }

    /**
     * The bounds are the provider's own, so a level validation accepts is never refused when the
     * journal is constructed.
     */
    @Test
    void theCompressionLevelBoundsMatchTheProvider()
    {
        assertTrue(errorsForLevel(R7fJournalProvider.MIN_COMPRESSION_LEVEL).isEmpty());
        assertTrue(errorsForLevel(R7fJournalProvider.MAX_COMPRESSION_LEVEL).isEmpty());
        assertFalse(errorsForLevel(R7fJournalProvider.MIN_COMPRESSION_LEVEL - 1).isEmpty());
        final List<String> errors = errorsForLevel(R7fJournalProvider.MAX_COMPRESSION_LEVEL + 1);
        assertEquals(1, errors.size(), () -> "expected exactly one error, got " + errors);
        assertTrue(errors.getFirst().contains("compression_level"), () -> errors.getFirst());
    }

    private static List<String> errorsForLevel(final int level)
    {
        return errorsForConfig(new ServerConfig.StorageConfig("journals", null, null, null, null, "zstd", level));
    }

    private static List<String> errorsForSize(final DataSize shardSize)
    {
        return errorsForConfig(new ServerConfig.StorageConfig("journals", null, shardSize, null, null, null, null));
    }

    private static List<String> errorsForConfig(final ServerConfig.StorageConfig config)
    {
        final ValidationResult result = new ValidationResult();
        config.validate(result);
        return result.getErrors();
    }
}
