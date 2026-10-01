package com.ethlo.r7.server.config;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.ethlo.r7.config.model.DataSize;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.journal.HeaderFingerprint;
import com.ethlo.r7.r7f.R7fJournalProvider;
import com.ethlo.r7.util.CidrRange;
import com.ethlo.r7.util.RegexBudget;
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
        return Optional.ofNullable(this.proxy).orElse(new ProxyConfig(null, null, null, null, null));
    }

    @Override
    public LimitsConfig limits()
    {
        return Optional.ofNullable(this.limits).orElse(new LimitsConfig(null, null, null, null, null, null));
    }

    @Override
    public StorageConfig storage()
    {
        return Optional.ofNullable(this.storage).orElse(new StorageConfig(null, null, null, null, null, null, null));
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
    public record ManagementConfig(
            String host,
            Integer port,
            Duration requestParseTimeout,
            Duration readTimeout,
            Duration idleTimeout,
            Integer maxConnections,
            List<String> allowedHosts
    ) implements ValidatableConfig
    {
        public ManagementConfig(final String host, final Integer port)
        {
            this(host, port, null, null, null, null, null);
        }

        @Override
        public void validate(final ValidationResult result)
        {
            if (this.port() < 1 || this.port() > 65535)
            {
                result.addError("port", "must be between 1 and 65535");
            }
            // All three go to Undertow/XNIO as int milliseconds, and none may be off: a management
            // connection that is never timed out holds a file descriptor the data plane shares.
            final ValidatorUtils v = new ValidatorUtils(result);
            v.requirePositive("request_parse_timeout", this.requestParseTimeout(), ValidatorUtils.MAX_INT_MILLIS);
            v.requirePositive("read_timeout", this.readTimeout(), ValidatorUtils.MAX_INT_MILLIS);
            v.requirePositive("idle_timeout", this.idleTimeout(), ValidatorUtils.MAX_INT_MILLIS);
            v.requirePositive("max_connections", this.maxConnections());
            for (final String allowedHost : this.allowedHosts())
            {
                v.notBlank("allowed_hosts", allowedHost);
            }
        }

        /**
         * Loopback unless configured otherwise: the management endpoint has no authentication and
         * shows the gateway's configuration, so reaching it from elsewhere has to be a decision.
         * {@code R7_MANAGEMENT_HOST} sets the default without a server.yaml - the container images
         * use it to listen on all interfaces, which their port mapping needs - and an explicit
         * {@code management.host} in server.yaml takes precedence over both.
         */
        @Override
        public String host()
        {
            return Optional.ofNullable(this.host).orElseGet(ManagementConfig::defaultHost);
        }

        static String defaultHost()
        {
            final String fromEnvironment = System.getenv(HOST_ENVIRONMENT_VARIABLE);
            return fromEnvironment != null && !fromEnvironment.isBlank() ? fromEnvironment.strip() : DEFAULT_HOST;
        }

        static final String HOST_ENVIRONMENT_VARIABLE = "R7_MANAGEMENT_HOST";
        static final String DEFAULT_HOST = "127.0.0.1";

        @Override
        public Integer port()
        {
            return Optional.ofNullable(this.port).orElse(18888);
        }

        /**
         * Time allowed to receive a complete request head. Without it a client that sends part
         * of a header block and then nothing keeps its connection open indefinitely.
         */
        @Override
        public Duration requestParseTimeout()
        {
            return Optional.ofNullable(this.requestParseTimeout).orElse(Duration.ofSeconds(2));
        }

        @Override
        public Duration readTimeout()
        {
            return Optional.ofNullable(this.readTimeout).orElse(Duration.ofSeconds(30));
        }

        /**
         * How long a connection may sit between requests before it is closed.
         */
        @Override
        public Duration idleTimeout()
        {
            return Optional.ofNullable(this.idleTimeout).orElse(Duration.ofSeconds(30));
        }

        /**
         * The dashboard polls from a handful of browsers; this cap keeps the management port from
         * being able to take file descriptors the data plane needs. Connections past it wait in
         * the kernel's accept backlog instead of being accepted.
         */
        @Override
        public Integer maxConnections()
        {
            return Optional.ofNullable(this.maxConnections).orElse(64);
        }

        /**
         * Host names, besides {@code localhost} and the bind host, that requests may name in
         * {@code Host}. IP literals are always accepted: DNS rebinding needs a name.
         */
        @Override
        public List<String> allowedHosts()
        {
            return Optional.ofNullable(this.allowedHosts).orElse(List.of());
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
            // Undertow's REQUEST_PARSE_TIMEOUT is an int of milliseconds
            new ValidatorUtils(result).fitsIntMillis("request_parse_timeout", this.requestParseTimeout());
        }

        /**
         * Off unless asked for. The listener is plaintext only, so HTTP/2 here means h2c (prior
         * knowledge or {@code Upgrade: h2c}): a second, far more complex parser exposed to every
         * client, and the protocol behind the rapid-reset and CONTINUATION-flood attacks, for a
         * benefit most deployments behind a load balancer never see.
         */
        @Override
        public Boolean enableHttp2()
        {
            return Optional.ofNullable(this.enableHttp2).orElse(false);
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
            Duration ttl,
            @Description("Which upstream client proxies requests on Undertow: 'undertow' (its own proxy, the default) or 'r7' "
                    + "(the blocking client every r7 server shares, on virtual threads). Experimental; see design/upstream.md.")
            String client
    ) implements ValidatableConfig
    {
        public static final String CLIENT_UNDERTOW = "undertow";
        public static final String CLIENT_R7 = "r7";

        @Override
        public void validate(final ValidationResult result)
        {
            if (!CLIENT_UNDERTOW.equals(this.client()) && !CLIENT_R7.equals(this.client()))
            {
                result.addError("client", "must be '" + CLIENT_UNDERTOW + "' or '" + CLIENT_R7 + "'");
            }
            if (this.connectionsPerThread() < 1)
            {
                result.addError("connections_per_thread", "must be >= 1");
            }
            // Both are handed to Undertow's proxy as int milliseconds when a route's upstream
            // context is built, which on a hot reload is long after this could name the field.
            final ValidatorUtils v = new ValidatorUtils(result);
            v.fitsIntMillis("max_request_time", this.maxRequestTime());
            v.fitsIntMillis("ttl", this.ttl());
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

        @Override
        public String client()
        {
            return this.client == null ? CLIENT_UNDERTOW : this.client.trim().toLowerCase(java.util.Locale.ROOT);
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
            Integer maxCookieCount,

            @Description("CIDR ranges of reverse proxies allowed to set X-Forwarded-For/X-Real-IP. "
                    + "Empty by default: the socket address is always used unless the immediate peer is in this list, "
                    + "so a direct client cannot spoof its own address by sending either header.")
            List<String> trustedProxies
    ) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            if (this.maxHeaderSize().bytes() < 1024)
            {
                result.addError("max_header_size", "must be >= 1024 bytes");
            }
            // Route and filter patterns match header values and the request line under a fixed
            // regex budget; past this size a perfectly linear pattern could exhaust it and turn a
            // legitimate request into a 500.
            if (this.maxHeaderSize().bytes() > RegexBudget.MAX_INPUT_LENGTH)
            {
                result.addError("max_header_size", "must be <= " + RegexBudget.MAX_INPUT_LENGTH + " bytes, the longest input configured regular expressions are budgeted for");
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
            for (final String cidr : this.trustedProxies())
            {
                try
                {
                    CidrRange.parse(cidr);
                }
                catch (final IllegalArgumentException e)
                {
                    result.addError("trusted_proxies", "invalid IP or CIDR notation: '" + cidr + "'");
                }
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

        @Override
        public List<String> trustedProxies()
        {
            return Optional.ofNullable(this.trustedProxies).orElse(List.of());
        }
    }

    // =========================================================
    // Storage
    // =========================================================
    public record StorageConfig(
            String workDir,
            Integer shardCount,
            DataSize shardSize,
            Boolean preFault,
            JournalSecurityConfig journalSecurity,
            String compression,
            Integer compressionLevel
    ) implements ValidatableConfig
    {
        public static final String COMPRESSION_ZSTD = "zstd";
        public static final String COMPRESSION_NONE = "none";

        @Override
        public void validate(final ValidationResult result)
        {
            final ValidatorUtils v = new ValidatorUtils(result);
            v.required("work_dir", this.workDir());
            this.journalSecurity().validate(result.nested("journal_security"));

            if (!COMPRESSION_ZSTD.equals(this.compression()) && !COMPRESSION_NONE.equals(this.compression()))
            {
                result.addError("compression", "must be '" + COMPRESSION_ZSTD + "' or '" + COMPRESSION_NONE
                        + "', but was '" + this.compression + "'");
            }
            final int level = this.compressionLevel();
            if (level < R7fJournalProvider.MIN_COMPRESSION_LEVEL || level > R7fJournalProvider.MAX_COMPRESSION_LEVEL)
            {
                result.addError("compression_level", "must be from " + R7fJournalProvider.MIN_COMPRESSION_LEVEL
                        + " to " + R7fJournalProvider.MAX_COMPRESSION_LEVEL + ", but was " + level
                        + ". Level 1 is the default and the measured sweet spot; higher levels cost throughput for little gain.");
            }

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
            else if (shardSizeBytes % R7fJournalProvider.SEGMENT_SIZE_MULTIPLE != 0)
            {
                result.addError("shard_size", "must be a multiple of " + R7fJournalProvider.SEGMENT_SIZE_MULTIPLE
                        + " bytes (" + R7fJournalProvider.SEGMENT_SIZE_MULTIPLE / 1024 + "KB), but was " + shardSizeBytes
                        + ". Journal segments are cut into blocks of that size.");
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

        /**
         * {@code R7_JOURNAL_DIR} sets the default without a server.yaml, and an explicit
         * {@code storage.work_dir} takes precedence over it. The container images set it to
         * /journals, the one directory they prepare for their nonroot user: without it the
         * relative default resolves under /app, which that user cannot create, and a container
         * started with no server.yaml died at startup.
         */
        @Override
        public String workDir()
        {
            return Optional.ofNullable(this.workDir).orElseGet(StorageConfig::defaultWorkDir);
        }

        static String defaultWorkDir()
        {
            final String fromEnvironment = System.getenv(WORK_DIR_ENVIRONMENT_VARIABLE);
            return fromEnvironment != null && !fromEnvironment.isBlank() ? fromEnvironment.strip() : DEFAULT_WORK_DIR;
        }

        static final String WORK_DIR_ENVIRONMENT_VARIABLE = "R7_JOURNAL_DIR";
        static final String DEFAULT_WORK_DIR = "journals";

        /**
         * Two by default: with page faults taken ahead of the writer, one shard already keeps up,
         * but a thread-per-connection server queues every connection's writer on it. A second
         * shard halves that queue for one more open segment and no memory of note. See
         * docs/performance_tuning.md.
         */
        @Override
        public Integer shardCount()
        {
            return Optional.ofNullable(this.shardCount).orElse(2);
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

        @Override
        public JournalSecurityConfig journalSecurity()
        {
            return Optional.ofNullable(this.journalSecurity).orElse(new JournalSecurityConfig(null, null, null, null, null));
        }

        /**
         * zstd by default: at level 1 it made the journal 9-15x smaller on benchmark traffic,
         * with no measurable latency at 1,000 req/s, for 17-40% of peak throughput at
         * saturation. See docs/performance_tuning.md.
         */
        @Override
        public String compression()
        {
            return this.compression == null ? COMPRESSION_ZSTD : this.compression.trim().toLowerCase(java.util.Locale.ROOT);
        }

        @Override
        public Integer compressionLevel()
        {
            return Optional.ofNullable(this.compressionLevel).orElse(1);
        }

        /**
         * The zstd level the journal writes with, or 0 for no compression.
         */
        public int journalCompressionLevel()
        {
            return COMPRESSION_ZSTD.equals(this.compression()) ? this.compressionLevel() : 0;
        }
    }

    /**
     * Controls the whitelist of header names journaled in plain text (see
     * {@link com.ethlo.r7.journal.JournalSecurity}). Anything not on the resulting list is
     * fingerprinted, no exceptions. Two independent ways to shape it, per direction:
     * <ul>
     *     <li>{@code additional_safe_*_headers} adds names on top of the built-in defaults.</li>
     *     <li>{@code safe_*_headers}, if non-empty, replaces the built-in defaults entirely —
     *     the resulting whitelist is exactly this list, and nothing else.</li>
     * </ul>
     * Setting both for the same direction is rejected: a full replacement and an addition to
     * the defaults it replaces is a contradiction, not a merge to guess at.
     * <p>
     * {@code fingerprint_key}, when set, keys the fingerprint written in place of a redacted
     * value, so that a reader of the journal cannot confirm a guessed value by hashing it
     * (see {@link com.ethlo.r7.journal.HeaderFingerprint}). Normally supplied through
     * {@code ${VAR}} interpolation rather than written into the file.
     */
    public record JournalSecurityConfig(
            List<String> additionalSafeRequestHeaders,
            List<String> additionalSafeResponseHeaders,
            List<String> safeRequestHeaders,
            List<String> safeResponseHeaders,
            String fingerprintKey
    ) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            // Checked here, against the constant HeaderFingerprint enforces, so that a short key
            // is a startup error naming this field rather than a constructor throwing later.
            if (this.fingerprintKey != null && this.fingerprintKey.length() < HeaderFingerprint.MIN_KEY_LENGTH)
            {
                result.addError("fingerprint_key", "must be at least " + HeaderFingerprint.MIN_KEY_LENGTH
                        + " characters (it is the whole secret behind every redacted header value); was "
                        + this.fingerprintKey.length() + ". Generate one with, for example, `openssl rand -base64 48`.");
            }

            requireValidHeaderTokens(result, "additional_safe_request_headers", this.additionalSafeRequestHeaders());
            requireValidHeaderTokens(result, "additional_safe_response_headers", this.additionalSafeResponseHeaders());
            requireValidHeaderTokens(result, "safe_request_headers", this.safeRequestHeaders());
            requireValidHeaderTokens(result, "safe_response_headers", this.safeResponseHeaders());

            if (!this.safeRequestHeaders().isEmpty() && !this.additionalSafeRequestHeaders().isEmpty())
            {
                result.addError("safe_request_headers", "must not be set together with additional_safe_request_headers: "
                        + "safe_request_headers replaces the whitelist entirely, so adding to it is ambiguous");
            }
            if (!this.safeResponseHeaders().isEmpty() && !this.additionalSafeResponseHeaders().isEmpty())
            {
                result.addError("safe_response_headers", "must not be set together with additional_safe_response_headers: "
                        + "safe_response_headers replaces the whitelist entirely, so adding to it is ambiguous");
            }
        }

        /**
         * A name that is not a valid HTTP token (RFC 9110 §5.6.2) can never match a wire
         * header, so it would silently make the configured entry a no-op rather than the
         * error it should be. {@link ValidatorUtils#httpToken} treats {@code null} as a
         * no-op (it is meant for optional single values), so a {@code null} list element
         * is rejected explicitly here before it can reach {@code JournalSecurity.resolve}
         * and blow up on {@code name.toLowerCase(...)}.
         */
        private static void requireValidHeaderTokens(final ValidationResult result, final String field, final List<String> names)
        {
            final ValidatorUtils v = new ValidatorUtils(result);
            for (final String name : names)
            {
                if (name == null)
                {
                    result.addError(field, "header names must not be null");
                }
                else
                {
                    v.httpToken(field, name);
                }
            }
        }

        @Override
        public List<String> additionalSafeRequestHeaders()
        {
            return Optional.ofNullable(this.additionalSafeRequestHeaders).orElse(List.of());
        }

        @Override
        public List<String> additionalSafeResponseHeaders()
        {
            return Optional.ofNullable(this.additionalSafeResponseHeaders).orElse(List.of());
        }

        @Override
        public List<String> safeRequestHeaders()
        {
            return Optional.ofNullable(this.safeRequestHeaders).orElse(List.of());
        }

        @Override
        public List<String> safeResponseHeaders()
        {
            return Optional.ofNullable(this.safeResponseHeaders).orElse(List.of());
        }

        /**
         * The key never appears here: the whole server config is logged at startup.
         */
        @Override
        public String toString()
        {
            return "JournalSecurityConfig[additionalSafeRequestHeaders=" + this.additionalSafeRequestHeaders()
                    + ", additionalSafeResponseHeaders=" + this.additionalSafeResponseHeaders()
                    + ", safeRequestHeaders=" + this.safeRequestHeaders()
                    + ", safeResponseHeaders=" + this.safeResponseHeaders()
                    + ", fingerprintKey=" + (this.fingerprintKey == null ? "unset" : "******") + "]";
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
            // XNIO's READ_TIMEOUT is an int of milliseconds
            new ValidatorUtils(result).fitsIntMillis("socket_read_timeout", this.socketReadTimeout());
            if (this.connectionLowWater() < 1)
            {
                result.addError("connection_low_water", "must be >= 1");
            }
            if (this.connectionHighWater() < this.connectionLowWater())
            {
                result.addError("connection_high_water", "must be >= connection_low_water (" + this.connectionLowWater() + ")");
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