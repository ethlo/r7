package com.ethlo.venturi.undertow;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.core.ExecutableRoute;
import com.ethlo.venturi.core.ShortInfo;
import com.ethlo.venturi.core.predicates.CompositePredicate;
import com.ethlo.venturi.journal.api.JournalLevel;
import com.ethlo.venturi.undertow.config.ServerConfig;

public class VerboseVenturiConsolePrinter implements VenturiConsolePrinter
{
    private static final Logger logger = LoggerFactory.getLogger("venturi");
    private static final int WIDTH = 80;
    private static final Pattern ANSI_PATTERN = Pattern.compile("\\e\\[[\\d;]*[^\\d;\\s]");
    private static final String HORIZONTAL = "─".repeat(WIDTH + 2);

    /**
     * Prints the full Venturi startup report
     */
    @Override
    public void printFullReport(ServerConfig config, List<? extends ExecutableRoute> routes)
    {
        printHeader();
        printServerConfigInternal(config);
        logBorder("├" + HORIZONTAL + "┤");
        printRouteTableInternal(routes);
        printFooter();
    }

    @Override
    public void printHeader()
    {
        logBorder("┌" + HORIZONTAL + "┐");
        logLine("");
        logLine("  \u001B[1mV E N T U R I\u001B[0m  GATEWAY");
        logLine("  " + "»".repeat(WIDTH - 4));
        logLine("");
    }

    @Override
    public void printServerConfig(ServerConfig config)
    {
        logBorder("┌" + HORIZONTAL + "┐");
        printServerConfigInternal(config);
        logBorder("└" + HORIZONTAL + "┘");
    }

    @Override
    public void printRouteTable(List<? extends ExecutableRoute> routes)
    {
        logBorder("┌" + HORIZONTAL + "┐");
        printRouteTableInternal(routes);
        logBorder("└" + HORIZONTAL + "┘");
    }

    private void printServerConfigInternal(ServerConfig config)
    {
        logLine(" \u001B[1mSYSTEM CONFIGURATION\u001B[0m");
        logLine(" ❯ Server:    \u001B[32m" + config.host() + ":" + config.port() + "\u001B[0m");

        // Worker Section
        logLine("");
        logLine("   \u001B[1mWorker & Threads\u001B[0m");
        logLine("   ├─ IO Threads:        " + config.worker().ioThreads());
        logLine("   ├─ Task Core/Max:     " + config.worker().taskCoreThreads() + " / " + config.worker().taskMaxThreads());
        logLine("   ├─ Stack Size:        " + (config.worker().stackSize() / 1024) + " KB");
        logLine("   └─ Watermark (H/L):   " + config.worker().connectionHighWater() + " / " + config.worker().connectionLowWater());

        // Options & Socket
        logLine("");
        logLine("   \u001B[1mNetworking\u001B[0m");
        logLine("   ├─ TCP No-Delay:      " + config.socket().tcpNodelay());
        logLine("   ├─ Backlog:           " + config.socket().backlog());
        logLine("   ├─ Read-timeout:      " + config.socket().readTimeout());
        logLine("   ├─ Reuse address:     " + config.socket().reuseAddresses());
        logLine("   ├─ Max header count:  " + config.options().maxHeaderCount());
        logLine("   ├─ Max header length: " + config.options().maxHeaderSize());
        logLine("   ├─ Request timeout:   " + config.options().requestParseTimeout());
        logLine("   ├─ HTTP/2:            " + (config.options().enableHttp2() ? "Enabled" : "Disabled"));
        logLine("   └─ Buffer:            " + (config.options().bufferSize() / 1024) + " KB (" + (config.options().directBuffers() ? "Direct" : "Heap") + ")");

        // Proxy Section
        logLine("");
        logLine("   \u001B[1mProxy & Timing\u001B[0m");
        logLine("   ├─ Conn/Thread:       " + config.proxy().connectionsPerThread());
        logLine("   ├─ Max Queue:         " + config.proxy().maxQueueSize());
        logLine("   ├─ Max Req Time:      " + config.proxy().maxRequestTime() + " ms");
        logLine("   └─ TTL:               " + (config.proxy().ttl() == -1 ? "Infinite" : config.proxy().ttl() + " ms"));

        // Storage
        logLine("");
        logLine("   \u001B[1mStorage & Buffering\u001B[0m");
        logLine("   └─ Directory:         " + config.storage().workDir());

        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memBean.getHeapMemoryUsage();
        var gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        var bufferPools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);

