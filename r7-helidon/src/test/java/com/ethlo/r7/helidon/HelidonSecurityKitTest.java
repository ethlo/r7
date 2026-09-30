package com.ethlo.r7.helidon;

import java.nio.file.Path;

import com.ethlo.r7.server.kit.GatewaySecurityKit;

/**
 * The wire-level security kit, run against r7 on Helidon Níma.
 */
class HelidonSecurityKitTest extends GatewaySecurityKit
{
    @Override
    protected AutoCloseable startGateway(final Path routesYaml, final Path serverYaml) throws Exception
    {
        final R7Helidon gateway = new R7Helidon(routesYaml, serverYaml);
        return gateway::stop;
    }
}
