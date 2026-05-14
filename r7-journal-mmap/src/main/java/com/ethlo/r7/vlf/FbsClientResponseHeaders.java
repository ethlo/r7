package com.ethlo.r7.vlf;

import com.ethlo.r7.vlf.fbs.ClientResponse;
import com.ethlo.r7.vlf.fbs.Header;

public class FbsClientResponseHeaders extends AbstractFbsGatewayHeaders
{
    private final ClientResponse event;

    public FbsClientResponseHeaders(ClientResponse event)
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