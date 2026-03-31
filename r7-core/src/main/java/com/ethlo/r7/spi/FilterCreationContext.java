package com.ethlo.r7.spi;

public record FilterCreationContext(
    String routeId, 
    EngineContext engine
) {
}