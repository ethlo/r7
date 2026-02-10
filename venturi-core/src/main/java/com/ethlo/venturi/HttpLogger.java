package com.ethlo.venturi;

import com.ethlo.venturi.core.model.WebExchangeDataProvider;

public interface HttpLogger extends AutoCloseable
{
    void accessLog(WebExchangeDataProvider dataProvider);

    String getName();
}
