package com.ptcrys.fpsmatch.compat.impl;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.forgespi.language.IModFileInfo;

import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;

public class FPSMImpl {

    public static final String LRTACTICAL = "lrtactical";
    public static final String COUNTER_STRIKE_GRENADES = "csgrenades";
    public static final String MOHIST = "mohist";
    public static final String CLOTH_CONFIG = "cloth_config";
    public static final String TACZ = "tacz";
    public static final String TACZ_TWEAKS = "tacztweaks";
    public static final String PHYSICS_MOD = "physicsmod";
    public static final String KUBEJS = "kubejs";

    public static boolean findLrtacticalMod() {
        return isLoaded(LRTACTICAL);
    }

    public static boolean findCounterStrikeGrenadesMod() {
        return isLoaded(COUNTER_STRIKE_GRENADES);
    }

    public static boolean findMohist() {
        return isLoaded(MOHIST);
    }

    public static boolean findClothConfig() {
        return isLoaded(CLOTH_CONFIG);
    }

    public static boolean findTacz() {
        return isLoaded(TACZ);
    }

    public static boolean findTaczTweaks() {
        return isLoaded(TACZ_TWEAKS);
    }

    public static boolean findPhysicsMod() {
        return isLoaded(PHYSICS_MOD);
    }

    public static boolean findKubeJS() {
        return isLoaded(KUBEJS);
    }

    public static boolean withVersion(String modId, String version) {
        IModFileInfo info = getModFileInfo(modId);
        try {
            return info != null && VersionRange.createFromVersionSpec(version).containsVersion(info.getMods().get(0).getVersion());
        } catch (InvalidVersionSpecificationException exception) {
            return false;
        }
    }

    private static boolean isLoaded(String modId) {
        return getModFileInfo(modId) != null;
    }

    private static IModFileInfo getModFileInfo(String modId) {
        ModList modList = ModList.get();
        if (modList != null) {
            return modList.getModFileById(modId);
        }

        LoadingModList loadingModList = LoadingModList.get();
        return loadingModList == null ? null : loadingModList.getModFileById(modId);
    }
}
