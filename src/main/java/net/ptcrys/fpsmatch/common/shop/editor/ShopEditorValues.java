package net.ptcrys.fpsmatch.common.shop.editor;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;

public final class ShopEditorValues {

    public static final int MAX_SELECTION = ShopEditorSnapshot.MAX_SLOTS;
    public static final int MAX_MODULES = ShopEditorSnapshot.MAX_MODULES;
    public static final int MAX_MODULE_NAME = 256;
    public static final int MAX_CATALOG = ShopEditorSnapshot.MAX_CATALOG;

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
