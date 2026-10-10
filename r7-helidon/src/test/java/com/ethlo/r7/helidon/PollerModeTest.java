package com.ethlo.r7.helidon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Which I/O poller mode {@link R7Helidon#choosePollerMode} sets. Only the property is checked:
 * the JDK's poller has long started in this JVM, so the value changes nothing here.
 */
class PollerModeTest
{
    private static final String PROPERTY = "jdk.pollerMode";
    private String saved;

    @BeforeEach
    void clear()
    {
        saved = System.getProperty(PROPERTY);
        System.clearProperty(PROPERTY);
    }

    @AfterEach
    void restore()
    {
        if (saved == null)
        {
            System.clearProperty(PROPERTY);
        }
        else
        {
            System.setProperty(PROPERTY, saved);
        }
    }

    @Test
    void platformPollersByDefault()
    {
        R7Helidon.choosePollerMode(null);
        assertThat(System.getProperty(PROPERTY)).isEqualTo("1");
    }

    @Test
    void blankEnvironmentIsTheDefault()
    {
        R7Helidon.choosePollerMode(" ");
        assertThat(System.getProperty(PROPERTY)).isEqualTo("1");
    }

    @Test
    void environmentChoosesTheMode()
    {
        R7Helidon.choosePollerMode(" 3 ");
        assertThat(System.getProperty(PROPERTY)).isEqualTo("3");
    }

    @Test
    void explicitPropertyWins()
    {
        System.setProperty(PROPERTY, "2");
        R7Helidon.choosePollerMode("3");
        assertThat(System.getProperty(PROPERTY)).isEqualTo("2");
    }

    @Test
    void unknownModeFailsStartup()
    {
        assertThatThrownBy(() -> R7Helidon.choosePollerMode("4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("R7_POLLER_MODE must be 1, 2 or 3");
        assertThat(System.getProperty(PROPERTY)).isNull();
    }
}
