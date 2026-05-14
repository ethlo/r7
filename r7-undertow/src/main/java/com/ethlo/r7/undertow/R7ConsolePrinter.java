package com.ethlo.r7.undertow;

import java.util.List;

import com.ethlo.r7.api.GatewayRoute;
import com.ethlo.r7.undertow.config.ServerConfig;

public interface R7ConsolePrinter
{
    void printFullReport(ServerConfig config, List<GatewayRoute> routes);

    void printHeader();

    void printServerConfig(ServerConfig config);

    void printRouteTable(List<GatewayRoute> routes);

    void printFooter();
}
