package com.ethlo.r7.graal;

import java.util.ServiceLoader;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;
import org.graalvm.nativeimage.hosted.RuntimeResourceAccess;

import com.ethlo.r7.config.RoutesDefinition;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.spi.GatewayPredicateFactory;

public final class R7ReflectionFeature implements Feature
{
    @Override
    public void beforeAnalysis(final BeforeAnalysisAccess access)
    {
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
        final Class<?>[] explicitClasses = new Class<?>[]{

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

                // Core Routing Config
                com.ethlo.r7.config.TargetConfig.class,
                com.ethlo.r7.config.RouteJournalConfig.class,
                com.ethlo.r7.config.JournalDirectionConfig.class,
                RoutesDefinition.class,
                com.ethlo.r7.config.UpstreamConfig.class,
                com.ethlo.r7.config.HealthCheckConfig.class,
                com.ethlo.r7.config.TimeoutConfig.class,
                com.ethlo.r7.config.RouteDefinition.class,
                com.ethlo.r7.config.ConditionDefinition.class,
                com.ethlo.r7.config.FilterDefinition.class,

                com.ethlo.r7.undertow.config.ServerConfig.class,
                com.ethlo.r7.undertow.config.ServerConfig.WorkerConfig.class,
                com.ethlo.r7.undertow.config.ServerConfig.SocketConfig.class,
                com.ethlo.r7.undertow.config.ServerConfig.OptionsConfig.class,
                com.ethlo.r7.undertow.config.ServerConfig.StorageConfig.class,
                com.ethlo.r7.undertow.config.ServerConfig.ProxyConfig.class,

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

        // Special case: java.lang.Record only needs its declared methods registered
        RuntimeReflection.register(java.lang.Record.class.getDeclaredMethods());
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
                RuntimeReflection.registerAllRecordComponents(clazz);
            }
        }
    }
}