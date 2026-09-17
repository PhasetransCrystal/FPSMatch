package com.ptcrys.fpsmatch.compat.cloth;

import net.minecraft.network.chat.Component;

import com.ptcrys.fpsmatch.config.FPSMConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;

public class FPSMClothConfig {

    public static void initClient(ConfigBuilder root, ConfigEntryBuilder entryBuilder) {
        ConfigCategory clientCategory = root.getOrCreateCategory(Component.translatable("config.fpsmatch.client"));

        // 客户端配置项（目前为空）
        clientCategory.addEntry(entryBuilder.startTextDescription(
                Component.translatable("config.fpsmatch.client.no_settings"))
                .build());
    }

    public static void initCommon(ConfigBuilder root, ConfigEntryBuilder entryBuilder) {
        // 武器拾取数量配置
        ConfigCategory dropsCategory = root.getOrCreateCategory(Component.translatable("config.fpsmatch.common.drops"));

        dropsCategory.addEntry(entryBuilder.startIntField(
                Component.translatable("config.fpsmatch.common.drops.main_weapon_count"),
                FPSMConfig.common.mainWeaponCount.get())
                .setMin(0).setMax(10)
                .setDefaultValue(1)
                .setTooltip(Component.translatable("config.fpsmatch.common.drops.main_weapon_count.tooltip"))
                .setSaveConsumer(FPSMConfig.common.mainWeaponCount::set)
                .build());

        dropsCategory.addEntry(entryBuilder.startIntField(
                Component.translatable("config.fpsmatch.common.drops.secondary_weapon_count"),
                FPSMConfig.common.secondaryWeaponCount.get())
                .setMin(0).setMax(10)
                .setDefaultValue(1)
                .setTooltip(Component.translatable("config.fpsmatch.common.drops.secondary_weapon_count.tooltip"))
                .setSaveConsumer(FPSMConfig.common.secondaryWeaponCount::set)
                .build());

        dropsCategory.addEntry(entryBuilder.startIntField(
                Component.translatable("config.fpsmatch.common.drops.throwable_count"),
                FPSMConfig.common.throwableCount.get())
                .setMin(0).setMax(10)
                .setDefaultValue(4)
                .setTooltip(Component.translatable("config.fpsmatch.common.drops.throwable_count.tooltip"))
                .setSaveConsumer(FPSMConfig.common.throwableCount::set)
                .build());

        dropsCategory.addEntry(entryBuilder.startIntField(
                Component.translatable("config.fpsmatch.common.drops.third_weapon_count"),
                FPSMConfig.common.thirdWeaponCount.get())
                .setMin(0).setMax(10)
                .setDefaultValue(1)
                .setTooltip(Component.translatable("config.fpsmatch.common.drops.third_weapon_count.tooltip"))
                .setSaveConsumer(FPSMConfig.common.thirdWeaponCount::set)
                .build());

        // 投掷物配置
        ConfigCategory throwableCategory = root.getOrCreateCategory(Component.translatable("config.fpsmatch.common.throwable"));

        // 闪光弹配置
        throwableCategory.addEntry(entryBuilder.startIntField(
                Component.translatable("config.fpsmatch.common.throwable.flash_bomb_radius"),
                FPSMConfig.common.flashBombRadius.get())
                .setMin(0).setMax(48)
                .setDefaultValue(48)
                .setTooltip(Component.translatable("config.fpsmatch.common.throwable.flash_bomb_radius.tooltip"))
                .setSaveConsumer(FPSMConfig.common.flashBombRadius::set)
                .build());

        // 手雷配置
        throwableCategory.addEntry(entryBuilder.startIntField(
                Component.translatable("config.fpsmatch.common.throwable.grenade_radius"),
                FPSMConfig.common.grenadeRadius.get())
                .setMin(0).setMax(10)
                .setDefaultValue(3)
                .setTooltip(Component.translatable("config.fpsmatch.common.throwable.grenade_radius.tooltip"))
                .setSaveConsumer(FPSMConfig.common.grenadeRadius::set)
                .build());

        throwableCategory.addEntry(entryBuilder.startIntField(
                Component.translatable("config.fpsmatch.common.throwable.grenade_fuse_time"),
                FPSMConfig.common.grenadeFuseTime.get())
                .setMin(0).setMax(200)
                .setDefaultValue(30)
                .setTooltip(Component.translatable("config.fpsmatch.common.throwable.grenade_fuse_time.tooltip"))
                .setSaveConsumer(FPSMConfig.common.grenadeFuseTime::set)
                .build());

        throwableCategory.addEntry(entryBuilder.startIntField(
                Component.translatable("config.fpsmatch.common.throwable.grenade_damage"),
                FPSMConfig.common.grenadeDamage.get())
                .setMin(0).setMax(9999)
                .setDefaultValue(20)
                .setTooltip(Component.translatable("config.fpsmatch.common.throwable.grenade_damage.tooltip"))
                .setSaveConsumer(FPSMConfig.common.grenadeDamage::set)
                .build());

        // 燃烧弹配置
        throwableCategory.addEntry(entryBuilder.startIntField(
                Component.translatable("config.fpsmatch.common.throwable.incendiary_grenade_out_time"),
                FPSMConfig.common.incendiaryGrenadeOutTime.get())
                .setMin(0).setMax(200)
                .setDefaultValue(40)
                .setTooltip(Component.translatable("config.fpsmatch.common.throwable.incendiary_grenade_out_time.tooltip"))
                .setSaveConsumer(FPSMConfig.common.incendiaryGrenadeOutTime::set)
                .build());

        throwableCategory.addEntry(entryBuilder.startIntField(
                Component.translatable("config.fpsmatch.common.throwable.incendiary_grenade_living_time"),
                FPSMConfig.common.incendiaryGrenadeLivingTime.get())
                .setMin(0).setMax(400)
                .setDefaultValue(140)
                .setTooltip(Component.translatable("config.fpsmatch.common.throwable.incendiary_grenade_living_time.tooltip"))
                .setSaveConsumer(FPSMConfig.common.incendiaryGrenadeLivingTime::set)
                .build());

        throwableCategory.addEntry(entryBuilder.startIntField(
                Component.translatable("config.fpsmatch.common.throwable.incendiary_grenade_damage"),
                FPSMConfig.common.incendiaryGrenadeDamage.get())
                .setMin(0).setMax(9999)
                .setDefaultValue(2)
                .setTooltip(Component.translatable("config.fpsmatch.common.throwable.incendiary_grenade_damage.tooltip"))
                .setSaveConsumer(FPSMConfig.common.incendiaryGrenadeDamage::set)
                .build());

        // 烟雾弹配置
        throwableCategory.addEntry(entryBuilder.startIntField(
                Component.translatable("config.fpsmatch.common.throwable.smoke_shell_living_time"),
                FPSMConfig.common.smokeShellLivingTime.get())
                .setMin(0).setMax(900)
                .setDefaultValue(300)
                .setTooltip(Component.translatable("config.fpsmatch.common.throwable.smoke_shell_living_time.tooltip"))
                .setSaveConsumer(FPSMConfig.common.smokeShellLivingTime::set)
                .build());

        // 防弹衣配置
        ConfigCategory armorCategory = root.getOrCreateCategory(Component.translatable("config.fpsmatch.common.armor"));

        armorCategory.addEntry(entryBuilder.startDoubleField(
                Component.translatable("config.fpsmatch.common.armor.base_armor_penetration"),
                FPSMConfig.common.baseArmorPenetration.get())
                .setMin(0.1).setMax(5.0)
                .setDefaultValue(1.4)
                .setTooltip(Component.translatable("config.fpsmatch.common.armor.base_armor_penetration.tooltip"))
                .setSaveConsumer(FPSMConfig.common.baseArmorPenetration::set)
                .build());

        armorCategory.addEntry(entryBuilder.startDoubleField(
                Component.translatable("config.fpsmatch.common.armor.headshot_multiplier"),
                FPSMConfig.common.headshotMultiplier.get())
                .setMin(1.0).setMax(10.0)
                .setDefaultValue(4.0)
                .setTooltip(Component.translatable("config.fpsmatch.common.armor.headshot_multiplier.tooltip"))
                .setSaveConsumer(FPSMConfig.common.headshotMultiplier::set)
                .build());
    }

    // 主入口方法
    public static void init(ConfigBuilder root, ConfigEntryBuilder entryBuilder) {
        initClient(root, entryBuilder);
        initCommon(root, entryBuilder);
    }
}
