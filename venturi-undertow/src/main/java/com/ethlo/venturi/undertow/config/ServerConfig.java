package com.ethlo.venturi.undertow.config;

import com.ethlo.venturi.util.ValidatorUtils;
import com.ethlo.venturi.validation.ValidatableConfig;
import com.ethlo.venturi.validation.ValidationResult;

public record ServerConfig(
        String host,
        int port,
        WorkerConfig worker,
        SocketConfig socket,
        ProxyConfig proxy,
        OptionsConfig options,
        StorageConfig storage
) implements ValidatableConfig
{
    public ServerConfig host(String host)
    {
        return new ServerConfig(host, port, worker, socket, proxy, options, storage);
    }

    public ServerConfig port(int port)
    {
        return new ServerConfig(host, port, worker, socket, proxy, options, storage);
    }

    @Override
    public void validate(final ValidationResult result)
    {
        final ValidatorUtils validator = new ValidatorUtils(result);
        final String ctx = "ServerConfig";

        // --- Core Server ---
        validator.required(ctx, "host", host);
        if (port < 1 || port > 65535)
        {
            result.addError(ctx, "'port' must be between 1 and 65535");
        }

        // --- Worker Config ---
        validator.required(ctx, "worker", worker);
        if (worker != null)
        {
            if (worker.ioThreads() < 1)
            {
                result.addError(ctx, "'worker.io_threads' must be >= 1");
            }
            if (worker.taskCoreThreads() < 0)
            {
                result.addError(ctx, "'worker.task_core_threads' must be >= 0");
            }
            if (worker.taskMaxThreads() < worker.taskCoreThreads())
            {
                result.addError(ctx, "'worker.task_max_threads' must be >= 'worker.task_core_threads'");
            }
            if (worker.connectionHighWater() < worker.connectionLowWater())
            {
                result.addError(ctx, "'worker.connection_high_water' must be >= 'worker.connection_low_water'");
            }
            if (worker.stackSize() < 0)
            {
                result.addError(ctx, "'worker.stack_size' must be >= 0");
            }
        }

        // --- Socket Config ---
        validator.required(ctx, "socket", socket);
        if (socket != null)
        {
            if (socket.backlog() < 1)
            {
                result.addError(ctx, "'socket.backlog' must be >= 1");
            }
            if (socket.readTimeout() < -1)
            {
                result.addError(ctx, "'socket.read_timeout' must be >= -1");
            }
        }

        // --- Proxy Config ---
        validator.required(ctx, "proxy", proxy);
        if (proxy != null)
        {
            if (proxy.connectionsPerThread() < 1)
            {
                result.addError(ctx, "'proxy.connections_per_thread' must be >= 1");
            }
            if (proxy.maxQueueSize() < 0)
            {
                result.addError(ctx, "'proxy.max_queue_size' must be >= 0");
            }
            if (proxy.maxRequestTime() < 1)
            {
                result.addError(ctx, "'proxy.max_request_time' must be >= 1");
            }
            if (proxy.ttl() < -1)
            {
                result.addError(ctx, "'proxy.ttl' must be >= -1");
            }
        }

        // --- Options Config ---
        validator.required(ctx, "options", options);
        if (options != null)
        {
            if (options.bufferSize() < 1024)
            {
                result.addError(ctx, "'options.buffer_size' must be >= 1024 (1KB)");
            }
            if (options.maxHeaderSize() < 1)
            {
                result.addError(ctx, "'options.max_header_size' must be >= 1");
            }
            if (options.maxHeaderCount() < 1)
            {
                result.addError(ctx, "'options.max_header_count' must be >= 1");
            }
            if (options.requestParseTimeout() < -1)
            {
                result.addError(ctx, "'options.request_parse_timeout' must be >= -1");
            }
        }

        // --- Storage Config ---
        validator.required(ctx, "storage", storage);
        if (storage != null)
        {
            validator.required(ctx, "storage.work_dir", storage.workDir());
        }
    }

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
            int backlog,
            int readTimeout
    )
    {
    }

    public record StorageConfig(
            String workDir
    )
    {
    }

    public record OptionsConfig(
            boolean enableHttp2,
            boolean alwaysSetKeepAlive,
            int bufferSize,
            boolean directBuffers,
            int maxHeaderSize,
            int maxHeaderCount,
            int requestParseTimeout
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