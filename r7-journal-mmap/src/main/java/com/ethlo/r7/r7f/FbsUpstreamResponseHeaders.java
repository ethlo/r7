package com.ethlo.r7.r7f;

import com.ethlo.r7.r7f.fbs.Header;
import com.ethlo.r7.r7f.fbs.UpstreamResponse;

public class FbsUpstreamResponseHeaders extends AbstractFbsGatewayHeaders
{
    private final UpstreamResponse event;

    public FbsUpstreamResponseHeaders(UpstreamResponse event)
    {
        super(event.headersLength());
        this.event = event;
    }

    @Override
    protected void getHeader(Header target, int index)
    {
        event.headers(target, index);
    }
}