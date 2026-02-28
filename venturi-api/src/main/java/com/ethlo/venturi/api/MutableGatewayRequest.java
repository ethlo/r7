package com.ethlo.venturi.api;

public interface MutableGatewayRequest extends GatewayRequest
{
    MutableGatewayHeaders headers();

    void path(CharSequence newPath);

    void queryParams(CharSequence newQueryParams);

    void uri(CharSequence uri);

    void method(CharSequence method);
}
