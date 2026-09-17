package com.ptcrys.fpsmatch.util;

/** Stable, portable identifier used as a map's persisted name. */
public final class MapId {

    public static final int MAX_LENGTH = 48;

    private MapId() {}

    public static boolean isValid(String value) {
        return value != null && !value.isEmpty() && isValidPrefix(value);
    }

    public static boolean isValidPrefix(String value) {
        if (value == null || value.length() > MAX_LENGTH) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9') || character == '_' || character == '-')) {
                return false;
            }
        }
        return true;
    }
}
