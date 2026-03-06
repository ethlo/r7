package com.ethlo.venturi.status;

import java.util.List;

import com.ethlo.venturi.status.dto.RouteMetricsDto;

public interface TelemetryRepository
{
    void save(List<RouteMetricsDto> metrics);

    List<RouteMetricsDto> load();
}
