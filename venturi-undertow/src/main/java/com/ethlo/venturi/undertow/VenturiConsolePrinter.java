package com.ethlo.venturi.undertow;

import java.util.List;

import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.undertow.config.ServerConfig;

public interface VenturiConsolePrinter
{
    void printFullReport(ServerConfig config, List<GatewayRoute> routes);

    void printHeader();

    void printServerConfig(ServerConfig config);

    void printRouteTable(List<GatewayRoute> routes);

    void printFooter();
}
