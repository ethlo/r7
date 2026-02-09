package com.ethlo.venturi.api;

interface GatewayResponse {
    void setStatus(int);
    void addHeader(String, String);
    OutputStream body();
}