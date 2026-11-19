package com.ethlo.r7.status.dto;

public record MemoryDto(
        long heapUsed, long heapMax,
        long directUsed, long directMax,
        long gcTimeMs,
        long openFds, long maxFds
)
{
}