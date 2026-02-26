package com.ethlo.venturi.undertow;

import com.ethlo.venturi.core.ExecutableRoute;
import com.ethlo.venturi.undertow.config.ServerConfig;

import java.util.List;

public interface VenturiConsolePrinter
{
    void printFullReport(ServerConfig config, List<? extends ExecutableRoute> routes);

    void printHeader();

    void printServerConfig(ServerConfig config);

    void printRouteTable(List<? extends ExecutableRoute> routes);
}
