package com.ethlo.r7.undertow;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.api.GatewayFilter;
import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRoute;
import com.ethlo.r7.config.DefaultGatewayRoute;
import com.ethlo.r7.config.JournalDirectionConfig;
import com.ethlo.r7.core.ShortInfo;
import com.ethlo.r7.predicates.CompositePredicate;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.undertow.config.ServerConfig;

/**
 * A themed console printer using "CSS-style" color mapping and rounded layout constants.
 * All original configuration properties restored.
 */
public class VerboseR7ConsolePrinter implements R7ConsolePrinter
{
    private static final Logger logger = LoggerFactory.getLogger("r7");
    private static final int WIDTH = 80;
    private static final Pattern ANSI_PATTERN = Pattern.compile("\\e\\[[\\d;]*[^\\d;\\s]");

    // --- "CSS" THEME CONSTANTS ---
    private static final String RESET = "\u001B[0m";
    private static final String CSS_PRIMARY = "\u001B[1;36m";   // Bold Cyan
    private static final String CSS_SECONDARY = "\u001B[95m"; // Bright Magenta
    private static final String CSS_SUCCESS = "\u001B[92m";   // Acid Green
    private static final String CSS_ALERT = "\u001B[93m";     // Neon Yellow
    private static final String CSS_CRITICAL = "\u001B[91m";  // Bright Red
    private static final String CSS_MUTED = "\u001B[90m";     // Dark Grey
    private static final String CSS_HEADER = "\u001B[1;97m";  // Bold White

    // --- LAYOUT CONSTANTS ---
    private static final String BORDER_TOP = "╭" + "─".repeat(WIDTH + 2) + "╮";
    private static final String BORDER_MID = "├" + "─".repeat(WIDTH + 2) + "┤";
    private static final String BORDER_BOT = "╰" + "─".repeat(WIDTH + 2) + "╯";
    private static final String TREE_BRANCH = CSS_MUTED + "├── " + RESET;
    private static final String TREE_LAST = CSS_MUTED + "└── " + RESET;
    private static final String TREE_PIPE = CSS_MUTED + "│   " + RESET;

    @Override
    public void printFullReport(final ServerConfig config, final List<GatewayRoute> routes)
    {
        printHeader();
        printServerConfigInternal(config);
        logBorder(BORDER_MID);
        printRouteTableInternal(routes);
        printFooter();
    }

    @Override
    public void printHeader()
    {
        logBorder(BORDER_TOP);
        logLine("");
        logLine("  " + CSS_HEADER + "V E N T U R I" + RESET + "  " + RESET + " GATEWAY");
        logLine("  " + CSS_MUTED + "»".repeat(WIDTH - 4) + RESET);
        logLine("");
    }

    @Override
    public void printServerConfig(final ServerConfig config)
    {
        logBorder(BORDER_TOP);
        printServerConfigInternal(config);
        logBorder(BORDER_BOT);
    }

    @Override
    public void printRouteTable(final List<GatewayRoute> routes)
    {
        logBorder(BORDER_TOP);
        printRouteTableInternal(routes);
        logBorder(BORDER_BOT);
    }

