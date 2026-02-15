package com.ethlo.venturi.core.storage.mmap;

public class JournalOverflowException extends RuntimeException
{
    public JournalOverflowException(String message)
    {
        super(message);
    }
}