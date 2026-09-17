package com.ptcrys.fpsmatch.core.persistence.datafixer;

import com.google.gson.JsonElement;
import com.ptcrys.fpsmatch.core.persistence.DataPersistenceException;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 全局数据修复工具，管理版本转换逻辑
 */
public class DataFixer {

    private static final DataFixer INSTANCE = new DataFixer();

    private final Map<Class<?>, Map<Integer, JsonFixer>> jsonFixers = new HashMap<>();

    private DataFixer() {}

    public static DataFixer getInstance() {
        return INSTANCE;
    }

    /**
     * 注册Json转换逻辑（从fromVersion到fromVersion+1）
     * 
     * @param dataClass   数据类型
     * @param fromVersion 旧版本号
     * @param fixer       转换逻辑
     */
    public void registerJsonFixer(Class<?> dataClass, int fromVersion, JsonFixer fixer) {
        jsonFixers.computeIfAbsent(dataClass, k -> new HashMap<>()).put(fromVersion, fixer);
    }

    /**
     * 执行数据修复（从旧版本到目标版本）
     * 
     * @param dataClass     数据类型
     * @param oldJson       旧版本Json数据
     * @param oldVersion    旧版本号
     * @param targetVersion 目标版本号
     * @return 修复后的新版本Json数据
     */
    public JsonElement fixJson(Class<?> dataClass, JsonElement oldJson, int oldVersion, int targetVersion) {
        Objects.requireNonNull(oldJson, "Old data cannot be null");
        if (oldVersion == targetVersion) return oldJson;
        if (oldVersion > targetVersion) {
            throw new DataPersistenceException("Cannot downgrade data version from " + oldVersion + " to " + targetVersion);
        }

        JsonElement currentJson = oldJson.getAsJsonObject();
        int currentVersion = oldVersion;
        Map<Integer, JsonFixer> fixers = jsonFixers.getOrDefault(dataClass, new HashMap<>());

        while (currentVersion < targetVersion) {
            JsonFixer fixer = fixers.get(currentVersion);
            if (fixer == null) {
                throw new DataPersistenceException("No fixer found for " + dataClass.getSimpleName() + " (version " + currentVersion + " → " + (currentVersion + 1) + ")");
            }
            currentJson = fixer.apply(currentJson);
            currentVersion++;
        }

        return currentJson;
    }

    /**
     * Json转换逻辑接口
     */
    @FunctionalInterface
    public interface JsonFixer {

        /**
         * 修复Json数据
         * 
         * @param oldJson 旧版本Json数据
         * @return 修复后的新版本Json数据
         */
        JsonElement apply(JsonElement oldJson);
    }
}
