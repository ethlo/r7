package com.ethlo.venturi.auditing.api;

public interface ExchangeCompletionListener
{
    void onComplete(JournalExchange exchange);
}
