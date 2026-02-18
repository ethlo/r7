package com.ethlo.venturi.config;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.core.predicates.CompositePredicate;

public class RouteTablePrinter
{
    private static final Logger logger = LoggerFactory.getLogger(RouteTablePrinter.class);

    public void printRouteTable(List<? extends GatewayRoute> routes) {
        final String separator = "─".repeat(70);
        // Use String.format for the centered header
        String headerText = String.format("│ %-68s │", center("VENTURI GATEWAY ROUTE TABLE", 68));

        logger.info("┌" + separator + "┐");
        logger.info(headerText);
        logger.info("├" + separator + "┤");

        for (int i = 0; i < routes.size(); i++) {
            final GatewayRoute route = routes.get(i);
            logger.info("│ ❯ Route: \u001B[1m{}\u001B[0m", route.id());

            logger.info("│   ├─ Match Logic:");
            printPredicate(route.predicate(), "│   │  ", true);

            final String targets = String.join(" | ", route.uri());
            logger.info("│   ├─ Proxy To: \u001B[32m{}\u001B[0m", targets);

            final List<String> filterNames = new ArrayList<>();
            route.filters().forEach(f -> filterNames.add(f.getClass().getSimpleName()));
            final String pipeline = filterNames.isEmpty() ? "DIRECT" : String.join(" ➔ ", filterNames);
            logger.info("│   └─ Pipeline: \u001B[36m{}\u001B[0m", pipeline);

            if (i < routes.size() - 1) logger.info("│");
        }
        logger.info("└" + separator + "┘");
    }

    private void printPredicate(GatewayPredicate predicate, String prefix, boolean isLast) {
        if (predicate == null) {
            logger.info("{}└── ALWAYS", prefix);
            return;
        }

        String name = predicate.getClass().getSimpleName();

        // Logic to handle messy Lambda names
        if (name.isEmpty() || name.contains("$$Lambda")) {
            name = "FunctionalPredicate";
        } else {
            name = name.replace("Gateway", "").replace("Predicate", "");
        }

        // Fix: Remove the double "── ──" from your previous output
        logger.info("{}{} {}", prefix, (isLast ? "└──" : "├──"), name);

        if (predicate instanceof CompositePredicate composite) {
            List<GatewayPredicate> children = composite.children();
            for (int i = 0; i < children.size(); i++) {
                String newPrefix = prefix + (isLast ? "    " : "│   ");
                printPredicate(children.get(i), newPrefix, i == children.size() - 1);
            }
        }
    }

    // Helper to center the text manually since Java doesn't have a native "center" flag
    private String center(String text, int len) {
        if (text.length() >= len) return text;
        int spaces = len - text.length();
        int leading = spaces / 2;
        return " ".repeat(leading) + text + " ".repeat(spaces - leading);
    }
}
