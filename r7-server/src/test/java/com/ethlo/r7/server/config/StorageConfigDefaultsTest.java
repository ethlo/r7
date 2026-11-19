package com.ethlo.r7.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

class StorageConfigDefaultsTest
{
    @Test
    void journalsGoUnderTheWorkingDirectoryByDefault()
    {
        assumeTrue(System.getenv(ServerConfig.StorageConfig.WORK_DIR_ENVIRONMENT_VARIABLE) == null,
                "R7_JOURNAL_DIR is set in this environment");

        assertThat(new ServerConfig.StorageConfig(null, null, null, null, null, null, null).workDir()).isEqualTo("journals");
        assertThat(ServerConfig.standard().storage().workDir()).isEqualTo("journals");
    }

    @Test
    void anExplicitWorkDirWins()
    {
        assertThat(new ServerConfig.StorageConfig("/data/r7", null, null, null, null, null, null).workDir()).isEqualTo("/data/r7");
    }
}
