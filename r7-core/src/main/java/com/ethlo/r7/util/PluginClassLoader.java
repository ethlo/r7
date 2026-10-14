package com.ethlo.r7.util;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.ethlo.r7.config.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The class loader that finds extension jars: every {@code .jar} directly in the plugins directory
 * and in each directory one level below it. The one level below is what lets each extension arrive
 * from a mount of its own (a container image volume per plugin) without the jars sharing a path.
 * A classpath wildcard cannot express that, since it only matches the jars of a single directory.
 * <p>
 * The directory is {@code plugins} in the working directory, like the config files, and
 * {@code R7_PLUGINS_DIR} names another. A missing directory means no extensions.
 */
public final class PluginClassLoader
{
    public static final String DIR_ENV = "R7_PLUGINS_DIR";
    private static final Logger log = LoggerFactory.getLogger(PluginClassLoader.class);

    private PluginClassLoader()
    {
    }

    /**
     * The loader the factories are looked up in, made once. It sits on top of the loader the
     * gateway itself runs in, so a factory sees the SPI types and the built-in factories are found
     * through it as before.
     */
    public static ClassLoader get()
    {
        return Holder.LOADER;
    }

    static ClassLoader create(final Path dir, final ClassLoader parent)
    {
        final List<Path> jars = findJars(dir);
        if (jars.isEmpty())
        {
            return parent;
        }

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
        return new URLClassLoader("r7-plugins", urls.toArray(URL[]::new), parent);
    }

    private static List<Path> findJars(final Path dir)
    {
        // Only a path proven absent means no extensions. isDirectory is false for a path it could not
        // inspect as well, and a plugins directory the gateway is not allowed into must stop the start.
        if (Files.notExists(dir))
        {
            return List.of();
        }

        // A path that exists but is not a readable directory must stop the start: the gateway running
        // without the extensions its routes name would fail every request that uses one, and say little why.
        final List<Path> jars = new ArrayList<>();
        try (final DirectoryStream<Path> entries = Files.newDirectoryStream(dir))
        {
            for (final Path entry : entries)
            {
                if (Files.isDirectory(entry))
                {
                    addJars(entry, jars);
                }
                else if (isJar(entry))
                {
                    jars.add(entry);
                }
            }
        }
        catch (IOException e)
        {
            throw new ConfigurationException("Cannot read the extension directory " + dir + ": " + e.getMessage());
        }

        // Directory order is arbitrary; a fixed order makes which of two clashing classes wins repeatable
        jars.sort(Path::compareTo);
        return jars;
    }

    private static void addJars(final Path dir, final List<Path> jars) throws IOException
    {
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
    }

    private static boolean isJar(final Path path)
    {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar");
    }

    private static final class Holder
    {
        private static final ClassLoader LOADER = create(
                Path.of(System.getenv().getOrDefault(DIR_ENV, "plugins")).toAbsolutePath(),
                PluginClassLoader.class.getClassLoader());
    }
}
