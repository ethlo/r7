package com.ethlo.r7.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import com.ethlo.r7.config.ConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginLoaderTest
{
    private static final ClassLoader PARENT = PluginLoaderTest.class.getClassLoader();

    @TempDir
    Path dir;

    @Test
    void eachPluginDirectoryGetsALoaderOfItsOwn() throws IOException
    {
        jar(this.dir.resolve("auth").resolve("auth.jar"), "auth.txt");
        jar(this.dir.resolve("auth").resolve("auth-lib.jar"), "auth-lib.txt");
        jar(this.dir.resolve("geo").resolve("geo.jar"), "geo.txt");

        final List<ClassLoader> loaders = PluginLoader.create(this.dir, PARENT);

        assertThat(loaders).hasSize(3).first().isSameAs(PARENT);
        final ClassLoader auth = loaders.get(1);
        final ClassLoader geo = loaders.get(2);
        assertThat(auth.getResource("auth.txt")).isNotNull();
        assertThat(auth.getResource("auth-lib.txt")).as("a plugin's libraries sit in its loader").isNotNull();
        assertThat(auth.getResource("geo.txt")).as("one plugin cannot see another").isNull();
        assertThat(geo.getResource("geo.txt")).isNotNull();
        assertThat(geo.getResource("auth.txt")).isNull();
    }

    @Test
    void jarsDirectlyInTheDirectoryShareOneLoader() throws IOException
    {
        jar(this.dir.resolve("a.jar"), "a.txt");
        jar(this.dir.resolve("b.jar"), "b.txt");
        jar(this.dir.resolve("geo").resolve("geo.jar"), "geo.txt");

        final List<ClassLoader> loaders = PluginLoader.create(this.dir, PARENT);

        assertThat(loaders).hasSize(3);
        final ClassLoader flat = loaders.get(1);
        assertThat(flat.getResource("a.txt")).isNotNull();
        assertThat(flat.getResource("b.txt")).isNotNull();
        assertThat(flat.getResource("geo.txt")).isNull();
    }

    @Test
    void twoPluginsMayCarryDifferentVersionsOfTheSameLibrary() throws IOException
    {
        jar(this.dir.resolve("one").resolve("lib-1.jar"), "lib.txt", 1);
        jar(this.dir.resolve("two").resolve("lib-2.jar"), "lib.txt", 2);

        final List<ClassLoader> loaders = PluginLoader.create(this.dir, PARENT);

        assertThat(read(loaders.get(1), "lib.txt")).isEqualTo(1);
        assertThat(read(loaders.get(2), "lib.txt")).isEqualTo(2);
    }

    @Test
    void jarsTwoLevelsDownAndOtherFilesAreIgnored() throws IOException
    {
        jar(this.dir.resolve("a").resolve("b").resolve("deep.jar"), "deep.txt");
        Files.writeString(this.dir.resolve("notes.txt"), "not a jar");
        Files.createDirectories(this.dir.resolve("a").resolve("dir.jar"));

        assertThat(PluginLoader.create(this.dir, PARENT)).containsExactly(PARENT);
    }

    @Test
    void aMissingDirectoryMeansNoExtensions()
    {
        assertThat(PluginLoader.create(this.dir.resolve("absent"), PARENT)).containsExactly(PARENT);
    }

    @Test
    void aPathThatExistsButIsNotADirectoryStopsTheStart() throws IOException
    {
        final Path file = Files.writeString(this.dir.resolve("plugins"), "not a directory");

        assertThatThrownBy(() -> PluginLoader.create(file, PARENT))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Cannot read the extension directory");
    }

    @Test
    void anEmptyDirectoryMeansNoExtensions()
    {
        assertThat(PluginLoader.create(this.dir, PARENT)).containsExactly(PARENT);
    }

    @Test
    void theParentStillServesWhatIsNotInTheJars() throws IOException
    {
        jar(this.dir.resolve("p").resolve("p.jar"), "p.txt");

        final ClassLoader loader = PluginLoader.create(this.dir, PARENT).get(1);

        assertThat(loader.getResource(PluginLoaderTest.class.getName().replace('.', '/') + ".class")).isNotNull();
    }

    private static void jar(final Path file, final String entry) throws IOException
    {
        jar(file, entry, 1);
    }

    private static void jar(final Path file, final String entry, final int content) throws IOException
    {
        Files.createDirectories(file.getParent());
        try (final OutputStream out = Files.newOutputStream(file); final JarOutputStream jar = new JarOutputStream(out))
        {
            jar.putNextEntry(new JarEntry(entry));
            jar.write(content);
            jar.closeEntry();
        }
    }

    private static int read(final ClassLoader loader, final String name) throws IOException
    {
        try (final var in = loader.getResourceAsStream(name))
        {
            return in.read();
        }
    }
}
