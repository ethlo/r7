package com.ethlo.r7.graal;

import java.util.ServiceLoader;

import com.ethlo.r7.config.JournalDefinition;

import com.ethlo.r7.config.JournalDirectionConfig;

import com.ethlo.r7.config.JournalDirectionDefinition;

import com.ethlo.r7.status.SparklineRingBuffer;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;
import org.graalvm.nativeimage.hosted.RuntimeResourceAccess;

import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.spi.GatewayPredicateFactory;
import com.ethlo.r7.undertow.config.ServerConfig;

public final class R7ReflectionFeature implements Feature
{
    @Override
    public void beforeAnalysis(final BeforeAnalysisAccess access)
    {
        registerJBossLoggers();

        registerExplicitConfigClasses();

        final ServiceLoader<GatewayFilterFactory> filters = ServiceLoader.load(GatewayFilterFactory.class);
        for (final GatewayFilterFactory<?> factory : filters)
        {
            registerRecordForReflection(factory.configClass());
        }

        final ServiceLoader<GatewayPredicateFactory> predicates = ServiceLoader.load(GatewayPredicateFactory.class);
        for (final GatewayPredicateFactory<?> factory : predicates)
        {
            registerRecordForReflection(factory.configClass());
        }

        registerCaffeineCaches();

        registerDashboardResources();
    }

    private void registerCaffeineCaches()
    {
        final String[] caffeineClasses = new String[]{
                "com.github.benmanes.caffeine.cache.SSMSA",
                "com.github.benmanes.caffeine.cache.PSAMS",
                "com.github.benmanes.caffeine.cache.SSMSW",
                "com.github.benmanes.caffeine.cache.PSW",
                "com.github.benmanes.caffeine.cache.PSA"
        };

        for (final String className : caffeineClasses)
        {
            try
            {
                final Class<?> clazz = Class.forName(className);
                RuntimeReflection.register(clazz);
                // Caffeine needs the constructor to instantiate the generated cache
                RuntimeReflection.register(clazz.getDeclaredConstructors());
            }
            catch (final ClassNotFoundException ignored)
            {
                // Failsafe in case a future Caffeine version renames these internal classes
            }
        }
    }

    private void registerDashboardResources()
    {
        final Module module = R7ReflectionFeature.class.getModule();

        // Note: Do not use a leading slash here. GraalVM requires the exact internal JAR path.
        RuntimeResourceAccess.addResource(module, "dashboard/default/page.html");
        RuntimeResourceAccess.addResource(module, "dashboard/default/style.css");
        RuntimeResourceAccess.addResource(module, "dashboard/default/script.js");
    }

