package com.ethlo.r7.undertow.config;

import java.time.Duration;
import java.util.Optional;

import com.ethlo.r7.config.DataSize;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public record ServerConfig(
        ServerCoreConfig server,
        ManagementConfig management,
        HttpConfig http,
        ProxyConfig proxy,
        LimitsConfig limits,
        StorageConfig storage,
        AdvancedConfig advanced
) implements ValidatableConfig
{
    public static ServerConfig standard()
    {
        return new ServerConfig(
                null, null, null, null, null, null, null
        );
    }

    @Override
    public ServerCoreConfig server()
    {
        return Optional.ofNullable(server).orElse(new ServerCoreConfig(null, null));
    }

    @Override
    public ManagementConfig management()
    {
        return Optional.ofNullable(management).orElse(new ManagementConfig(null, null));
    }

    @Override
    public HttpConfig http()
    {
        return Optional.ofNullable(http).orElse(new HttpConfig(null, null, null));
    }

    @Override
    public ProxyConfig proxy()
    {
        return Optional.ofNullable(proxy).orElse(new ProxyConfig(null, null, null, null));
    }

    @Override
    public LimitsConfig limits()
    {
        return Optional.ofNullable(limits).orElse(new LimitsConfig(null, null, null, null, null));
    }

    @Override
    public StorageConfig storage()
    {
        return Optional.ofNullable(storage).orElse(new StorageConfig(null, null, null, null));
    }

    @Override
    public AdvancedConfig advanced()
    {
        return Optional.ofNullable(advanced).orElse(new AdvancedConfig(null, null, null, null, null, null, null, null, null, null, null, null));
    }

    @Override
    public void validate(final ValidationResult result)
    {
        final ValidatorUtils v = new ValidatorUtils(result);
        final String ctx = "ServerConfig";

        // Core Server
        final ServerCoreConfig srv = server();
        v.required(ctx, "server.host", srv.host());
        v.required(ctx, "server.port", srv.port());

        if (srv.port() < 1 || srv.port() > 65535)
        {
            result.addError(ctx, "server.port must be 1-65535");
        }

        // Management
        final ManagementConfig mgt = management();
        if (mgt.port() < 1 || mgt.port() > 65535)
        {
            result.addError(ctx, "management.port must be 1-65535");
        }

        // HTTP
        final HttpConfig httpConfig = http();
        if (httpConfig.requestParseTimeout().toMillis() < -1)
        {
            result.addError(ctx, "http.request_parse_timeout must be >= -1");
        }

        // Limits
        final LimitsConfig limitsConfig = limits();
        if (limitsConfig.maxHeaderSize().toBytes() < 1024)
        {
            result.addError(ctx, "limits.max_header_size must be >= 1024");
        }
        if (limitsConfig.maxEntitySize().toBytes() < 1024)
        {
            result.addError(ctx, "limits.max_entity_size must be >= 1KB");
        }
        if (limitsConfig.maxHeaderCount() < 1)
        {
            result.addError(ctx, "limits.max_header_count must be >= 1");
        }
        if (limitsConfig.maxParameterCount() < 1)
        {
            result.addError(ctx, "limits.max_parameters must be >= 1");
        }
        if (limitsConfig.maxCookieCount() < 1)
        {
            result.addError(ctx, "limits.max_cookies must be >= 1");
        }

        // Proxy
        final ProxyConfig proxyConfig = proxy();
        if (proxyConfig.connectionsPerThread() < 1)
        {
            result.addError(ctx, "proxy.connections_per_thread must be >= 1");
        }
        if (proxyConfig.maxQueueSize() < 0)
        {
            result.addError(ctx, "proxy.max_queue_size must be >= 0");
        }
        final Object requestParseTimeout = proxyConfig.requestParseTimeout();
        if (requestParseTimeout instanceof Duration duration && duration.isNegative())
        {
            result.addError(ctx, "proxy.request_parse_timeout must be >= 0");
        }
        else if (requestParseTimeout instanceof Optional<?> optional
                && optional.isPresent()
                && optional.get() instanceof Duration duration
                && duration.isNegative())
        {
            result.addError(ctx, "proxy.request_parse_timeout must be >= 0");
        }
        final Object ttl = proxyConfig.ttl();
        if (ttl instanceof Duration duration && duration.isNegative())
        {
            result.addError(ctx, "proxy.ttl must be >= 0");
        }
        else if (ttl instanceof Optional<?> optional
                && optional.isPresent()
                && optional.get() instanceof Duration duration
                && duration.isNegative())
        {
            result.addError(ctx, "proxy.ttl must be >= 0");
        }

        // Storage
        final StorageConfig storageConfig = storage();
        v.required(ctx, "storage.work_dir", storageConfig.workDir());
        if (storageConfig.shardCount() < 1)
        {
            result.addError(ctx, "storage.shard_count must be >= 1");
        }

        // Advanced
        final AdvancedConfig adv = advanced();
        if (adv.ioThreads() < 1)
        {
            result.addError(ctx, "advanced.io_threads must be >= 1");
        }
        if (adv.taskThreads() < 1)
        {
            result.addError(ctx, "advanced.task_threads must be >= 1");
        }
        if (adv.socketBacklog() < 1)
        {
            result.addError(ctx, "advanced.socket_backlog must be >= 1");
        }
    }

    // =========================================================
    // Core Server (Data Plane)
    // =========================================================
    public record ServerCoreConfig(String host, Integer port)
    {
        @Override
        public String host()
        {
            return Optional.ofNullable(host).orElse("0.0.0.0");
        }

        @Override
        public Integer port()
        {
            return Optional.ofNullable(port).orElse(8888);
        }
    }

    // =========================================================
    // Management (Control Plane)
    // =========================================================
    public record ManagementConfig(String host, Integer port)
    {
        @Override
        public String host()
        {
            return Optional.ofNullable(host).orElse("0.0.0.0");
        }

        @Override
        public Integer port()
        {
            return Optional.ofNullable(port).orElse(18888);
        }
    }

    // =========================================================
    // HTTP
    // =========================================================
    public record HttpConfig(
            Boolean enableHttp2,
            Duration requestParseTimeout,
            Boolean alwaysSetKeepAlive
    )
    {
        @Override
        public Boolean enableHttp2()
        {
            return Optional.ofNullable(enableHttp2).orElse(true);
        }

        @Override
        public Duration requestParseTimeout()
        {
            return Optional.ofNullable(requestParseTimeout).orElse(Duration.ofSeconds(2));
        }

        @Override
        public Boolean alwaysSetKeepAlive()
        {
            return Optional.ofNullable(alwaysSetKeepAlive).orElse(true);
        }
    }

    // =========================================================
    // Proxy
    // =========================================================
    public record ProxyConfig(
            Integer connectionsPerThread,
            Integer maxQueueSize,
            Duration maxRequestTime,
            Duration ttl
    )
    {
        @Override
        public Integer connectionsPerThread()
        {
            return Optional.ofNullable(connectionsPerThread).orElse(512);
        }

        @Override
        public Integer maxQueueSize()
        {
            return Optional.ofNullable(maxQueueSize).orElse(1000);
        }

        @Override
        public Duration maxRequestTime()
        {
            return Optional.ofNullable(maxRequestTime).orElse(Duration.ofMinutes(1));
        }

        @Override
        public Duration ttl()
        {
            return Optional.ofNullable(ttl).orElse(Duration.ofSeconds(30));
        }
    }

    // =========================================================
    // Security & Limits
    // =========================================================
    public record LimitsConfig(
            DataSize maxHeaderSize,
            Integer maxHeaderCount,
            DataSize maxEntitySize,
            Integer maxParameterCount,
            Integer maxCookieCount
    )
    {
        @Override
        public DataSize maxHeaderSize()
        {
            return Optional.ofNullable(maxHeaderSize).orElse(DataSize.ofKilobytes(8));
        }

        @Override
        public Integer maxHeaderCount()
        {
            return Optional.ofNullable(maxHeaderCount).orElse(50);
        }

        @Override
        public DataSize maxEntitySize()
        {
            return Optional.ofNullable(maxEntitySize).orElse(DataSize.ofMegabytes(2));
        }

        @Override
        public Integer maxParameterCount()
        {
            return Optional.ofNullable(maxParameterCount).orElse(1000);
        }

        @Override
        public Integer maxCookieCount()
        {
            return Optional.ofNullable(maxCookieCount).orElse(200);
        }
    }

    // =========================================================
    // Storage
    // =========================================================
    public record StorageConfig(
            String workDir,
            Integer shardCount,
            DataSize shardSize,
            Boolean preFault
    )
    {
        @Override
        public String workDir()
        {
            return Optional.ofNullable(workDir).orElse("/tmp/r7/journal");
        }

        @Override
        public Integer shardCount()
        {
            return Optional.ofNullable(shardCount).orElse(1);
        }

        @Override
        public DataSize shardSize()
        {
            return Optional.ofNullable(shardSize).orElse(DataSize.ofMegabytes(200));
        }

        @Override
        public Boolean preFault()
        {
            return Optional.ofNullable(preFault).orElse(false);
        }
    }

    // =========================================================
    // Advanced (Undertow Internals & System)
    // =========================================================
    public record AdvancedConfig(
            Integer ioThreads,
            Integer taskThreads,
            Integer connectionHighWater,
            Integer connectionLowWater,
            Boolean tcpNoDelay,
            Boolean reuseAddresses,
            Integer socketBacklog,
            Duration socketReadTimeout,
            Boolean directBuffers,
            Boolean recordRequestStartTime,
            Boolean enableStatistics,
            Boolean trackActiveRequests
    )
    {
        @Override
        public Integer ioThreads()
        {
            return Optional.ofNullable(ioThreads)
                    .orElse(Math.max(2, Runtime.getRuntime().availableProcessors()));
        }

        @Override
        public Integer taskThreads()
        {
            return Optional.ofNullable(taskThreads).orElse(ioThreads() * 8);
        }

        @Override
        public Integer connectionHighWater()
        {
            return Optional.ofNullable(connectionHighWater).orElse(20000);
        }

        @Override
        public Integer connectionLowWater()
        {
            return Optional.ofNullable(connectionLowWater).orElse(10000);
        }

        @Override
        public Boolean tcpNoDelay()
        {
            return Optional.ofNullable(tcpNoDelay).orElse(true);
        }

        @Override
        public Boolean reuseAddresses()
        {
            return Optional.ofNullable(reuseAddresses).orElse(true);
        }

        @Override
        public Integer socketBacklog()
        {
            return Optional.ofNullable(socketBacklog).orElse(1000);
        }

        @Override
        public Duration socketReadTimeout()
        {
            return Optional.ofNullable(socketReadTimeout).orElse(Duration.ofSeconds(30));
        }

        @Override
        public Boolean directBuffers()
        {
            return Optional.ofNullable(directBuffers).orElse(true);
        }

        @Override
        public Boolean recordRequestStartTime()
        {
            return Optional.ofNullable(recordRequestStartTime).orElse(false);
        }

        @Override
        public Boolean enableStatistics()
        {
            return Optional.ofNullable(enableStatistics).orElse(false);
        }

        @Override
        public Boolean trackActiveRequests()
        {
            return Optional.ofNullable(trackActiveRequests).orElse(false);
        }
    }
}