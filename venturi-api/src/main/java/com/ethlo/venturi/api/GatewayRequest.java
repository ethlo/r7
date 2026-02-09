package com.ethlo.venturi.api;

interface GatewayRequest {
    String method();
    URI uri();
    Headers headers();
    InputStream body(); // or channel
}