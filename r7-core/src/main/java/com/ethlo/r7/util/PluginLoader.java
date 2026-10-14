package com.ethlo.r7.util;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;

import com.ethlo.r7.config.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the extensions: the jars in the plugins directory, each plugin in a class loader of its own.
 * A plugin is a directory one level below the plugins directory, with every jar in it (the
 * extension and the libraries it brings), so a plugin can arrive from a mount of its own, such as
 * a container image volume per plugin, which a classpath wildcard cannot express. Jars directly
 * in the plugins directory share one class loader.
 * <p>
 * A loader of its own means that two plugins can carry different versions of the same library and
 * cannot see each other's classes. It is parent-first: the r7 API has to be the same classes in
 * the gateway and in a plugin, so the gateway's own classes and libraries always win.
 * <p>
 * The directory is {@code plugins} in the working directory, like the config files, and
 * {@code R7_PLUGINS_DIR} names another. A missing directory means no extensions.
 */
public final class PluginLoader
{
    public static final String DIR_ENV = "R7_PLUGINS_DIR";
    private static final Logger log = LoggerFactory.getLogger(PluginLoader.class);

    private PluginLoader()
    {
    }

    /**
     * Every class loader to look for extensions in, made once: the gateway's own, which holds the
     * built-in factories, then one for each plugin.
     */
    public static List<ClassLoader> loaders()
    {
        return Holder.LOADERS;
    }

    /**
     * The providers of {@code spi} in the gateway and in every plugin. A plugin's loader sees the
     * gateway's providers through its parent, so a provider found twice is returned once.
     */
    public static <S> List<S> load(final Class<S> spi, final List<ClassLoader> loaders)
    {
        final List<S> providers = new ArrayList<>();
        final Set<Class<?>> seen = new HashSet<>();
        for (final ClassLoader loader : loaders)
        {
            ServiceLoader.load(spi, loader).stream()
                    .filter(provider -> seen.add(provider.type()))
                    .forEach(provider -> providers.add(provider.get()));
        }
        return providers;
    }

    static List<ClassLoader> create(final Path dir, final ClassLoader parent)
    {
        final List<ClassLoader> loaders = new ArrayList<>();
        loaders.add(parent);
        for (final var plugin : findPlugins(dir).entrySet())
        {
            loaders.add(loader(plugin.getKey(), plugin.getValue(), parent));
        }
        return List.copyOf(loaders);
    }

    private static ClassLoader loader(final String name, final List<Path> jars, final ClassLoader parent)
    {
        final List<URL> urls = new ArrayList<>(jars.size());
        for (final Path jar : jars)
        {
            try
            {
                urls.add(jar.toUri().toURL());
            }
            catch (MalformedURLException e)
            {
                throw new ConfigurationException("Cannot load extension jar " + jar + ": " + e.getMessage());
            }
            log.info("Loading extension jar {}", jar);
        }
        return new URLClassLoader(".".equals(name) ? "r7-plugins" : "r7-plugin-" + name, urls.toArray(URL[]::new), parent);
    }

    /**
     * The jars of each plugin, by plugin name: the directories one level down by their own names,
     * and the jars directly in the plugins directory together under {@code "."}. In name order, since directory order is arbitrary and the order decides which of two clashing
     * providers is registered first.
     */
    private static TreeMap<String, List<Path>> findPlugins(final Path dir)
    {
        final TreeMap<String, List<Path>> plugins = new TreeMap<>();

        // Only a path proven absent means no extensions. isDirectory is false for a path it could not
        // inspect as well, and a plugins directory the gateway is not allowed into must stop the start.
        if (Files.notExists(dir))
        {
            return plugins;
        }

        // A path that exists but is not a readable directory must stop the start: the gateway running
        // without the extensions its routes name would fail every request that uses one, and say little why.
        try (final DirectoryStream<Path> entries = Files.newDirectoryStream(dir))
        {
            final List<Path> flat = new ArrayList<>();
            for (final Path entry : entries)
            {
                if (Files.isDirectory(entry))
                {
                    final List<Path> jars = jarsIn(entry);
                    if (!jars.isEmpty())
                    {
                        plugins.put(entry.getFileName().toString(), jars);
                    }
                }
                else if (isJar(entry))
                {
                    flat.add(entry);
                }
            }
            if (!flat.isEmpty())
            {
                flat.sort(Path::compareTo);
                // A plugin directory cannot be named "." so this never takes a directory's place
                plugins.put(".", flat);
            }
        }
        catch (IOException e)
        {
            throw new ConfigurationException("Cannot read the extension directory " + dir + ": " + e.getMessage());
        }
        return plugins;
    }

    private static List<Path> jarsIn(final Path dir) throws IOException
    {
        final List<Path> jars = new ArrayList<>();
        try (final DirectoryStream<Path> entries = Files.newDirectoryStream(dir))
        {
            for (final Path entry : entries)
            {
                if (isJar(entry))
                {
                    jars.add(entry);
                }
            }
        }
        jars.sort(Path::compareTo);
        return jars;
    }

    private static boolean isJar(final Path path)
    {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar");
    }

    private static final class Holder
    {
        private static final List<ClassLoader> LOADERS = create(
                Path.of(System.getenv().getOrDefault(DIR_ENV, "plugins")).toAbsolutePath(),
                PluginLoader.class.getClassLoader());
    }
}
