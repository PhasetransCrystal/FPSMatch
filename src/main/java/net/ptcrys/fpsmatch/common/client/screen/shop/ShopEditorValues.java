package net.ptcrys.fpsmatch.common.client.screen.shop;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;

/** Shared limits for editor drafts and untrusted network requests. */
public final class ShopEditorValues {

    public static final int MAX_SELECTION = 512;
    public static final int MAX_MODULES = 64;
    public static final int MAX_MODULE_NAME = 256;
    public static final int MAX_CATALOG = 1024;

    private ShopEditorValues() {}

    public static boolean validGroup(int group) {
        return group >= -1 && group <= 999_999;
    }

    public static boolean validSelection(int[] indices, int size) {
        if (indices == null || indices.length == 0 || indices.length > MAX_SELECTION) return false;
        var unique = new HashSet<Integer>();
        for (int index : indices) if (index < 0 || index >= size || !unique.add(index)) return false;
        return true;
    }

    public static boolean validModules(List<String> names, Collection<String> registered) {
        if (names == null || names.size() > MAX_MODULES) return false;
        var unique = new HashSet<String>();
        for (String name : names) if (name == null || name.isBlank() || name.length() > MAX_MODULE_NAME || !registered.contains(name) || !unique.add(name)) return false;
        return true;
    }
}