        // Identify Active GC
        String gcName = gcBeans.stream()
                .map(GarbageCollectorMXBean::getName)
                .findFirst()
                .orElse("Unknown");

        // Calculate Direct Memory (Off-Heap)
        long directBufUsed = bufferPools.stream()
                .filter(p -> p.getName().equals("direct"))
                .mapToLong(BufferPoolMXBean::getMemoryUsed)
                .findFirst()
                .orElse(0L);

        // JVM & Memory Section
        logLine("");
        logLine("   \u001B[1mJVM & Memory\u001B[0m");
        logLine("   ├─ Java Version:      " + Runtime.version());
        logLine("   ├─ GC Implementation: " + gcName);
        logLine("   ├─ Heap Used/Max:     " + (heap.getUsed() / 1024 / 1024) + " MB / " + (heap.getMax() / 1024 / 1024) + " MB");
        logLine("   ├─ Direct Buffer:     " + (directBufUsed / 1024) + " KB");
        logLine("   └─ Total Threads:     " + ManagementFactory.getThreadMXBean().getThreadCount());
    }

    private void printRouteTableInternal(List<? extends ExecutableRoute> routes)
    {
        logLine(" \u001B[1mACTIVE ROUTES\u001B[0m (" + routes.size() + ")");

        for (int i = 0; i < routes.size(); i++)
        {
            final ExecutableRoute route = routes.get(i);

            // 1. Route ID (Cyan)
            logLine(" ❯ Route: \u001B[1;36m" + route.id() + "\u001B[0m");

            // 2. Match Logic
            logLine("   ├─ Predicates:");
            printPredicate(route.predicate(), "   │  ", true);

            // 3. Journaling config
            final JournalLevel reqJournal = route.routeDefinition().journal().request;
            final JournalLevel resJournal = route.routeDefinition().journal().response;
            logLine("   ├─ Journaling: " + reqJournal + " / " + resJournal);

            // 4. Proxy Targets (Green)
            final String targets = String.join(" | ", route.uri());
            logLine("   ├─ Destination: \u001B[32m" + targets + "\u001B[0m");

            // 5. Pipeline (Magenta/Vertical)
            logLine("   └─ Filters:");
            final List<GatewayFilter> filters = iterableToList(route.filters());
            if (filters.isEmpty())
            {
                logLine("      └── \u001B[33mDIRECT\u001B[0m");
            }
            else
            {
                for (int f = 0; f < filters.size(); f++)
                {
                    boolean isLastFilter = (f == filters.size() - 1);
                    String connector = isLastFilter ? "└── " : "├── ";
                    logLine("      " + connector + "\u001B[35m" + getInfo(filters.get(f)) + "\u001B[0m");
                }
            }

            // Space between routes with borders preserved
            if (i < routes.size() - 1)
            {
                logLine("");
            }
        }
    }

    private void printPredicate(GatewayPredicate predicate, String prefix, boolean isLast)
    {
        if (predicate == null)
        {
            logLine(prefix + "└── ALWAYS");
            return;
        }

        // Get the name/summary of the current predicate
        String name = getInfo(predicate);

        // Draw the current node
        logLine(prefix + (isLast ? "└── " : "├── ") + name);

        // If it's a composite (And, Or, Not), recurse into children
        if (predicate instanceof CompositePredicate composite)
        {
            List<GatewayPredicate> children = composite.children();
            for (int i = 0; i < children.size(); i++)
            {
                // If the current node is the last one, we don't draw a vertical
                // line for its children's prefix.
                String newPrefix = prefix + (isLast ? "    " : "│   ");
                printPredicate(children.get(i), newPrefix, i == children.size() - 1);
            }
        }
    }

    private <T> List<T> iterableToList(Iterable<T> iterable)
    {
        final List<T> list = new ArrayList<>();
        iterable.forEach(list::add);
        return list;
    }

    private String getInfo(GatewayFilter filter)
    {
        return (filter instanceof ShortInfo si) ? si.summary() : filter.getClass().getSimpleName();
    }

    private String getInfo(GatewayPredicate predicate)
    {
        return (predicate instanceof ShortInfo si) ? si.summary() : predicate.getClass().getSimpleName();
    }

    public void printFooter()
    {
        logBorder("└" + HORIZONTAL + "┘");
    }

    private void logLine(String content)
    {
        int visibleLength = ANSI_PATTERN.matcher(content).replaceAll("").length();
        int padding = Math.max(0, WIDTH - visibleLength);
        logger.info("│ {}{} │", content, " ".repeat(padding));
    }

    private void logBorder(String border)
    {
        logger.info(border);
    }
}