package com.ethlo.r7.spi;

/**
 * Where in routes.yaml a filter is declared: in the root's {@code filters}, a group's, or a
 * route's. A filter is one instance for everything beneath the level it is declared on.
 */
public enum DeclarationLevel
{
    ROOT("the root"), GROUP("a group"), ROUTE("a route");

    private final String label;

    DeclarationLevel(final String label)
    {
        this.label = label;
    }

    /**
     * @return how an error message names the level: "the root", "a group", "a route"
     */
    public String label()
    {
        return this.label;
    }
}