    private void printServerConfigInternal(final ServerConfig config)
    {
        logLine(" " + CSS_PRIMARY + "◆ SYSTEM CONFIGURATION" + RESET);
        logLine("   " + CSS_SECONDARY + "Server:       " + CSS_SUCCESS + config.host() + ":" + config.port() + RESET);

        // Worker Section
        logLine("");
        logLine("   " + CSS_HEADER + "Worker & Threads" + RESET);
        logLine("   " + TREE_BRANCH + "IO Threads:        " + config.worker().ioThreads());
        logLine("   " + TREE_BRANCH + "Task Core/Max:     " + config.worker().taskCoreThreads() + " / " + config.worker().taskMaxThreads());
        logLine("   " + TREE_BRANCH + "Stack Size:        " + (config.worker().stackSize() / 1024) + " KB");
        logLine("   " + TREE_LAST + "Watermark (H/L):   " + config.worker().connectionHighWater() + " / " + config.worker().connectionLowWater());

        // Options & Socket
        logLine("");
        logLine("   " + CSS_HEADER + "Networking" + RESET);
        logLine("   " + TREE_BRANCH + "TCP No-Delay:      " + config.socket().tcpNodelay());
        logLine("   " + TREE_BRANCH + "Backlog:           " + config.socket().backlog());
        logLine("   " + TREE_BRANCH + "Read-timeout:      " + config.socket().readTimeout());
        logLine("   " + TREE_BRANCH + "Reuse address:     " + config.socket().reuseAddresses());
        logLine("   " + TREE_BRANCH + "Max header count:  " + config.options().maxHeaderCount());
        logLine("   " + TREE_BRANCH + "Max header length: " + config.options().maxHeaderSize());
        logLine("   " + TREE_BRANCH + "Request timeout:   " + config.options().requestParseTimeout());
        logLine("   " + TREE_BRANCH + "HTTP/2:            " + (config.options().enableHttp2() ? CSS_SUCCESS + "Enabled" : CSS_MUTED + "Disabled") + RESET);
        logLine("   " + TREE_LAST + "Buffer:            " + (config.options().bufferSize() / 1024) + " KB (" + (config.options().directBuffers() ? "Direct" : "Heap") + ")");

        // Proxy Section
        logLine("");
        logLine("   " + CSS_HEADER + "Proxy & Timing" + RESET);
        logLine("   " + TREE_BRANCH + "Conn/Thread:       " + config.proxy().connectionsPerThread());
        logLine("   " + TREE_BRANCH + "Max Queue:         " + config.proxy().maxQueueSize());
        logLine("   " + TREE_BRANCH + "Max Req Time:      " + config.proxy().maxRequestTime() + " ms");
        logLine("   " + TREE_LAST + "TTL:               " + (config.proxy().ttl() == -1 ? "Infinite" : config.proxy().ttl() + " ms"));

        // Storage
        logLine("");
        logLine("   " + CSS_HEADER + "Journaling" + RESET);
        logLine("   " + TREE_BRANCH + "Shard count:         " + config.storage().shardCount());
        logLine("   " + TREE_BRANCH + "Shard size:          " + config.storage().shardSize());
        logLine("   " + TREE_BRANCH + "Pre-fault segments:  " + config.storage().preFault());
        logLine("   " + TREE_LAST +   "Directory:           " + config.storage().workDir());

        final MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        final MemoryUsage heap = memBean.getHeapMemoryUsage();
        final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        final List<BufferPoolMXBean> bufferPools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);

        final String gcName = gcBeans.stream()
                .map(GarbageCollectorMXBean::getName)
                .findFirst()
                .orElse("Unknown");

        final long directBufUsed = bufferPools.stream()
                .filter(p -> p.getName().equals("direct"))
                .mapToLong(BufferPoolMXBean::getMemoryUsed)
                .findFirst()
                .orElse(0L);

        // JVM & Memory Section
        logLine("");
        logLine("   " + CSS_HEADER + "JVM & Memory" + RESET);
        logLine("   " + TREE_BRANCH + "Java Version:      " + Runtime.version());
        logLine("   " + TREE_BRANCH + "GC Implementation: " + gcName);
        logLine("   " + TREE_BRANCH + "Heap Used/Max:     " + (heap.getUsed() / 1024 / 1024) + " MB / " + (heap.getMax() / 1024 / 1024) + " MB");
        logLine("   " + TREE_BRANCH + "Direct Buffer:     " + (directBufUsed / 1024) + " KB");
        logLine("   " + TREE_LAST + "Total Threads:     " + ManagementFactory.getThreadMXBean().getThreadCount());


