package com.ptcrys.fpsmatch.common.packet.mapselect;

import net.minecraft.network.FriendlyByteBuf;

import com.ptcrys.fpsmatch.core.data.Setting;

public record MapRoomSettingInfo(String name, String value, String defaultValue, boolean editable,
                                 String translationKey, SettingType type, String descriptionKey,
                                 boolean slider, double minValue, double maxValue, double step,
                                 String category) {

    private static final int NAME_MAX_LENGTH = 128;
    private static final int VALUE_MAX_LENGTH = 1024;
    public static final String CATEGORY_TRANSLATION_PREFIX = "setting.fpsm.category.";

    public enum SettingType {
        BOOLEAN,
        INTEGER,
        DECIMAL,
        STRING,
        OTHER
    }

    public MapRoomSettingInfo {
        category = Setting.normalizeCategory(category);
    }

    /** Backward-compatible constructor for presentation metadata without a category. */
    public MapRoomSettingInfo(String name, String value, String defaultValue, boolean editable,
                              String translationKey, SettingType type, String descriptionKey,
                              boolean slider, double minValue, double maxValue, double step) {
        this(name, value, defaultValue, editable, translationKey, type, descriptionKey,
                slider, minValue, maxValue, step, Setting.DEFAULT_CATEGORY);
    }

    /** Backward-compatible constructor for callers that only have the legacy fields. */
    public MapRoomSettingInfo(String name, String value, String defaultValue, boolean editable,
                              String translationKey, SettingType type) {
        this(name, value, defaultValue, editable, translationKey, type,
                translationKey + ".desc", false, 0.0, 0.0, 1.0, Setting.DEFAULT_CATEGORY);
    }

    public String categoryTranslationKey() {
        return categoryTranslationKey(category);
    }

    public static String categoryTranslationKey(String category) {
        return CATEGORY_TRANSLATION_PREFIX + Setting.normalizeCategory(category);
    }

    public static void encode(MapRoomSettingInfo info, FriendlyByteBuf buf) {
        buf.writeUtf(info.name(), NAME_MAX_LENGTH);
        buf.writeUtf(info.value(), VALUE_MAX_LENGTH);
        buf.writeUtf(info.defaultValue(), VALUE_MAX_LENGTH);
        buf.writeBoolean(info.editable());
        buf.writeUtf(info.translationKey(), VALUE_MAX_LENGTH);
        buf.writeEnum(info.type());
        buf.writeUtf(info.descriptionKey(), VALUE_MAX_LENGTH);
        buf.writeBoolean(info.slider());
        buf.writeDouble(info.minValue());
        buf.writeDouble(info.maxValue());
        buf.writeDouble(info.step());
        buf.writeUtf(info.category(), NAME_MAX_LENGTH);
    }

    public static MapRoomSettingInfo decode(FriendlyByteBuf buf) {
        return new MapRoomSettingInfo(buf.readUtf(NAME_MAX_LENGTH), buf.readUtf(VALUE_MAX_LENGTH),
                buf.readUtf(VALUE_MAX_LENGTH), buf.readBoolean(), buf.readUtf(VALUE_MAX_LENGTH),
                buf.readEnum(SettingType.class), buf.readUtf(VALUE_MAX_LENGTH), buf.readBoolean(),
                buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readUtf(NAME_MAX_LENGTH));
    }
}
