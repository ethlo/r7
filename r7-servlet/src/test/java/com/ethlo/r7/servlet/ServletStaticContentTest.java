package com.ethlo.r7.servlet;

import java.nio.file.Path;

import com.ethlo.r7.server.blocking.BlockingGateway;
import com.ethlo.r7.server.kit.StaticContentKit;

/**
 * The static content kit, run against r7 on Servlet.
 */
class ServletStaticContentTest extends StaticContentKit
{
    @Override
    protected AutoCloseable startGateway(final Path routesYaml, final Path serverYaml) throws Exception
    {
        return new EmbeddedTomcat(new BlockingGateway(routesYaml, serverYaml));
    }
}
