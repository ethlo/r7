package com.ethlo.r7.api;

/**
 * Thrown when a header or attribute is given a value the gateway cannot represent.
 *
 * @see TextValues
 */
public class InvalidTextValueException extends IllegalArgumentException
{
    private final String field;
    private final int index;

    public InvalidTextValueException(final String message, final String field, final int index)
    {
        super(message);
        this.field = field;
        this.index = index;
    }

    /**
     * Name of the header or attribute that was being set.
     */
    public String getField()
    {
        return field;
    }

    /**
     * Index of the first character that cannot be represented, or
     * {@link TextValues#ABSENT} when the value was absent altogether rather than
     * containing an unrepresentable character.
     */
    public int getIndex()
    {
        return index;
    }
}
