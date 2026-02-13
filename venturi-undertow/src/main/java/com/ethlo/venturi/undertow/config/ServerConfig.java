package com.ethlo.venturi.undertow.config;

public record ServerConfig(
        String host,
        int port,
        WorkerConfig worker,
        SocketConfig socket,
        ProxyConfig proxy,
        OptionsConfig options,
        StorageConfig storage
)
{
    public record WorkerConfig(
            int ioThreads,
            int taskCoreThreads,
            int taskMaxThreads,
            long stackSize,
            int connectionHighWater,
            int connectionLowWater
    )
    {
    }

    public record SocketConfig(
            boolean tcpNodelay,
            boolean reuseAddresses,
            int backlog
    )
    {
    }

    public record StorageConfig(
            String tempDir,
            int memoryThreshold
    )
    {
    }

    public record OptionsConfig(
            boolean enableHttp2,
            boolean alwaysSetKeepAlive,
            int bufferSize,
            boolean directBuffers
    )
    {
    }

    public record ProxyConfig(
            int connectionsPerThread,
            int maxQueueSize,
            int ttl,
            int maxRequestTime
    )
    {
    }
}