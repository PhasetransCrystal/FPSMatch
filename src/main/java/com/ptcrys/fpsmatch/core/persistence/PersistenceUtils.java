package com.ptcrys.fpsmatch.core.persistence;

import net.minecraftforge.fml.loading.FMLLoader;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PersistenceUtils {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String[] ILLEGAL_FILENAME_CHARS = { "\\", "/", ":", "*", "?", "\"", "<", ">", "|" };

    /** 修复文件名中的非法字符 */
    public static String fixFileName(String fileName) {
        String result = fileName;
        for (String charToReplace : ILLEGAL_FILENAME_CHARS) {
            result = result.replace(charToReplace, "");
        }
        return result;
    }

    /** 确保目录存在，不存在则创建 */
    public static void ensureDirectoryExists(Path path) {
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path);
                LOGGER.info("Created directory: {}", path);
            } else if (!Files.isDirectory(path)) {
                throw new DataPersistenceException(path + " is not a directory");
            }
        } catch (Exception e) {
            throw new DataPersistenceException("Failed to create directory: " + path, e);
        }
    }

    /** 获取本地缓存文件 */
    public static Path getLocalCachePath(String filename, String type) {
        Path cacheDir = Paths.get(FMLLoader.getGamePath().toString(), "fpsmatch", "cache", type);
        ensureDirectoryExists(cacheDir);
        String fixedFilename = fixFileName(filename);
        return cacheDir.resolve(fixedFilename + "." + type);
    }
}
