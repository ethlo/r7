package com.ethlo.r7.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonPointer;
import tools.jackson.core.JsonToken;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.ObjectMapper;

/**
 * Where each scalar of a YAML document was written, by its {@link JsonPointer}. A bound config
 * tree has no locations left, so a check that runs on it looks its findings up here to tell the
 * operator which line to edit.
 * <p>
 * The index is built on the first lookup, from the raw text: a document that produces no
 * findings is never parsed a second time.
 */
public final class YamlSourcePositions
{
    public record Position(int line, int column)
    {
    }

    private final ObjectMapper mapper;
    private final String yaml;
    private Map<String, Position> index;

    public YamlSourcePositions(final ObjectMapper mapper, final String yaml)
    {
        this.mapper = mapper;
        this.yaml = yaml;
    }

    /**
     * @return the position of the scalar at {@code pointer}, or empty when there is none (the
     * node is not a scalar, or the text no longer parses)
     */
    public Optional<Position> of(final JsonPointer pointer)
    {
        if (this.index == null)
        {
            this.index = buildIndex();
        }
        return Optional.ofNullable(this.index.get(pointer.toString()));
    }

    private Map<String, Position> buildIndex()
    {
        final Map<String, Position> positions = new HashMap<>();
        try (JsonParser parser = this.mapper.createParser(this.yaml))
        {
            JsonToken token;
            while ((token = parser.nextToken()) != null)
            {
                if (token.isScalarValue())
                {
                    final TokenStreamLocation location = parser.currentTokenLocation();
                    positions.put(parser.streamReadContext().pathAsPointer().toString(), new Position(location.getLineNr(), location.getColumnNr()));
                }
            }
        }
        catch (final RuntimeException e)
        {
            // Positions are a courtesy on a finding, never a reason to fail: the same text
            // already parsed once to produce the tree being checked.
            return positions;
        }
        return positions;
    }
}