        /*final long[] ids = ManagementFactory.getThreadMXBean().getAllThreadIds();
        for (long id : ids) {
            logger.info("{}", ManagementFactory.getThreadMXBean().getThreadInfo(id));
        }*/

    }

    private void printRouteTableInternal(final List<GatewayRoute> routes)
    {
        logLine(" " + CSS_PRIMARY + "◆ ACTIVE ROUTE MANIFEST" + RESET + " (" + routes.size() + ")");

        for (int i = 0; i < routes.size(); i++)
        {
            final GatewayRoute route = routes.get(i);
            logLine(" " + CSS_PRIMARY + "◉" + RESET + " Route: " + CSS_HEADER + route.id() + RESET);

            logLine("   " + CSS_MUTED + "│" + RESET + " Match Logic:");
            printPredicate(route.predicate(), "   " + CSS_MUTED + "│" + RESET + "  ", true);

            final JournalDirectionConfig reqConfig = ((DefaultGatewayRoute) route).routeDefinition().journal().request();
            final JournalDirectionConfig resConfig = ((DefaultGatewayRoute) route).routeDefinition().journal().response();

            logLine("   " + CSS_MUTED + "│" + RESET + " Journaling:");
            logLine("   " + CSS_MUTED + "│" + RESET + "  " + TREE_BRANCH + "Request:  " + colorLevel(reqConfig.level()) + formatOverrides(reqConfig.statusOverrides()));
            logLine("   " + CSS_MUTED + "│" + RESET + "  " + TREE_LAST + "Response: " + colorLevel(resConfig.level()) + formatOverrides(resConfig.statusOverrides()));

            logLine("   " + CSS_MUTED + "│" + RESET + " Destination: " + CSS_SUCCESS + String.join(" | ", route.uri()) + RESET);

            logLine("   " + TREE_LAST + "Pipeline:");
            final List<GatewayFilter> filters = iterableToList(route.filters());
            if (filters.isEmpty())
            {
                logLine("      " + TREE_LAST + CSS_ALERT + "DIRECT" + RESET);
            }
            else
            {
                for (int f = 0; f < filters.size(); f++)
                {
                    final boolean isLastFilter = (f == filters.size() - 1);
                    final String connector = isLastFilter ? TREE_LAST : TREE_BRANCH;
                    logLine("      " + connector + CSS_SECONDARY + getInfo(filters.get(f)) + RESET);
                }
            }

            if (i < routes.size() - 1)
            {
                logLine("");
            }
        }
    }

    private String colorLevel(final JournalLevel level)
    {
        return switch (level)
        {
            case NONE -> CSS_MUTED + "NONE" + RESET;
            case METADATA -> CSS_ALERT + "METADATA" + RESET;
            case HEADERS -> CSS_PRIMARY + "HEADERS" + RESET;
            case FULL -> CSS_CRITICAL + "\u001B[7m FULL " + RESET;
        };
    }

    private String formatOverrides(final JournalLevel[] overrides)
    {
        if (overrides == null)
        {
            return "";
        }

        final Map<JournalLevel, List<String>> grouped = new EnumMap<>(JournalLevel.class);
        for (int i = 100; i < 600; )
        {
            final JournalLevel level = overrides[i];
            if (level != null)
            {
                boolean isHundredBlock = true;
                if (i % 100 == 0)
                {
                    for (int j = 1; j < 100; j++)
                    {
                        if (overrides[i + j] != level)
                        {
                            isHundredBlock = false;
                            break;
                        }
                    }
                }
                else
                {
                    isHundredBlock = false;
                }

                if (isHundredBlock)
                {
                    grouped.computeIfAbsent(level, k -> new ArrayList<>()).add((i / 100) + "xx");
                    i += 100;
                    continue;
                }
                else
                {
                    grouped.computeIfAbsent(level, k -> new ArrayList<>()).add(String.valueOf(i));
                }
            }
            i++;
        }

        if (grouped.isEmpty())
        {
            return "";
        }

        final StringBuilder sb = new StringBuilder(" " + CSS_ALERT + "[Overrides: ");
        boolean first = true;
        for (final Map.Entry<JournalLevel, List<String>> entry : grouped.entrySet())
        {
            if (!first)
            {
                sb.append(", ");
            }
            sb.append(String.join(",", entry.getValue()))
                    .append(" ➔ ")
                    .append(colorLevel(entry.getKey()))
                    .append(CSS_ALERT);
            first = false;
        }
        sb.append("]" + RESET);
        return sb.toString();
    }

    private void printPredicate(final GatewayPredicate predicate, final String prefix, final boolean isLast)
    {
        if (predicate == null)
        {
            logLine(prefix + TREE_LAST + "ALWAYS");
            return;
        }

        final String name = getInfo(predicate);
        logLine(prefix + (isLast ? TREE_LAST : TREE_BRANCH) + name);

        if (predicate instanceof CompositePredicate composite)
        {
            final List<GatewayPredicate> children = composite.children();
            for (int i = 0; i < children.size(); i++)
            {
                final String newPrefix = prefix + (isLast ? "    " : TREE_PIPE);
                printPredicate(children.get(i), newPrefix, i == children.size() - 1);
            }
        }
    }

    private <T> List<T> iterableToList(final Iterable<T> iterable)
    {
        final List<T> list = new ArrayList<>();
        iterable.forEach(list::add);
        return list;
    }

    private String getInfo(final GatewayFilter filter)
    {
        return (filter instanceof ShortInfo si) ? si.summary() : filter.getClass().getSimpleName();
    }

    private String getInfo(final GatewayPredicate predicate)
    {
        return (predicate instanceof ShortInfo si) ? si.summary() : predicate.getClass().getSimpleName();
    }

    @Override
    public void printFooter()
    {
        logBorder(BORDER_BOT);
    }

    private void logLine(final String content)
    {
        final int visibleLength = ANSI_PATTERN.matcher(content).replaceAll("").length();
        final int padding = Math.max(0, WIDTH - visibleLength);
        logger.info("│ {}{} │", content, " ".repeat(padding));
    }

    private void logBorder(final String border)
    {
        logger.info(border);
    }
}