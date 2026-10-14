package com.ethlo.r7.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import com.ethlo.r7.config.ConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginClassLoaderTest
{
    private static final ClassLoader PARENT = PluginClassLoaderTest.class.getClassLoader();

    @TempDir
    Path dir;

    @Test
    void jarsDirectlyInTheDirectoryAndOneLevelDownAreFound() throws IOException
    {
        jar(this.dir.resolve("flat.jar"), "flat.txt");
        jar(this.dir.resolve("auth").resolve("auth.jar"), "auth.txt");
        jar(this.dir.resolve("geo").resolve("geo.jar"), "geo.txt");

        final ClassLoader loader = PluginClassLoader.create(this.dir, PARENT);

        assertThat(loader.getResource("flat.txt")).isNotNull();
        assertThat(loader.getResource("auth.txt")).isNotNull();
        assertThat(loader.getResource("geo.txt")).isNotNull();
    }

    @Test
    void jarsTwoLevelsDownAndOtherFilesAreIgnored() throws IOException
    {
        jar(this.dir.resolve("a").resolve("b").resolve("deep.jar"), "deep.txt");
        Files.writeString(this.dir.resolve("notes.txt"), "not a jar");
        Files.createDirectories(this.dir.resolve("a").resolve("dir.jar"));

        final ClassLoader loader = PluginClassLoader.create(this.dir, PARENT);

        assertThat(loader).isSameAs(PARENT);
    }

    @Test
    void aMissingDirectoryMeansNoExtensions()
    {
        assertThat(PluginClassLoader.create(this.dir.resolve("absent"), PARENT)).isSameAs(PARENT);
    }

    @Test
    void aPathThatExistsButIsNotADirectoryStopsTheStart() throws IOException
    {
        final Path file = Files.writeString(this.dir.resolve("plugins"), "not a directory");

        assertThatThrownBy(() -> PluginClassLoader.create(file, PARENT))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Cannot read the extension directory");
    }

    @Test
    void anEmptyDirectoryMeansNoExtensions()
    {
        assertThat(PluginClassLoader.create(this.dir, PARENT)).isSameAs(PARENT);
    }

    @Test
    void theParentStillServesWhatIsNotInTheJars() throws IOException
    {
        jar(this.dir.resolve("p").resolve("p.jar"), "p.txt");

        final ClassLoader loader = PluginClassLoader.create(this.dir, PARENT);
        final URL own = loader.getResource(PluginClassLoaderTest.class.getName().replace('.', '/') + ".class");

        assertThat(own).isNotNull();
        assertThat(Collections.list(loader.getResources("p.txt"))).hasSize(1);
    }

    private static void jar(final Path file, final String entry) throws IOException
    {
        Files.createDirectories(file.getParent());
        try (final OutputStream out = Files.newOutputStream(file); final JarOutputStream jar = new JarOutputStream(out))
        {
            jar.putNextEntry(new JarEntry(entry));
            jar.write(1);
            jar.closeEntry();
        }
    }
}
