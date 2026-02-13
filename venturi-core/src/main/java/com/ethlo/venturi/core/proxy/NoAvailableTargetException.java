package com.ethlo.venturi.core.proxy;

public class NoAvailableTargetException extends GatewayProxyException
{
    public NoAvailableTargetException(String message)
    {
        super(message);
    }
}
