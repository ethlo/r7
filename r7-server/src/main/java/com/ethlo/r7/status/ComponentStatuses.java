package com.ethlo.r7.status;

import com.ethlo.r7.api.ComponentStatus;
import com.ethlo.r7.api.StatusReporting;

/**
 * Asks a route's components how they are doing, for the management snapshot.
 */
public final class ComponentStatuses
{
    private ComponentStatuses()
    {
    }

    /**
     * @return the component's status, or null when it reports none. A component whose
     * {@code status()} throws is reported as an error rather than breaking the snapshot every
     * other component is in.
     */
    public static ComponentStatus of(final Object component)
    {
        if (!(component instanceof StatusReporting reporting))
        {
            return null;
        }
        try
        {
            return reporting.status();
        }
        catch (final RuntimeException e)
        {
            return new ComponentStatus(ComponentStatus.Health.ERROR, "Status unavailable: " + e, null);
        }
    }
}
