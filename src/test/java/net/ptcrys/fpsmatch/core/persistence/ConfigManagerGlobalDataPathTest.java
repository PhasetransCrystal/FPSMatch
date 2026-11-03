package net.ptcrys.fpsmatch.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigManagerGlobalDataPathTest {
    @TempDir Path directory;

    @Test
    void unavailableConfiguredPathFallsBackToInstanceDefault() throws Exception {
        Path configuredFile = Files.createFile(directory.resolve("copied-configured-path"));
        Path fallback = directory.resolve("fpsmatch/global");

        Path resolved = GlobalDataPathResolver.resolve(configuredFile.toString(), fallback);

        assertEquals(fallback.toAbsolutePath().normalize(), resolved);
        assertTrue(Files.isDirectory(resolved));
    }

    @Test
    void usableConfiguredPathIsPreserved() throws Exception {
        Path configured = directory.resolve("custom-global");

        Path resolved = GlobalDataPathResolver.resolve(configured.toString(), directory.resolve("default/global"));

        assertEquals(configured.toAbsolutePath().normalize(), resolved);
        assertTrue(Files.isDirectory(configured));
        assertFalse(Files.exists(directory.resolve("default/global")));
    }

    @Test
    void unavailableConfiguredAndFallbackPathsStillFailClearly() throws Exception {
        Path configuredFile = Files.createFile(directory.resolve("configured-file"));
        Path fallbackFile = Files.createFile(directory.resolve("fallback-file"));

        assertThrows(DataPersistenceException.class,
                () -> GlobalDataPathResolver.resolve(configuredFile.toString(), fallbackFile));
    }
}
