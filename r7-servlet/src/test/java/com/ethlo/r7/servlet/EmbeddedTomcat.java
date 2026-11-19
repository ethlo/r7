package com.ethlo.r7.servlet;

import java.io.IOException;
import java.nio.file.Files;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;

import com.ethlo.r7.server.blocking.BlockingGateway;
import com.ethlo.r7.server.config.ServerConfig;

/**
 * r7 as a servlet in embedded Tomcat, on the data-plane host and port its server configuration
 * names, with virtual threads - what a deployment would configure. Also runnable, for
 * benchmarking the servlet host against the other servers.
 */
final class EmbeddedTomcat implements AutoCloseable
{
    private final BlockingGateway gateway;
    private final Tomcat tomcat;

    EmbeddedTomcat(final BlockingGateway gateway) throws IOException, LifecycleException
    {
        this.gateway = gateway;
        final ServerConfig.ServerCoreConfig core = gateway.serverConfig().server();
        this.tomcat = new Tomcat();
        this.tomcat.setBaseDir(Files.createTempDirectory("r7-tomcat-").toString());

        final Connector connector = new Connector();
        connector.setPort(core.port());
        connector.setProperty("address", core.host());
        connector.setProperty("useVirtualThreads", "true");
        // Tomcat refuses TRACE with 405 itself by default. Let it through so the kit checks r7's
        // own refusal (501), not the container's.
        connector.setAllowTrace(true);
        this.tomcat.setConnector(connector);

        final Context context = this.tomcat.addContext("", null);
        Tomcat.addServlet(context, "r7", new R7GatewayServlet(gateway));
        context.addServletMappingDecoded("/*", "r7");
        this.tomcat.start();
    }

    @Override
    public void close() throws LifecycleException
    {
        this.tomcat.stop();
        this.tomcat.destroy();
        this.gateway.close();
    }

    public static void main(final String[] args) throws Exception
    {
        final EmbeddedTomcat server = new EmbeddedTomcat(BlockingGateway.fromEnvironment());
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            try
            {
                server.close();
            }
            catch (final LifecycleException ignored)
            {
                // Shutting down anyway.
            }
        }, "r7-shutdown-hook"));
    }
}
