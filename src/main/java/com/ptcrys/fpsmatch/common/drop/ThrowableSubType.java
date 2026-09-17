package com.ptcrys.fpsmatch.common.drop;

import java.util.Objects;

public class ThrowableSubType {

    private final String id;
    private final int defaultLimit;
    private final String displayName;

    public ThrowableSubType(String id, int defaultLimit, String displayName) {
        this.id = id;
        this.defaultLimit = defaultLimit;
        this.displayName = displayName;
    }

    public ThrowableSubType(String id, int defaultLimit) {
        this(id, defaultLimit, id);
    }

    public String getId() {
        return id;
    }

    public int getDefaultLimit() {
        return defaultLimit;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ThrowableSubType that = (ThrowableSubType) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ThrowableSubType{" +
                "id='" + id + '\'' +
                ", displayName='" + displayName + '\'' +
                '}';
    }
}
