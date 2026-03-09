package com.ethlo.r7.vlf;

import com.ethlo.r7.vlf.fbs.Header;
import com.ethlo.r7.vlf.fbs.UpstreamRequest;

public class FbsUpstreamRequestHeaders extends AbstractFbsGatewayHeaders
{
    private final UpstreamRequest event;

    public FbsUpstreamRequestHeaders(UpstreamRequest event)
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