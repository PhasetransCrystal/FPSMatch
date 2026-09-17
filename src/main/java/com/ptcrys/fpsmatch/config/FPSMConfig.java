package com.ptcrys.fpsmatch.config;

import net.minecraftforge.common.ForgeConfigSpec;

import org.apache.commons.lang3.tuple.Pair;

public class FPSMConfig {

    public static class Server {

        public static ForgeConfigSpec.BooleanValue lock3PersonCamera;
        public static ForgeConfigSpec.BooleanValue lockSpecKeyHandle;
        public static ForgeConfigSpec.BooleanValue disableDefaultGlow;
        public static ForgeConfigSpec.BooleanValue disableSpecGlowKey;
        public static ForgeConfigSpec.BooleanValue disableRenderNameTag;
        public static ForgeConfigSpec.BooleanValue disableRenderHitBox;
        public static ForgeConfigSpec.BooleanValue disableRenderHeadShotHitBox;
        public static ForgeConfigSpec.BooleanValue enableMapSelectionButtonForNonOps;

        public static ForgeConfigSpec.BooleanValue roomSyncPushEnabled;
        public static ForgeConfigSpec.IntValue roomSyncIntervalTicks;
        public static ForgeConfigSpec.IntValue roomSyncMaxWatchersPerRoom;

        public static void init(ForgeConfigSpec.Builder builder) {
            lock3PersonCamera = builder.comment(
                    "禁用第三人称").define("Lock3PersonCamera", true);

            lockSpecKeyHandle = builder.comment(
                    "阻止旁观者原版按键").define("LockSpecKeyHandle", true);

            disableDefaultGlow = builder.comment(
                    "禁用原版的发光效果").define("DisableDefaultGlow", true);

            disableSpecGlowKey = builder.comment(
                    "禁用旁观者模式的发光按键").define("DisableSpecGlowKey", true);

            disableRenderNameTag = builder.comment(
                    "禁止玩家头顶名称的渲染").define("DisableRenderNameTag", true);

            builder.comment("如果取消了碰撞箱的渲染则爆头碰撞箱也不会渲染了");
            builder.comment("Disabling hit box rendering will also hide the headshot hit box");

            disableRenderHitBox = builder.comment(
                    "禁止渲染碰撞箱").define("disableRenderHitBox", true);

            disableRenderHeadShotHitBox = builder.comment(
                    "禁止渲染爆头碰撞箱").define("disableRenderHeadShotHitBox", true);

            enableMapSelectionButtonForNonOps = builder.comment(
                    "允许非 OP 玩家在 ESC 暂停界面看到 FPSMatch 地图选择按钮",
                    "Allow non-OP players to see the FPSMatch map selection button in the ESC pause screen").define("EnableMapSelectionButtonForNonOps", true);

            builder.comment(
                    "房间/地图选择界面实时同步（订阅式事件驱动）",
                    "Room / map-selection UI live sync (subscription-based, event-driven)").push("roomSync");

            roomSyncPushEnabled = builder.comment(
                    "启用后：房间数据变化时主动向正在观看的客户端广播，实现准实时刷新；",
                    "关闭后回退到旧的“打开/手动刷新才拉取一次”行为。",
                    "When enabled, room changes are pushed to viewers for near-realtime UI; disabled falls back to legacy pull-on-open.").define("RoomSyncPushEnabled", true);

            roomSyncIntervalTicks = builder.comment(
                    "服务端合并/广播变更的间隔(tick)。越小越实时越耗带宽，20=每秒一次。",
                    "Server dirty-scan / broadcast interval in ticks. Smaller = more realtime & more bandwidth.").defineInRange("RoomSyncIntervalTicks", 10, 1, 100);

            roomSyncMaxWatchersPerRoom = builder.comment(
                    "单个房间单次广播的最大接收者数量，防止大厅广播风暴。",
                    "Max recipients per room per broadcast, to prevent lobby broadcast storms.").defineInRange("RoomSyncMaxWatchersPerRoom", 64, 1, 512);

            builder.pop();
        }
    }

    public static class Client {

        private Client(ForgeConfigSpec.Builder builder) {}
    }

    public static class Common {

        // normal
        public final ForgeConfigSpec.BooleanValue autoAdventureMode;

        public final ForgeConfigSpec.DoubleValue baseArmorPenetration;
        public final ForgeConfigSpec.DoubleValue headshotMultiplier;

        // drops
        public final ForgeConfigSpec.IntValue mainWeaponCount;
        public final ForgeConfigSpec.IntValue secondaryWeaponCount;
        public final ForgeConfigSpec.IntValue thirdWeaponCount;
        public final ForgeConfigSpec.IntValue throwableCount;