    private void registerExplicitConfigClasses()
    {
        // Allow GraalVM to introspect base Record classes
        RuntimeReflection.register(java.lang.Record.class.getDeclaredMethods());

        // Allow Jackson 3 to reflectively call getName() and getType() on RecordComponent
        RuntimeReflection.register(java.lang.reflect.RecordComponent.class.getDeclaredMethods());

        final Class<?>[] explicitClasses = new Class<?>[]{

                com.ethlo.r7.status.dto.FilterNode.class,
                com.ethlo.r7.status.dto.MemoryDto.class,
                com.ethlo.r7.status.dto.RouteMetricsDto.class,
                com.ethlo.r7.status.dto.RequestStatsDto.class,
                com.ethlo.r7.status.dto.TrafficFlowDto.class,
                com.ethlo.r7.status.dto.PerformanceTelemetryDto.class,
                com.ethlo.r7.status.dto.EgressDto.class,
                com.ethlo.r7.status.dto.IngressDto.class,
                com.ethlo.r7.status.dto.RequestStatsDto.class,
                com.ethlo.r7.status.dto.RouteConfigDto.class,
                com.ethlo.r7.status.dto.FilterDto.class,
                com.ethlo.r7.status.dto.MatchDto.class,
                com.ethlo.r7.status.dto.ConnectorStatisticsDto.class,
                com.ethlo.r7.status.dto.JournalDto.class,

                SparklineRingBuffer.class,
                SparklineRingBuffer.SparklineSnapshot.class,
                SparklineRingBuffer.SparklineMetadata.class,

                // Core Routing Config
                JournalDefinition.class,
                JournalDirectionConfig.class,
                JournalDirectionDefinition.class,
                com.ethlo.r7.config.TargetConfig.class,
                com.ethlo.r7.config.RouteJournalConfig.class,
                com.ethlo.r7.config.JournalDirectionConfig.class,
                com.ethlo.r7.config.RoutesDefinition.class,
                com.ethlo.r7.config.FallbackConfig.class,
                com.ethlo.r7.config.UpstreamConfig.class,
                com.ethlo.r7.config.HealthCheckConfig.class,
                com.ethlo.r7.config.TimeoutConfig.class,
                com.ethlo.r7.config.RouteDefinition.class,
                com.ethlo.r7.config.ConditionDefinition.class,
                com.ethlo.r7.config.FilterDefinition.class,

                ServerConfig.class,
                ServerConfig.ServerCoreConfig.class,
                ServerConfig.HttpConfig.class,
                ServerConfig.AdvancedConfig.class,
                ServerConfig.LimitsConfig.class,
                ServerConfig.StorageConfig.class,
                ServerConfig.ProxyConfig.class,
                ServerConfig.ManagementConfig.class,

                // Logback / Logging Infrastructure
                ch.qos.logback.classic.joran.JoranConfigurator.class,
                ch.qos.logback.core.ConsoleAppender.class,
                ch.qos.logback.classic.AsyncAppender.class,
                ch.qos.logback.classic.encoder.PatternLayoutEncoder.class,
                ch.qos.logback.core.encoder.LayoutWrappingEncoder.class,
                ch.qos.logback.classic.PatternLayout.class,
                ch.qos.logback.classic.LoggerContext.class,
                ch.qos.logback.core.status.StatusListener.class
        };

        for (final Class<?> clazz : explicitClasses)
        {
            registerRecordForReflection(clazz);
        }
    }

    private void registerRecordForReflection(final Class<?> clazz)
    {
        if (clazz != null)
        {
            RuntimeReflection.register(clazz);
            RuntimeReflection.register(clazz.getDeclaredConstructors());
            RuntimeReflection.register(clazz.getConstructors());
            RuntimeReflection.register(clazz.getDeclaredMethods());
            RuntimeReflection.register(clazz.getMethods());
            RuntimeReflection.register(clazz.getDeclaredFields());

            if (clazz.isRecord())
            {
                // Register the record metadata
                RuntimeReflection.registerAllRecordComponents(clazz);

                // BRUTE FORCE: Explicitly register every accessor method.
                // This prevents GraalVM from stripping the getters, which is
                // what causes the UnsupportedFeatureError.
                for (final java.lang.reflect.RecordComponent rc : clazz.getRecordComponents())
                {
                    RuntimeReflection.register(rc.getAccessor());
                }
            }
        }
    }

    private void registerJBossLoggers()
    {
        // JBoss logging generates these classes at build-time.
        // We must manually retain them for reflection so Undertow and XNIO don't crash.
        final String[] jbossGeneratedClasses = new String[]{
                "org.xnio._private.Messages_$logger",
                "org.xnio.nio.Log_$logger",
                "io.undertow.UndertowLogger_$logger",
                "io.undertow.UndertowMessages_$bundle"
        };

        for (final String className : jbossGeneratedClasses)
        {
            try
            {
                final Class<?> clazz = Class.forName(className);
                RuntimeReflection.register(clazz);
                RuntimeReflection.register(clazz.getDeclaredConstructors());
            }
            catch (final ClassNotFoundException ignored)
            {
                // Safely ignore if a specific version of Undertow/XNIO drops one of these classes
            }
        }
    }
}