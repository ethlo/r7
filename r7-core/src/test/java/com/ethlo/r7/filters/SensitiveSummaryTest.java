package com.ethlo.r7.filters;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.predicates.RequestHeaderFactory;
import com.ethlo.r7.spi.EngineContext;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.util.SensitiveConfig;

/**
 * Summaries are shown on the management page. A configured value there can be a shared secret,
 * so it is masked as the route configuration view masks it, rather than fingerprinted, so a filter
 * or predicate never needs the journal's key: these are built with an empty engine context.
 */
class SensitiveSummaryTest
{
    @Test
    void aValueSetByAFilterIsMasked()
    {
        final Object filter = new SetRequestHeaderFactory().create(
                new SetRequestHeaderFactory.Config("X-Api-Key", "s3cret-token"),
                new FilterCreationContext("route", new EngineContext(Map.of())));

        assertThat(((ShortInfo) filter).summary())
                .isEqualTo("SetRequestHeader: X-Api-Key: " + SensitiveConfig.MASK)
                .doesNotContain("s3cret");
    }

    @Test
    void aValueAPredicateComparesAgainstIsMasked()
    {
        final Object predicate = new RequestHeaderFactory().create(new RequestHeaderFactory.Config("X-Api-Key", "s3cret-token"));

        assertThat(((ShortInfo) predicate).summary())
                .isEqualTo("RequestHeader: X-Api-Key == " + SensitiveConfig.MASK)
                .doesNotContain("s3cret");
    }
}
