package com.ethlo.venturi.vlf;

import com.ethlo.venturi.vlf.fbs.Header;
import com.ethlo.venturi.vlf.fbs.UpstreamRequest;

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