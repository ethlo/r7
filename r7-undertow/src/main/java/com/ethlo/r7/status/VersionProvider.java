package com.ethlo.r7.status;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class VersionProvider
{

    private VersionProvider()
    {
        // Prevent instantiation
    }

    public static String getVersion()
    {
        final Properties properties = new Properties();

        // Use "/git.properties" if you used Method 2
        try (final InputStream inputStream = VersionProvider.class.getResourceAsStream("/git.properties"))
        {
            if (inputStream != null)
            {
                properties.load(inputStream);
                final String versionName = properties.getProperty("git.build.version");
                final String gitHash = properties.getProperty("git.commit.id.abbrev", "<rev>");
                return versionName + " (" + gitHash + ")";
            }
            else
            {
                return "unknown";
            }
        }
        catch (final IOException e)
        {
            throw new RuntimeException("Failed to read version properties", e);
        }
    }
}