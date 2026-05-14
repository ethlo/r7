package com.ethlo.r7.vlf;

import com.ethlo.r7.vlf.fbs.Header;
import com.ethlo.r7.vlf.fbs.UpstreamRequest;
import com.ethlo.r7.vlf.fbs.UpstreamResponse;

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