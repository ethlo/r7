package com.ethlo.r7.undertow;

import java.nio.file.Path;

import com.ethlo.r7.server.kit.GatewaySecurityKit;

/**
 * The wire-level security kit, run against r7 on Undertow.
 */
class UndertowSecurityKitTest extends GatewaySecurityKit
{
    @Override
    protected AutoCloseable startGateway(final Path routesYaml, final Path serverYaml) throws Exception
    {
        final R7Main gateway = new R7Main(routesYaml, serverYaml);
        return gateway::stop;
    }
}
