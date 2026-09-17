package com.ptcrys.fpsmatch.compat.gun;

/**
 * 枪械分类枚举，替代 TACZ 的 GunTabType。
 * 同时作为枪械音效注册的 key 类型。
 */
public enum GunTabTypeEnum {

    PISTOL,
    RIFLE,
    SHOTGUN,
    SMG,
    SNIPER,
    MG,
    RPG;

    public static GunTabTypeEnum fromString(String type) {
        try {
            return valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return RIFLE;
        }
    }
}
