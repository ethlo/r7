package com.ethlo.r7.undertow;

import java.nio.file.Path;

import com.ethlo.r7.server.kit.StaticContentKit;

/**
 * The static content kit, run against r7 on Undertow.
 */
class UndertowStaticContentTest extends StaticContentKit
{
    @Override
    protected AutoCloseable startGateway(final Path routesYaml, final Path serverYaml) throws Exception
    {
        final R7Main gateway = new R7Main(routesYaml, serverYaml);
        return gateway::stop;
    }
}
