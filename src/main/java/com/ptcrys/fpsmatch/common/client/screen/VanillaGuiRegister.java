package com.ptcrys.fpsmatch.common.client.screen;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.screen.shop.modernui.ModernEditShopSlotScreen;
import com.ptcrys.fpsmatch.common.client.screen.shop.modernui.ModernEditorShopScreen;

public class VanillaGuiRegister {

    public static final DeferredRegister<MenuType<?>> CONTAINERS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, FPSMatch.MODID);
    public static final RegistryObject<MenuType<EditorShopContainer>> EDITOR_SHOP_CONTAINER = CONTAINERS.register("editor_shop_menu",
            () -> IForgeMenuType.create(EditorShopContainer::new));

    public static final RegistryObject<MenuType<EditShopSlotMenu>> EDIT_SHOP_SLOT_MENU = CONTAINERS.register(
            "edit_shop_slot_menu", () -> IForgeMenuType.create(EditShopSlotMenu::new));

    public static void register() {
        MenuScreens.register(EDITOR_SHOP_CONTAINER.get(), ModernEditorShopScreen::new);
        MenuScreens.register(EDIT_SHOP_SLOT_MENU.get(), ModernEditShopSlotScreen::new);
    }
}
