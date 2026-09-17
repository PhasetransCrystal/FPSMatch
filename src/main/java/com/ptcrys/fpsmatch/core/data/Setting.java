package com.ptcrys.fpsmatch.core.data;

import net.minecraft.network.FriendlyByteBuf;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.ptcrys.fpsmatch.FPSMatch;

import java.util.function.Function;

/**
 * 用于存储和管理配置项的通用类。
 * <p>
 * 该类通过指定的 Codec（编解码器）来序列化和反序列化配置值，支持将配置值存储为 JSON 格式，并从 JSON 中恢复。
 * <p>
 * 主要功能：
 * <ul>
 * <li>存储配置项的名称、默认值和当前值。</li>
 * <li>提供配置值的获取和设置方法。</li>
 * <li>将配置值编码为 JSON 元素。</li>
 * <li>从 JSON 元素中解码配置值。</li>
 * <li>提供创建各种类型配置项的静态工厂方法。</li>
 * </ul>
 *
 * @param <T> 配置值的类型，必须与指定的 Codec 兼容。
 */
public class Setting<T> {

    public static final String DEFAULT_CATEGORY = "default";

    /**
     * 用于序列化和反序列化配置值的编解码器。
     */
    private final Codec<T> codec;

    private final Function<String, T> parser;

    /**
     * 配置项的名称，用于标识配置项。
     */
    private final String configName;

    /** Stable suffix used to group this setting in configuration UIs. */
    private final String category;

    /**
     * 当前配置值。
     */
    private T value;

    private final T defaultValue;

    /**
     * 构造一个新的配置项实例。
     *
     * @param configName   配置项的名称。
     * @param codec        用于处理配置值的编解码器。
     * @param defaultValue 配置项的默认值。
     */
    public Setting(String configName, Codec<T> codec, T defaultValue) {
        this(DEFAULT_CATEGORY, configName, codec, defaultValue);
    }

    public Setting(String category, String configName, Codec<T> codec, T defaultValue) {
        this.configName = configName;
        this.category = normalizeCategory(category);
        this.codec = codec;
        this.value = defaultValue;
        this.defaultValue = defaultValue;
        this.parser = null;
    }

    /**
     * 构造一个新的配置项实例。
     *
     * @param configName   配置项的名称。
     * @param codec        用于处理配置值的编解码器。
     * @param defaultValue 配置项的默认值。
     */
    public Setting(String configName, Codec<T> codec, T defaultValue, Function<String, T> parser) {
        this(DEFAULT_CATEGORY, configName, codec, defaultValue, parser);
    }

    public Setting(String category, String configName, Codec<T> codec, T defaultValue,
                   Function<String, T> parser) {
        this.configName = configName;
        this.category = normalizeCategory(category);
        this.codec = codec;
        this.value = defaultValue;
        this.defaultValue = defaultValue;
        this.parser = parser;
    }

    /**
     * 获取当前配置值。
     *
     * @return 当前配置值。
     */
    public T get() {
        return value;
    }

    /**
     * 设置新的配置值。
     *
     * @param value 新的配置值。
     * @return 设置后的配置值。
     */
    public T set(T value) {
        this.value = value;
        return value;
    }

    public T getDefaultValue() {
        return defaultValue;
    }

    /**
     * 获取配置项的编解码器。
     *
     * @return 配置项的编解码器。
     */
    public Codec<T> codec() {
        return codec;
    }

    /**
     * 获取配置项的名称。
     *
     * @return 配置项的名称。
     */
    public String getConfigName() {
        return configName;
    }

    public String getCategory() {
        return category;
    }

