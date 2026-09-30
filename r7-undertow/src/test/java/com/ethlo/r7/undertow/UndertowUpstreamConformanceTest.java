package com.ethlo.r7.undertow;

import java.nio.file.Path;

import com.ethlo.r7.server.kit.UpstreamConformanceKit;

/**
 * The upstream conformance kit, run against r7 on Undertow with the r7 upstream client.
 */
class UndertowUpstreamConformanceTest extends UpstreamConformanceKit
{
    @Override
    protected AutoCloseable startGateway(final Path routesYaml, final Path serverYaml) throws Exception
    {
        final R7Main gateway = new R7Main(routesYaml, serverYaml);
        return gateway::stop;
    }

    @Override
    protected String serverYamlExtra()
    {
        return "proxy:\n  client: r7\n";
    }
}
