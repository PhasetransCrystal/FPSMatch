package net.ptcrys.fpsmatch.core.persistence;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.nio.file.Paths;

final class GlobalDataPathResolver {
    private static final Logger LOGGER = LogUtils.getLogger();

    private GlobalDataPathResolver() {}

    static Path resolve(String configuredPath, Path defaultPath) {
        Path fallback = defaultPath.toAbsolutePath().normalize();
        Path configured = safeAbsolutePath(configuredPath);
        try {
            Path selected = configured == null ? fallback : configured;
            PersistenceUtils.ensureDirectoryExists(selected);
            return selected;
        } catch (RuntimeException failure) {
            if (fallback.equals(configured)) {
                throw failure;
            }
            LOGGER.warn("Configured global data path is unavailable; using {} instead", fallback, failure);
        }

        PersistenceUtils.ensureDirectoryExists(fallback);
        return fallback;
    }

    static Path safeAbsolutePath(String path) {
        if (path == null || path.isBlank()) return null;
        try {
            return Paths.get(path).toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
