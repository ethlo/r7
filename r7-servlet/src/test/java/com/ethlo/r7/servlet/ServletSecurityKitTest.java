package com.ethlo.r7.servlet;

import java.nio.file.Path;

import com.ethlo.r7.server.blocking.BlockingGateway;
import com.ethlo.r7.server.kit.GatewaySecurityKit;

/**
 * The wire-level security kit, run against r7 as a servlet in Tomcat.
 */
class ServletSecurityKitTest extends GatewaySecurityKit
{
    @Override
    protected AutoCloseable startGateway(final Path routesYaml, final Path serverYaml) throws Exception
    {
        return new EmbeddedTomcat(new BlockingGateway(routesYaml, serverYaml));
    }
}