    public static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return DEFAULT_CATEGORY;
        }
        return category.trim();
    }

    /**
     * 将当前配置值编码为 JSON 元素。
     *
     * @return 配置值的 JSON 表示。
     * @throws RuntimeException 如果编码过程中发生错误。
     */
    public JsonElement toJson() {
        return this.codec().encodeStart(JsonOps.INSTANCE, this.value).getOrThrow(false, e -> {
            throw new RuntimeException(e);
        });
    }

    @Override
    public String toString() {
        return String.valueOf(this.value);
    }

    /**
     * 从 JSON 元素中解码配置值。
     *
     * @param json 配置值的 JSON 表示。
     * @throws RuntimeException 如果解码过程中发生错误。
     */
    public void fromJson(JsonElement json) {
        this.value = this.codec().decode(JsonOps.INSTANCE, json).getOrThrow(false, e -> {
            throw new RuntimeException(e);
        }).getFirst();
    }

    public boolean parse(String value) {
        if (value == null) return false;
        if (parser == null) return false;

        try {
            this.value = parser.apply(value);
            return true;
        } catch (Exception e) {
            FPSMatch.LOGGER.error(e.getMessage());
            return false;
        }
    }

    public void readFromBuf(FriendlyByteBuf buf) {
        value = buf.readJsonWithCodec(codec);
    }

    public void writeToBuf(FriendlyByteBuf buf) {
        buf.writeJsonWithCodec(codec, this.value);
    }

    /**
     * 创建一个整型配置项。
     *
     * @param configName   配置项名称。
     * @param defaultValue 默认值。
     * @return 配置项实例。
     */
    public static Setting<Integer> of(String configName, int defaultValue) {
        return of(DEFAULT_CATEGORY, configName, defaultValue);
    }

    public static Setting<Integer> of(String category, String configName, int defaultValue) {
        return new Setting<>(category, configName, Codec.INT, defaultValue, Integer::parseInt);
    }

    /**
     * 创建一个长整型配置项。
     *
     * @param configName   配置项名称。
     * @param defaultValue 默认值。
     * @return 配置项实例。
     */
    public static Setting<Long> of(String configName, long defaultValue) {
        return of(DEFAULT_CATEGORY, configName, defaultValue);
    }

    public static Setting<Long> of(String category, String configName, long defaultValue) {
        return new Setting<>(category, configName, Codec.LONG, defaultValue, Long::parseLong);
    }

    /**
     * 创建一个浮点型配置项。
     *
     * @param configName   配置项名称。
     * @param defaultValue 默认值。
     * @return 配置项实例。
     */
    public static Setting<Float> of(String configName, float defaultValue) {
        return of(DEFAULT_CATEGORY, configName, defaultValue);
    }

    public static Setting<Float> of(String category, String configName, float defaultValue) {
        return new Setting<>(category, configName, Codec.FLOAT, defaultValue, Float::parseFloat);
    }

    /**
     * 创建一个双精度浮点型配置项。
     *
     * @param configName   配置项名称。
     * @param defaultValue 默认值。
     * @return 配置项实例。
     */
    public static Setting<Double> of(String configName, double defaultValue) {
        return of(DEFAULT_CATEGORY, configName, defaultValue);
    }

    public static Setting<Double> of(String category, String configName, double defaultValue) {
        return new Setting<>(category, configName, Codec.DOUBLE, defaultValue, Double::parseDouble);
    }

    /**
     * 创建一个字节型配置项。
     *
     * @param configName   配置项名称。
     * @param defaultValue 默认值。
     * @return 配置项实例。
     */
    public static Setting<Byte> of(String configName, byte defaultValue) {
        return of(DEFAULT_CATEGORY, configName, defaultValue);
    }

    public static Setting<Byte> of(String category, String configName, byte defaultValue) {
        return new Setting<>(category, configName, Codec.BYTE, defaultValue, Byte::parseByte);
    }

    /**
     * 创建一个布尔型配置项。
     *
     * @param configName   配置项名称。
     * @param defaultValue 默认值。
     * @return 配置项实例。
     */
    public static Setting<Boolean> of(String configName, boolean defaultValue) {
        return of(DEFAULT_CATEGORY, configName, defaultValue);
    }

    public static Setting<Boolean> of(String category, String configName, boolean defaultValue) {
        return new Setting<>(category, configName, Codec.BOOL, defaultValue, value -> {
            if ("true".equalsIgnoreCase(value)) {
                return true;
            }
            if ("false".equalsIgnoreCase(value)) {
                return false;
            }
            throw new IllegalArgumentException("Invalid boolean: " + value);
        });
    }

    /**
     * 创建一个字符串配置项。
     *
     * @param configName   配置项名称。
     * @param defaultValue 默认值。
     * @return 配置项实例。
     */
    public static Setting<String> of(String configName, String defaultValue) {
        return of(DEFAULT_CATEGORY, configName, defaultValue);
    }

    public static Setting<String> of(String category, String configName, String defaultValue) {
        return new Setting<>(category, configName, Codec.STRING, defaultValue, str -> str);
    }

    public void reset() {
        this.value = getDefaultValue();
    }
}
