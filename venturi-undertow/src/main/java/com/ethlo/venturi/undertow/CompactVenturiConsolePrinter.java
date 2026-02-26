package com.ethlo.venturi.undertow;

import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.core.ExecutableRoute;
import com.ethlo.venturi.core.ShortInfo;
import com.ethlo.venturi.journal.api.JournalLevel;
import com.ethlo.venturi.undertow.config.ServerConfig;

public class CompactVenturiConsolePrinter implements VenturiConsolePrinter
{
    private static final Logger logger = LoggerFactory.getLogger("venturi");
    private static final int WIDTH = 85;
    private static final Pattern ANSI_PATTERN = Pattern.compile("\\e\\[[\\d;]*[^\\d;\\s]");
    private static final String HORIZONTAL = "─".repeat(WIDTH + 2);

    @Override
    public void printFullReport(ServerConfig config, List<? extends ExecutableRoute> routes)
    {
        logBorder("┌" + HORIZONTAL + "┐");
        
        // Single line server summary
        String serverSummary = String.format("  \u001B[1mVENTURI GATEWAY\u001B[0m » %s:%d » %d Routes Loaded", 
                config.host(), config.port(), routes.size());
        logLine(serverSummary);
        
        logBorder("├" + HORIZONTAL + "┤");
        printRouteTableInternal(routes);
        logBorder("└" + HORIZONTAL + "┘");
    }

    @Override
    public void printHeader() 
    {
        // No-op for compact view unless called individually
    }

    @Override
    public void printServerConfig(ServerConfig config) 
    {
        // Omitted in compact view
    }

    @Override
    public void printRouteTable(List<? extends ExecutableRoute> routes) 
    {
        logBorder("┌" + HORIZONTAL + "┐");
        printRouteTableInternal(routes);
        logBorder("└" + HORIZONTAL + "┘");
    }

    private void printRouteTableInternal(List<? extends ExecutableRoute> routes)
    {
        // Fixed-width column headers
        String header = String.format(" %-20s │ %-28s │ %-18s │ %-9s", 
                "ROUTE ID", "PREDICATE", "DESTINATION", "JOURNAL");
        logLine("\u001B[1m" + header + "\u001B[0m");
        
        // Column separators
        logLine(" " + "─".repeat(21) + "┼" + "─".repeat(30) + "┼" + "─".repeat(20) + "┼" + "─".repeat(10));

        for (ExecutableRoute route : routes)
        {
            String id = truncate(route.id().toString(), 20);
            String pred = truncate(getPredicateSummary(route.predicate()), 28);
            String dest = truncate(String.join(",", route.uri()), 18);
            
            // Shorten FULL/METADATA to F/M to save horizontal space
            JournalLevel req = route.routeDefinition().journal().request;
            JournalLevel res = route.routeDefinition().journal().response;
            String journal = req.name().charAt(0) + "/" + res.name().charAt(0); 

            // Format with colors
            String row = String.format(" \u001B[36m%-20s\u001B[0m │ %-28s │ \u001B[32m%-18s\u001B[0m │ %-9s", 
                    id, pred, dest, journal);
            
            logLine(row);
        }
    }

    private String getPredicateSummary(GatewayPredicate predicate)
    {
        if (predicate == null) return "ALWAYS";
        return (predicate instanceof ShortInfo si) ? si.summary() : predicate.getClass().getSimpleName();
    }

    private String truncate(String val, int max)
    {
        if (val == null) return "";
        if (val.length() <= max) return val;
        // Keep it cleanly inside the box
        return val.substring(0, max - 3) + "..."; 
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