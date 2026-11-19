package com.ethlo.r7.status;

import java.util.List;

import com.ethlo.r7.status.dto.RouteMetricsDto;

public interface TelemetryRepository
{
    void save(List<RouteMetricsDto> metrics);

    List<RouteMetricsDto> load();
}
