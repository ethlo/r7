package com.ethlo.r7.helidon;

import java.nio.file.Path;

import com.ethlo.r7.server.kit.StaticContentKit;

/**
 * The static content kit, run against r7 on Helidon.
 */
class HelidonStaticContentTest extends StaticContentKit
{
    @Override
    protected AutoCloseable startGateway(final Path routesYaml, final Path serverYaml) throws Exception
    {
        final R7Helidon gateway = new R7Helidon(routesYaml, serverYaml);
        return gateway::stop;
    }
}
