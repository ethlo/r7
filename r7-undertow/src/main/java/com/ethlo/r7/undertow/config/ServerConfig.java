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
        return new ServerConfig(null, null, null, null, null, null, null);
    }

    @Override
    public ServerCoreConfig server()
    {
        return Optional.ofNullable(this.server).orElse(new ServerCoreConfig(null, null));
    }

    @Override
    public ManagementConfig management()
    {
        return Optional.ofNullable(this.management).orElse(new ManagementConfig(null, null));
    }

    @Override
    public HttpConfig http()
    {
        return Optional.ofNullable(this.http).orElse(new HttpConfig(null, null, null));
    }

    @Override
    public ProxyConfig proxy()
    {
        return Optional.ofNullable(this.proxy).orElse(new ProxyConfig(null, null, null, null));
    }

    @Override
    public LimitsConfig limits()
    {
        return Optional.ofNullable(this.limits).orElse(new LimitsConfig(null, null, null, null, null));
    }

    @Override
    public StorageConfig storage()
    {
        return Optional.ofNullable(this.storage).orElse(new StorageConfig(null, null, null, null));
    }

    @Override
    public AdvancedConfig advanced()
    {
        return Optional.ofNullable(this.advanced).orElse(new AdvancedConfig(null, null, null, null, null, null, null, null, null, null, null, null));
    }

    @Override
    public void validate(final ValidationResult result)
    {
        // Recursively pass validation down the tree, letting the nested path prefixes build automatically
        this.server().validate(result.nested("server"));
        this.management().validate(result.nested("management"));
        this.http().validate(result.nested("http"));
        this.proxy().validate(result.nested("proxy"));
        this.limits().validate(result.nested("limits"));
        this.storage().validate(result.nested("storage"));
        this.advanced().validate(result.nested("advanced"));
    }

    // =========================================================
    // Core Server (Data Plane)
    // =========================================================
    public record ServerCoreConfig(String host, Integer port) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            final ValidatorUtils v = new ValidatorUtils(result);
            v.required("host", this.host());
            v.required("port", this.port());

            if (this.port() < 1 || this.port() > 65535)
            {
                result.addError("port", "must be between 1 and 65535");
            }
        }

        @Override
        public String host()
        {
            return Optional.ofNullable(this.host).orElse("0.0.0.0");
        }

        @Override
        public Integer port()
        {
            return Optional.ofNullable(this.port).orElse(8888);
        }
    }

    // =========================================================
    // Management (Control Plane)
    // =========================================================
    public record ManagementConfig(String host, Integer port) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            if (this.port() < 1 || this.port() > 65535)
            {
                result.addError("port", "must be between 1 and 65535");
            }
        }

        @Override
        public String host()
        {
            return Optional.ofNullable(this.host).orElse("0.0.0.0");
        }

        @Override
        public Integer port()
        {
            return Optional.ofNullable(this.port).orElse(18888);
        }
    }

    // =========================================================
    // HTTP
    // =========================================================
    public record HttpConfig(
            Boolean enableHttp2,
            Duration requestParseTimeout,
            Boolean alwaysSetKeepAlive
    ) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            if (this.requestParseTimeout().toMillis() < -1)
            {
                result.addError("request_parse_timeout", "must be >= -1");
            }
        }

        @Override
        public Boolean enableHttp2()
        {
            return Optional.ofNullable(this.enableHttp2).orElse(true);
        }

        @Override
        public Duration requestParseTimeout()
        {
            return Optional.ofNullable(this.requestParseTimeout).orElse(Duration.ofSeconds(2));
        }

        @Override
        public Boolean alwaysSetKeepAlive()
        {
            return Optional.ofNullable(this.alwaysSetKeepAlive).orElse(true);
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
    ) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            if (this.connectionsPerThread() < 1)
            {
                result.addError("connections_per_thread", "must be >= 1");
            }
        }

        @Override
        public Integer connectionsPerThread()
        {
            return Optional.ofNullable(this.connectionsPerThread).orElse(512);
        }

        @Override
        public Integer maxQueueSize()
        {
            return Optional.ofNullable(this.maxQueueSize).orElse(1000);
        }

        @Override
        public Duration maxRequestTime()
        {
            return Optional.ofNullable(this.maxRequestTime).orElse(Duration.ofMinutes(1));
        }

        @Override
        public Duration ttl()
        {
            return Optional.ofNullable(this.ttl).orElse(Duration.ofSeconds(30));
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
    ) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            if (this.maxHeaderSize().toBytes() < 1024)
            {
                result.addError("max_header_size", "must be >= 1024 bytes");
            }
            if (this.maxEntitySize().toBytes() < 1024)
            {
                result.addError("max_entity_size", "must be >= 1KB");
            }
            if (this.maxHeaderCount() < 1)
            {
                result.addError("max_header_count", "must be >= 1");
            }
            if (this.maxParameterCount() < 1)
            {
                result.addError("max_parameters", "must be >= 1");
            }
            if (this.maxCookieCount() < 1)
            {
                result.addError("max_cookies", "must be >= 1");
            }
        }

        @Override
        public DataSize maxHeaderSize()
        {
            return Optional.ofNullable(this.maxHeaderSize).orElse(DataSize.ofKilobytes(8));
        }

        @Override
        public Integer maxHeaderCount()
        {
            return Optional.ofNullable(this.maxHeaderCount).orElse(50);
        }

        @Override
        public DataSize maxEntitySize()
        {
            return Optional.ofNullable(this.maxEntitySize).orElse(DataSize.ofMegabytes(2));
        }

        @Override
        public Integer maxParameterCount()
        {
            return Optional.ofNullable(this.maxParameterCount).orElse(1000);
        }

        @Override
        public Integer maxCookieCount()
        {
            return Optional.ofNullable(this.maxCookieCount).orElse(200);
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
    ) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            final ValidatorUtils v = new ValidatorUtils(result);
            v.required("work_dir", this.workDir());

            if (this.shardCount() < 1)
            {
                result.addError("shard_count", "must be >= 1");
            }
        }

        @Override
        public String workDir()
        {
            return Optional.ofNullable(this.workDir).orElse("/tmp/r7/journal");
        }

        @Override
        public Integer shardCount()
        {
            return Optional.ofNullable(this.shardCount).orElse(1);
        }

        @Override
        public DataSize shardSize()
        {
            return Optional.ofNullable(this.shardSize).orElse(DataSize.ofMegabytes(200));
        }

        @Override
        public Boolean preFault()
        {
            return Optional.ofNullable(this.preFault).orElse(false);
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
    ) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            if (this.ioThreads() < 1)
            {
                result.addError("io_threads", "must be >= 1");
            }
            if (this.taskThreads() < 1)
            {
                result.addError("task_threads", "must be >= 1");
            }
            if (this.socketBacklog() < 1)
            {
                result.addError("socket_backlog", "must be >= 1");
            }
        }

        @Override
        public Integer ioThreads()
        {
            return Optional.ofNullable(this.ioThreads)
                    .orElse(Math.max(2, Runtime.getRuntime().availableProcessors()));
        }

        @Override
        public Integer taskThreads()
        {
            return Optional.ofNullable(this.taskThreads).orElse(this.ioThreads() * 8);
        }

        @Override
        public Integer connectionHighWater()
        {
            return Optional.ofNullable(this.connectionHighWater).orElse(20000);
        }

        @Override
        public Integer connectionLowWater()
        {
            return Optional.ofNullable(this.connectionLowWater).orElse(10000);
        }

        @Override
        public Boolean tcpNoDelay()
        {
            return Optional.ofNullable(this.tcpNoDelay).orElse(true);
        }

        @Override
        public Boolean reuseAddresses()
        {
            return Optional.ofNullable(this.reuseAddresses).orElse(true);
        }

        @Override
        public Integer socketBacklog()
        {
            return Optional.ofNullable(this.socketBacklog).orElse(1000);
        }

        @Override
        public Duration socketReadTimeout()
        {
            return Optional.ofNullable(this.socketReadTimeout).orElse(Duration.ofSeconds(30));
        }

        @Override
        public Boolean directBuffers()
        {
            return Optional.ofNullable(this.directBuffers).orElse(true);
        }

        @Override
        public Boolean recordRequestStartTime()
        {
            return Optional.ofNullable(this.recordRequestStartTime).orElse(false);
        }

        @Override
        public Boolean enableStatistics()
        {
            return Optional.ofNullable(this.enableStatistics).orElse(false);
        }

        @Override
        public Boolean trackActiveRequests()
        {
            return Optional.ofNullable(this.trackActiveRequests).orElse(false);
        }
    }
}