package com.ethlo.venturi.core;

public record HttpInfo(CharSequence method, CharSequence uri, CharSequence protocol, int statusCode)
{
}
