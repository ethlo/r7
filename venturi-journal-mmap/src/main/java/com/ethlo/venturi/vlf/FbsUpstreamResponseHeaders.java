package com.ethlo.venturi.vlf;

import com.ethlo.venturi.vlf.fbs.Header;
import com.ethlo.venturi.vlf.fbs.UpstreamRequest;
import com.ethlo.venturi.vlf.fbs.UpstreamResponse;

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