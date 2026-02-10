package com.ethlo.venturi.delegate;

import java.util.function.Consumer;

import com.ethlo.chronograph.Chronograph;
import com.ethlo.venturi.core.model.WebExchangeDataProvider;

public interface DelegateHttpLogger extends AutoCloseable
{
    void accessLog(Chronograph chronograph, WebExchangeDataProvider dataProvider);

    void addListener(Consumer<WebExchangeDataProvider> listener);

    void removeListener(Consumer<WebExchangeDataProvider> listener);
}
