package com.ethlo.r7.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import com.ethlo.r7.api.GatewayFilter;
import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayResponsePredicate;
import com.ethlo.r7.config.ConfigurationException;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.spi.GatewayPredicateFactory;
import com.ethlo.r7.spi.GatewayResponsePredicateFactory;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * What a plugin jar has to do to be found: a {@code META-INF/services} descriptor naming a factory,
 * in a jar the plugins directory holds. The factories here are on the test classpath, so the jars
 * carry only the descriptors, which is what decides whether the registries find them.
 */
class PluginDiscoveryTest
{
    private static final String FILTER_SPI = "com.ethlo.r7.spi.GatewayFilterFactory";
    private static final String PREDICATE_SPI = "com.ethlo.r7.spi.GatewayPredicateFactory";
    private static final String RESPONSE_PREDICATE_SPI = "com.ethlo.r7.spi.GatewayResponsePredicateFactory";

    @TempDir
    Path dir;

    public record NoParameters() implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
        }
    }

    public static final class DiscoveredFilter implements GatewayFilterFactory<NoParameters>
    {
        @Override
        public String name()
        {
            return "DiscoveredFilter";
        }

        @Override
        public Class<NoParameters> configClass()
        {
            return NoParameters.class;
        }

        @Override
        public GatewayFilter create(final NoParameters config, final FilterCreationContext filterCreationContext)
        {
            throw new UnsupportedOperationException();
        }
    }

    public static final class DiscoveredPredicate implements GatewayPredicateFactory<NoParameters>
    {
        @Override
        public String name()
        {
            return "DiscoveredPredicate";
        }

        @Override
        public Class<NoParameters> configClass()
        {
            return NoParameters.class;
        }

        @Override
        public GatewayPredicate create(final NoParameters config)
        {
            throw new UnsupportedOperationException();
        }
    }

    public static final class DiscoveredResponsePredicate implements GatewayResponsePredicateFactory<NoParameters>
    {
        @Override
        public String name()
        {
            return "DiscoveredResponsePredicate";
        }

        @Override
        public Class<NoParameters> configClass()
        {
            return NoParameters.class;
        }

        @Override
        public GatewayResponsePredicate create(final NoParameters config)
        {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void aFilterInAPluginJarIsFound() throws IOException
    {
        service(this.dir.resolve("auth").resolve("auth.jar"), FILTER_SPI, DiscoveredFilter.class);
        final ClassLoader loader = PluginClassLoader.create(this.dir, getClass().getClassLoader());

        assertThat(new FilterRegistry(loader).get("DiscoveredFilter")).isInstanceOf(DiscoveredFilter.class);
    }

    @Test
    void aFilterTheJarsDoNotHaveIsUnknown() throws IOException
    {
        service(this.dir.resolve("auth").resolve("auth.jar"), FILTER_SPI, DiscoveredFilter.class);
        final ClassLoader loader = PluginClassLoader.create(this.dir, getClass().getClassLoader());

        assertThatThrownBy(() -> new FilterRegistry(loader).get("Absent"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Unknown filter: 'Absent'");
    }

    @Test
    void requestAndResponsePredicatesInPluginJarsAreFound() throws IOException
    {
        service(this.dir.resolve("geo.jar"), PREDICATE_SPI, DiscoveredPredicate.class);
        service(this.dir.resolve("geo").resolve("geo-response.jar"), RESPONSE_PREDICATE_SPI, DiscoveredResponsePredicate.class);
        final ClassLoader loader = PluginClassLoader.create(this.dir, getClass().getClassLoader());

        final PredicateRegistry registry = new PredicateRegistry(new ObjectMapper(), loader);

        assertThat(registry.exists("DiscoveredPredicate")).isTrue();
        assertThat(registry.exists("DiscoveredResponsePredicate")).isTrue();
    }

    @Test
    void withoutPluginJarsNeitherIsFound()
    {
        final ClassLoader loader = PluginClassLoader.create(this.dir, getClass().getClassLoader());

        assertThat(new PredicateRegistry(new ObjectMapper(), loader).exists("DiscoveredPredicate")).isFalse();
        assertThatThrownBy(() -> new FilterRegistry(loader).get("DiscoveredFilter")).isInstanceOf(ConfigurationException.class);
    }

    private static void service(final Path file, final String spi, final Class<?> implementation) throws IOException
    {
        Files.createDirectories(file.getParent());
        try (final OutputStream out = Files.newOutputStream(file); final JarOutputStream jar = new JarOutputStream(out))
        {
            jar.putNextEntry(new JarEntry("META-INF/services/" + spi));
            jar.write((implementation.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }
}
