package com.ptcrys.fpsmatch.core.persistence;

import net.minecraftforge.fml.loading.FMLLoader;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.ptcrys.fpsmatch.FPSMatch;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class ConfigManager {

    private static final Path CONFIG_DIR = Paths.get(FMLLoader.getGamePath().toString(), "fpsmatch");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");
    private static final Gson GSON = new Gson();
    private static Map<String, String> config = new HashMap<>();

    static {
        try {
            loadConfig();
        } catch (Exception e) {
            FPSMatch.LOGGER.error("Failed to load config, using default: {}", e.getMessage());
            config.put("globalDataPath", CONFIG_DIR.resolve("global").toString());
            try {
                saveConfig();
            } catch (Exception ex) {
                FPSMatch.LOGGER.error("Failed to save config, using default: {}", ex.getMessage());
            }
        }
    }

    private static void loadConfig() {
        try {
            PersistenceUtils.ensureDirectoryExists(CONFIG_DIR);

            if (Files.exists(CONFIG_FILE)) {
                String content = readFileWithMultipleEncodings();
                Map<String, String> loadedConfig = GSON.fromJson(content, new TypeToken<Map<String, String>>() {}.getType());
                config = Objects.requireNonNullElseGet(loadedConfig, HashMap::new);
            } else {
                config.put("globalDataPath", CONFIG_DIR.resolve("global").toString());
                saveConfig();
            }
        } catch (Exception e) {
            throw new DataPersistenceException("Failed to load config", e);
        }
    }

    /**
     * 尝试多种编码方式读取文件
     */
    private static String readFileWithMultipleEncodings() throws Exception {
        Charset[] charsets = {
                StandardCharsets.UTF_8,
                StandardCharsets.ISO_8859_1,
                Charset.forName("GBK"),
                Charset.forName("GB2312"),
                StandardCharsets.UTF_16
        };

        byte[] bytes = Files.readAllBytes(ConfigManager.CONFIG_FILE);

        for (Charset charset : charsets) {
            try {
                String content = new String(bytes, charset);
                if (isValidJson(content)) {
                    return content;
                }
            } catch (Exception ignored) {}
        }

        return null;
    }

    /**
     * 简单检查是否为有效的JSON
     */
    private static boolean isValidJson(String content) {
        if (content == null || content.trim().isEmpty()) {
            return false;
        }
        String trimmed = content.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }

    private static void saveConfig() {
        try {
            PersistenceUtils.ensureDirectoryExists(CONFIG_DIR);
            String content = GSON.toJson(config);
            Files.writeString(CONFIG_FILE, content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new DataPersistenceException("Failed to save config", e);
        }
    }

    public static Path getGlobalDataPath() {
        String pathStr = config.get("globalDataPath");
        if (pathStr == null) {
            // 如果配置缺失，创建默认路径
            pathStr = CONFIG_DIR.resolve("global").toString();
            config.put("globalDataPath", pathStr);
            try {
                saveConfig();
            } catch (Exception e) {
                throw new DataPersistenceException("Failed to save default globalDataPath", e);
            }
        }
        return Paths.get(pathStr);
    }

    /**
     * 重新加载配置
     */
    public static void reloadConfig() {
        try {
            loadConfig();
        } catch (Exception e) {
            throw new DataPersistenceException("Failed to reload config", e);
        }
    }

    /**
     * 获取配置映射（只读）
     */
    public static Map<String, String> getConfig() {
        return new HashMap<>(config);
    }

    /**
     * 更新配置
     */
    public static void updateConfig(Map<String, String> newConfig) {
        config.putAll(newConfig);
        saveConfig();
    }
}
