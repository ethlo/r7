package com.ethlo.venturi.vlf;

import com.ethlo.venturi.vlf.fbs.ClientRequest;
import com.ethlo.venturi.vlf.fbs.Header;

// For ClientRequest
public class ClientRequestHeaders extends AbstractFbsGatewayHeaders
{
    private final ClientRequest event;

    public ClientRequestHeaders(ClientRequest event)
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