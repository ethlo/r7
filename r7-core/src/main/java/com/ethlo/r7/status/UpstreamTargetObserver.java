package com.ethlo.r7.status;

import java.net.URI;

/**
 * Lightweight signaling interface used to notify the underlying
 * proxy engine of upstream target availability changes.
 */
public interface UpstreamTargetObserver
{
    void onTargetUp(URI target);

    void onTargetDown(URI target);
}