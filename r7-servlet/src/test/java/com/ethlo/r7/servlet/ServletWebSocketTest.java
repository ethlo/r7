package com.ethlo.r7.servlet;

import java.nio.file.Path;

import com.ethlo.r7.server.blocking.BlockingGateway;
import com.ethlo.r7.server.kit.WebSocketKit;

/**
 * The WebSocket kit, run against r7 as a servlet in Tomcat.
 */
class ServletWebSocketTest extends WebSocketKit
{
    @Override
    protected AutoCloseable startGateway(final Path routesYaml, final Path serverYaml) throws Exception
    {
        return new EmbeddedTomcat(new BlockingGateway(routesYaml, serverYaml));
    }

    @Override
    protected boolean hasManagementPort()
    {
        return false;
    }
}
