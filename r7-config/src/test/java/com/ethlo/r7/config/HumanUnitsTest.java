package com.ethlo.r7.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HumanUnitsTest
{
    @Test
    void aDataSizeIsBytesOrAUnit()
    {
        assertThat(HumanUnits.parseDataSize("1024").bytes()).isEqualTo(1024);
        assertThat(HumanUnits.parseDataSize("64KB").bytes()).isEqualTo(64 * 1024);
        assertThat(HumanUnits.parseDataSize("2gb").bytes()).isEqualTo(2L * 1024 * 1024 * 1024);
    }

    /**
     * A size whose bytes do not fit a long must be refused, never wrapped: 2^54 + 1 KB wraps to
     * 1 KB, which would load as a cap far below the one written.
     */
    @Test
    void aDataSizeTooLargeForALongIsRefusedNotWrapped()
    {
        assertThatThrownBy(() -> HumanUnits.parseDataSize("18014398509481985KB"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("too large");
        assertThatThrownBy(() -> HumanUnits.parseDataSize("99999999999999999999"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("too large");
    }
}
