package com.ethlo.r7.undertow.config;

import java.time.Duration;
import java.util.Optional;

import com.ethlo.r7.config.model.DataSize;
import com.ethlo.r7.r7f.R7fJournalProvider;
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
        return Optional.ofNullable(this.advanced).orElse(new AdvancedConfig(null, null, null, null, null, null, null, null));
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
            if (this.maxHeaderSize().bytes() < 1024)
            {
                result.addError("max_header_size", "must be >= 1024 bytes");
            }
            if (this.maxEntitySize().bytes() < 1024)
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

            final int shardCount = this.shardCount();
            if (shardCount < 1)
            {
                result.addError("shard_count", "must be >= 1");
            }
            else if (!isPowerOfTwo(shardCount))
            {
                // The writer picks a shard by masking the request id's hash, which only
                // distributes evenly when the count is a power of two. Without this check the
                // configuration passes validation and ShardedJournalWriter then refuses to be
                // constructed, so an operator sees a stack trace at startup instead of being
                // told which value to use.
                result.addError("shard_count", "must be a power of two, but was " + shardCount
                        + ". The nearest valid values are " + Integer.highestOneBit(shardCount)
                        + " and " + nextPowerOfTwo(shardCount) + ".");
            }

            // Same reasoning as shard_count: R7fJournalProvider refuses these values in its
            // constructor, and without the check here that refusal arrives as a stack trace at
            // startup instead of as an error naming the field. The bounds are the provider's
            // own constants so the two cannot drift apart.
            final long shardSizeBytes = this.shardSize().bytes();
            if (shardSizeBytes < R7fJournalProvider.MIN_SEGMENT_SIZE)
            {
                result.addError("shard_size", "must be at least " + R7fJournalProvider.MIN_SEGMENT_SIZE
                        + " bytes, but was " + shardSizeBytes
                        + ". A segment has to hold the preamble plus at least one entry.");
            }
            else if (shardSizeBytes > R7fJournalProvider.MAX_SEGMENT_SIZE)
            {
                result.addError("shard_size", "must not exceed " + R7fJournalProvider.MAX_SEGMENT_SIZE
                        + " bytes, but was " + shardSizeBytes
                        + ". Readers address segment offsets with ints.");
            }
        }

        private static boolean isPowerOfTwo(final int value)
        {
            return (value & (value - 1)) == 0;
        }

        /**
         * Smallest power of two above {@code value}, saturating rather than overflowing into a
         * negative number that would make the advice nonsense.
         */
        private static int nextPowerOfTwo(final int value)
        {
            final int highest = Integer.highestOneBit(value);
            return highest >= (1 << 30) ? highest : highest << 1;
        }

        @Override
        public String workDir()
        {
            return Optional.ofNullable(this.workDir).orElse("journals");
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
            Duration socketReadTimeout
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
    }
}