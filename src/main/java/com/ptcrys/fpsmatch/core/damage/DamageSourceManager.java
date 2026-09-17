package com.ptcrys.fpsmatch.core.damage;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class DamageSourceManager {

    private static final Map<String, DamageSourceCategory> DEFAULT_RULES = new HashMap<>();
    private static final Map<String, DamageSourceCategory> CUSTOM_RULES = new HashMap<>();

    private DamageSourceManager() {}

    public static void registerDefaultId(String sourceId, DamageSourceCategory category) {
        DEFAULT_RULES.put(normalize(sourceId), Objects.requireNonNull(category));
    }

    public static void registerId(String sourceId, DamageSourceCategory category) {
        CUSTOM_RULES.put(normalize(sourceId), Objects.requireNonNull(category));
    }

    public static DamageSourceCategory classify(String sourceId) {
        String normalizedId = normalize(sourceId);
        DamageSourceCategory customCategory = CUSTOM_RULES.get(normalizedId);
        if (customCategory != null) {
            return customCategory;
        }
        return DEFAULT_RULES.getOrDefault(normalizedId, DamageSourceCategory.FALLBACK);
    }

    public static void clearCustomRules() {
        CUSTOM_RULES.clear();
    }

    static void clearDefaultRulesForTest() {
        DEFAULT_RULES.clear();
    }

    private static String normalize(String sourceId) {
        return Objects.requireNonNull(sourceId).toLowerCase(Locale.ROOT);
    }
}
