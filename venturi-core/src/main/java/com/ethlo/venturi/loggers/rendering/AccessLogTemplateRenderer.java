package com.ethlo.venturi.loggers.rendering;

import java.util.Map;

public interface AccessLogTemplateRenderer
{
    String render(Map<String, Object> data);

    String getPattern();
}
