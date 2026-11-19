package com.ethlo.r7.status;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.ComponentStatus;
import com.ethlo.r7.api.StatusReporting;

class ComponentStatusesTest
{
    @Test
    void aComponentThatDoesNotReportHasNoStatus()
    {
        assertThat(ComponentStatuses.of(new Object())).isNull();
        assertThat(ComponentStatuses.of(null)).isNull();
    }

    /**
     * One filter whose status() throws must not cost the snapshot every other route's data, and
     * a status that cannot be read is itself worth an operator's attention.
     */
    @Test
    void aStatusThatThrowsIsReportedAsAnError()
    {
        final StatusReporting broken = () ->
        {
            throw new IllegalStateException("boom");
        };
        final ComponentStatus status = ComponentStatuses.of(broken);
        assertThat(status.health()).isEqualTo(ComponentStatus.Health.ERROR);
        assertThat(status.detail()).contains("boom");
    }
}
