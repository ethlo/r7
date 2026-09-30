package com.ethlo.r7.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.status.dto.LatencyDto;

class LatencyHistogramTest
{
    @Test
    void everyDurationFallsInsideItsBucketsBounds()
    {
        for (long micros = 0; micros < 1 << 24; micros = micros < 64 ? micros + 1 : micros + micros / 7)
        {
            final int bucket = LatencyHistogram.bucketOf(TimeUnit.MICROSECONDS.toNanos(micros));
            assertThat(bucket).isBetween(0, LatencyHistogram.BUCKET_COUNT - 2);
            assertThat(LatencyHistogram.upperBoundMicros(bucket)).as("upper bound of %dµs", micros).isGreaterThan(micros);
            final long lowerBound = bucket == 0 ? 0 : LatencyHistogram.upperBoundMicros(bucket - 1);
            assertThat(lowerBound).as("lower bound of %dµs", micros).isLessThanOrEqualTo(micros);
        }
    }

    @Test
    void reportedValueIsWithinAQuarterOfTheTrueOne()
    {
        for (long micros = 4; micros < 1 << 24; micros += micros / 3)
        {
            final long reported = LatencyHistogram.upperBoundMicros(LatencyHistogram.bucketOf(TimeUnit.MICROSECONDS.toNanos(micros)));
            assertThat((double) reported / micros).isLessThanOrEqualTo(1.25);
        }
    }

    @Test
    void durationsBeyondTheRangeLandInTheOverflowBucket()
    {
        assertThat(LatencyHistogram.bucketOf(TimeUnit.MINUTES.toNanos(5))).isEqualTo(LatencyHistogram.BUCKET_COUNT - 1);
        assertThat(LatencyHistogram.bucketOf(-1)).isZero();
    }

    @Test
    void windowReportsPercentilesOfWhatWasRecordedSinceItsTicks()
    {
        final LatencyWindow window = new LatencyWindow(4);
        for (int i = 0; i < 98; i++)
        {
            window.record(TimeUnit.MILLISECONDS.toNanos(1));
        }
        window.record(TimeUnit.MILLISECONDS.toNanos(50));
        window.record(TimeUnit.MILLISECONDS.toNanos(50));
        window.tick();

        final LatencyDto latency = window.snapshot();
        assertThat(latency.windowSamples()).isEqualTo(100);
        assertThat(latency.p50Micros()).isBetween(1_000L, 1_250L);
        assertThat(latency.p99Micros()).isBetween(50_000L, 62_500L);
        assertThat(latency.p99SeriesMicros()).hasSize(4);
        assertThat(latency.p99SeriesMicros()[3]).isEqualTo(latency.p99Micros());

        // Once its slot is overwritten, a tick's samples leave the window
        for (int i = 0; i < 4; i++)
        {
            window.tick();
        }
        assertThat(window.snapshot().windowSamples()).isZero();
        assertThat(window.snapshot().p99Micros()).isZero();
    }
}
