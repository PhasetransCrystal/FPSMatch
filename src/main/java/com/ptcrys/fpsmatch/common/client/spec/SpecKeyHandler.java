package com.ptcrys.fpsmatch.common.client.spec;

import net.minecraft.client.KeyMapping;

import java.util.ArrayList;
import java.util.List;

public class SpecKeyHandler {

    public static final List<KeyMapping> switchKeys = new ArrayList<>();

    public static void registerSwitchKey(KeyMapping key) {
        switchKeys.add(key);
    }

    public static boolean switchKeyMatches(int keyCode, int scanCode) {
        for (KeyMapping key : switchKeys) {
            if (key.matches(keyCode, scanCode)) {
                return true;
            }
        }
        return false;
    }
}
