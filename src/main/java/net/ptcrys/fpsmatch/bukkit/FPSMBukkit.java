package net.ptcrys.fpsmatch.bukkit;

import net.ptcrys.fpsmatch.bukkit.event.FPSMBukkitEventBirge;

import net.minecraft.server.level.ServerLevel;

public class FPSMBukkit {

    public static void register() {
        FPSMBukkitEventBirge.register();
    }

    public static boolean isBukkitEnvironment() {
        try {
            Class.forName("org.bukkit.Bukkit");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static String getLevelName(ServerLevel level) {
        String original = level.toString();
        if (original.startsWith("ServerLevel[") && original.endsWith("]")) {
            return original.substring("ServerLevel[".length(), original.length() - 1);
        }
        return "UnknownLevel";
    }
}