        // Flash Bomb
        public final ForgeConfigSpec.IntValue flashBombRadius;
        // Grenade
        public final ForgeConfigSpec.IntValue grenadeRadius;
        public final ForgeConfigSpec.IntValue grenadeFuseTime;
        public final ForgeConfigSpec.IntValue grenadeDamage;
        // Incendiary Grenade
        public final ForgeConfigSpec.IntValue incendiaryGrenadeOutTime;
        public final ForgeConfigSpec.IntValue incendiaryGrenadeLivingTime;
        public final ForgeConfigSpec.IntValue incendiaryGrenadeDamage;
        // SmokeShell
        public final ForgeConfigSpec.IntValue smokeShellLivingTime;

        private Common(ForgeConfigSpec.Builder builder) {
            builder.push("normal");
            autoAdventureMode = builder.comment(
                    "进入世界自动切换到冒险模式").define("AutoAdventureMode", true);
            builder.pop();

            builder.push("armor");
            {
                baseArmorPenetration = builder.comment(
                        "防弹衣的基础穿透系数",
                        "Base armor penetration multiplier",
                        "当玩家有防弹衣时，受到的伤害 = 原伤害 * (baseArmorPenetration / 2.0)").defineInRange("BaseArmorPenetration", 1.4, 0.1, 5.0);

                headshotMultiplier = builder.comment(
                        "爆头伤害倍率",
                        "Headshot damage multiplier").defineInRange("HeadshotMultiplier", 4.0, 1.0, 10.0);
            }
            builder.pop();

            builder.push("drops");
            {
                mainWeaponCount = builder.comment(
                        "比赛时主武器可拾取数量",
                        "Number of main weapons that can be picked up during the competition").defineInRange("MainWeaponCount", 1, 0, 10);
                secondaryWeaponCount = builder.comment(
                        "比赛时副武器可拾取数量",
                        "Number of secondary weapons that can be picked up during the competition").defineInRange("SecondaryCount", 1, 0, 10);
                throwableCount = builder.comment(
                        "比赛时投掷物可拾取数量",
                        "Number of throwable that can be picked up during the competition")
                        .defineInRange("ThrowableCount", 4, 0, 10);
                thirdWeaponCount = builder.comment(
                        "比赛时RPG品类(刀包用)可拾取数量",
                        "The number of weapons that can be picked up when the weapon type is RPG (knife) during the competition").defineInRange("ThirdWeaponCount", 1, 0, 10);
            }
            builder.pop();

            builder.push("throwable");
            {

                flashBombRadius = builder.comment(
                        "闪光弹致盲生效半径",
                        "Effective blinding radius of flash bombs").defineInRange("FlashBombRadius", 48, 0, 48);

                grenadeRadius = builder.comment(
                        "手雷爆炸生效半径",
                        "Effective explosion radius of grenades").defineInRange("GrenadeRadius", 3, 0, 10);

                grenadeFuseTime = builder.comment(
                        "手雷投掷后多久爆炸 (tick)",
                        "Delay before grenade detonation after being thrown (ticks)",
                        "20 ticks = 1 second").defineInRange("GrenadeFuseTime", 30, 0, 200);

                grenadeDamage = builder.comment(
                        "手雷的爆炸伤害",
                        "Explosion damage of grenades").defineInRange("GrenadeDamage", 20, 0, 9999);

                incendiaryGrenadeOutTime = builder.comment(
                        "燃烧弹投掷后多久自毁 (tick)",
                        "Self-destruct delay of incendiary grenades after being thrown (ticks)",
                        "20 ticks = 1 second").defineInRange("IncendiaryGrenadeOutTime", 40, 0, 200);

                incendiaryGrenadeLivingTime = builder.comment(
                        "燃烧弹激活后的存活时间 (tick)",
                        "Survival time after activation of incendiary grenade (ticks)",
                        "20 ticks = 1 second").defineInRange("IncendiaryGrenadeLivingTime", 140, 0, 400);

                incendiaryGrenadeDamage = builder.comment(
                        "燃烧弹的伤害",
                        "Damage value of incendiary grenades").defineInRange("IncendiaryGrenadeDamage", 2, 0, 9999);

                smokeShellLivingTime = builder.comment(
                        "烟雾弹激活后的存活时间 (tick)",
                        "Survival time after smoke bomb activation (ticks)",
                        "20 ticks = 1 second").defineInRange("SmokeShellLivingTime", 300, 0, 900);
            }
            builder.pop();
        }
    }

    public static Client client;
    public static ForgeConfigSpec clientSpec;
    public static Common common;
    public static ForgeConfigSpec commonSpec;
    public static ForgeConfigSpec serverSpec;

    static {
        final Pair<Client, ForgeConfigSpec> clientSpecPair = new ForgeConfigSpec.Builder().configure(Client::new);
        client = clientSpecPair.getLeft();
        clientSpec = clientSpecPair.getRight();
        final Pair<Common, ForgeConfigSpec> commonSpecPair = new ForgeConfigSpec.Builder().configure(Common::new);
        common = commonSpecPair.getLeft();
        commonSpec = commonSpecPair.getRight();
    }

    public static synchronized ForgeConfigSpec initServer() {
        if (serverSpec != null) {
            return serverSpec;
        }
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        Server.init(builder);
        serverSpec = builder.build();
        return serverSpec;
    }
}
