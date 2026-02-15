package com.ethlo.venturi.core.storage.mmap;

public interface ExchangeCompletionListener {
    /** Called only when a full request/response cycle is finished */
    void onComplete(JournalExchange exchange);
}