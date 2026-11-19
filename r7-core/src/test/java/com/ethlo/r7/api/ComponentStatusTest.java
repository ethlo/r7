package com.ethlo.r7.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ComponentStatusTest
{
    @Test
    void valuesKeepTheirOrderAndCannotBeChangedAfterwards()
    {
        final Map<String, Long> values = new LinkedHashMap<>();
        values.put("zeta", 1L);
        values.put("alpha", 2L);
        final ComponentStatus status = new ComponentStatus(ComponentStatus.Health.WARN, "detail", values);
        values.put("late", 3L);

        assertThat(status.values()).containsExactly(Map.entry("zeta", 1L), Map.entry("alpha", 2L));
        assertThatThrownBy(() -> status.values().put("x", 1L)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void noValuesIsAnEmptyMap()
    {
        assertThat(ComponentStatus.ok().values()).isEmpty();
        assertThat(ComponentStatus.ok().health()).isEqualTo(ComponentStatus.Health.OK);
    }

    /**
     * A value name becomes a Prometheus label and a dashboard key: a name built from request
     * data would make series without bound, so only plain snake_case names are taken.
     */
    @Test
    void aValueNameMustBeSnakeCase()
    {
        assertThatThrownBy(() -> new ComponentStatus(ComponentStatus.Health.OK, null, Map.of("Rejected", 1L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ComponentStatus(ComponentStatus.Health.OK, null, Map.of("client 10.0.0.1", 1L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ComponentStatus(ComponentStatus.Health.OK, null, Map.of("a".repeat(65), 1L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ComponentStatus(ComponentStatus.Health.OK, null, Map.of("rejected_requests_2", 1L)).values()).hasSize(1);
    }

    @Test
    void healthIsRequired()
    {
        assertThatThrownBy(() -> new ComponentStatus(null, null, null)).isInstanceOf(NullPointerException.class);
    }
}
