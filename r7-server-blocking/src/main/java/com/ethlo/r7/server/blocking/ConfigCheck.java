package com.ethlo.r7.server.blocking;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.ethlo.r7.GatewayScheduler;
import com.ethlo.r7.config.ConfigurationException;
import com.ethlo.r7.config.ConfigurationManager;
import com.ethlo.r7.server.config.ServerConfig;
import com.ethlo.r7.status.MetricsRegistry;
import com.ethlo.r7.status.TelemetryRepository;
import com.ethlo.r7.status.dto.RouteMetricsDto;

/**
 * Loads and validates the configuration files as {@link BlockingGateway} does at startup - the
 * same files, interpolation, binding, validation and filter and predicate construction - and
 * stops there: no listener, no journal or work directory, no hot reload and no upstream
 * connection, since those are created by the gateway and its pipeline, never by building routes.
 */
public final class ConfigCheck
{
    public static final int VALID = 0;
    public static final int INVALID = 1;

    private ConfigCheck()
    {
    }

    /**
     * @param environment where {@code R7_ROUTES_CONFIG} and {@code R7_SERVER_CONFIG} are read
     *                    from; {@code ${VAR}} references in the files are resolved as at startup
     * @param out         gets one line naming the files when they are valid
     * @param err         gets the errors when they are not
     * @return {@link #VALID} or {@link #INVALID}
     */
    public static int run(final Map<String, String> environment, final PrintStream out, final PrintStream err)
    {
        final Path routesFile = BlockingGateway.routesFile(environment);
        String checking = null;
        try
        {
            final Path serverFile = BlockingGateway.serverFile(environment.get(BlockingGateway.SERVER_ENVIRONMENT_VARIABLE), BlockingGateway.SERVER_ENVIRONMENT_VARIABLE);
            BlockingGateway.requireRoutesFile(routesFile);
            checking = Files.exists(serverFile) ? serverFile.toString() : "built-in server defaults (no " + serverFile + ")";
            final ServerConfig serverConfig = BlockingGateway.loadServerSettings(serverFile);
            checking = routesFile.toString();
            checkRoutes(routesFile, serverConfig);
            out.println(Files.exists(serverFile)
                    ? routesFile + " and " + serverFile + " OK"
                    : routesFile + " OK, with the built-in server defaults (no " + serverFile + ")");
            return VALID;
        }
        catch (final ConfigurationException e)
        {
            err.println(prefix(checking) + e.getMessage());
            return INVALID;
        }
        catch (final RuntimeException e)
        {
            // A filter factory may reject its arguments with any exception; the check reports it
            // as the gateway would fail on it, but as a message, not a stack trace.
            err.println(prefix(checking) + e.getClass().getSimpleName() + ": " + e.getMessage());
            return INVALID;
        }
    }

    private static String prefix(final String checking)
    {
        return checking == null ? "" : checking + ": ";
    }

    /**
     * Builds the routes with a scheduler of their own, stopped before returning, and metrics that
     * are neither read from nor saved to the work directory.
     */
    private static void checkRoutes(final Path routesFile, final ServerConfig serverConfig)
    {
        final GatewayScheduler scheduler = new GatewayScheduler(1);
        try
        {
            final MetricsRegistry metricsRegistry = new MetricsRegistry(new NoTelemetry(), scheduler);
            new ConfigurationManager(BlockingGateway.engineContext(scheduler, metricsRegistry, serverConfig.storage())).buildFrom(routesFile);
        }
        finally
        {
            scheduler.shutdown();
        }
    }

    private static final class NoTelemetry implements TelemetryRepository
    {
        @Override
        public void save(final List<RouteMetricsDto> metrics)
        {
        }

        @Override
        public List<RouteMetricsDto> load()
        {
            return List.of();
        }
    }
}
