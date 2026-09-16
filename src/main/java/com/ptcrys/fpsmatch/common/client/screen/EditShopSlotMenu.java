package com.ptcrys.fpsmatch.common.client.screen;


import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.capability.team.ShopCapability;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.common.mapselect.MapRoomQueryService;
import com.ptcrys.fpsmatch.common.client.screen.shop.ShopEditorValues;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomToastS2CPacket;
import com.ptcrys.fpsmatch.core.map.BaseMap;
import com.ptcrys.fpsmatch.core.shop.FPSMShop;
import com.ptcrys.fpsmatch.core.shop.slot.ShopSlot;
import com.ptcrys.fpsmatch.core.team.ServerTeam;
import com.ptcrys.fpsmatch.util.FPSMCodec;
import com.ptcrys.fpsmatch.util.FPSMUtil;
import com.ptcrys.fpsmatch.compat.gun.GunCompatManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

import java.util.List;
import java.util.Optional;


public class EditShopSlotMenu extends AbstractContainerMenu {
    private static final int ID_MAX_LENGTH = 128;

    private final ContainerData data;
    private final ItemStackHandler itemHandler;
    private ShopSlot shopSlot;
    private List<String> listeners;
    private List<String> availableListeners;
    private final String gameType;
    private final String mapName;
    private final String teamName;
    private final String shopType;
    private final int slotNum;

    public EditShopSlotMenu(int id, Inventory playerInventory, ShopSlot shopSlot, String gameType, String mapName, String teamName, String shopType, int slotNum) {
        this(id, playerInventory, new ItemStackHandler(1), new SimpleContainerData(3), shopSlot, gameType, mapName, teamName, shopType, slotNum);
    }

    public EditShopSlotMenu(int id, Inventory playerInventory, FriendlyByteBuf buf) {
        this(
                id,
                playerInventory,
                FPSMCodec.decodeFromJson(ShopSlot.CODEC, new Gson().fromJson(buf.readUtf(), JsonElement.class)),
                buf.readUtf(ID_MAX_LENGTH),
                buf.readUtf(ID_MAX_LENGTH),
                buf.readUtf(ID_MAX_LENGTH),
                buf.readUtf(ID_MAX_LENGTH),
                buf.readInt()
        );
        // Read names directly: a module registered only on the server need not exist client-side.
        listeners = buf.readCollection(FriendlyByteBuf.limitValue(java.util.ArrayList::new, ShopEditorValues.MAX_CATALOG),
                in -> in.readUtf(ShopEditorValues.MAX_MODULE_NAME));
        availableListeners = buf.readCollection(FriendlyByteBuf.limitValue(java.util.ArrayList::new, ShopEditorValues.MAX_CATALOG),
                in -> in.readUtf(ShopEditorValues.MAX_MODULE_NAME));
    }

    public EditShopSlotMenu(int id, Inventory playerInventory, ItemStackHandler handler, ContainerData data, ShopSlot shopSlot, String gameType, String mapName, String teamName, String shopType, int slotNum) {
        super(VanillaGuiRegister.EDIT_SHOP_SLOT_MENU.get(), id);
        this.itemHandler = handler;
        this.data = data;
        this.shopSlot = shopSlot;
        this.listeners = List.copyOf(shopSlot.getListenerNames());
        this.availableListeners = FPSMCore.getInstance().getListenerModuleManager().getListenerModules().stream().sorted().toList();
        this.gameType = gameType;
        this.mapName = mapName;
        this.teamName = teamName;
        this.shopType = shopType;
        this.slotNum = slotNum;
        this.setAmmo(shopSlot.getAmmoCount());
        this.setPrice(shopSlot.getDefaultCost());
        this.setGroupId(shopSlot.getGroupId());
        this.itemHandler.setStackInSlot(0, this.shopSlot.process());
        this.addSlot(new SlotItemHandler(itemHandler, 0, 20, 20));

        addPlayerInventory(playerInventory, 8, 124);

        addDataSlots(data);
    }


    private void addPlayerInventory(Inventory playerInventory, int x, int y) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, x + col * 18, y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, x + col * 18, y + 58));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int i) {
        return ItemStack.EMPTY;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (player instanceof ServerPlayer serverPlayer
                && (!MapRoomQueryService.isMapOperator(serverPlayer)
                || resolveCurrentShop().isEmpty())) {
            FPSMatch.sendToPlayer(serverPlayer, new MapRoomToastS2CPacket(
                    net.minecraft.network.chat.Component.translatable(
                            "gui.fpsm.shop_editor.open.no_permission"), true));
            return;
        }
        if (slotId == 0) {
            if (clickType == ClickType.PICKUP && !getCarried().isEmpty()) {
                itemHandler.setStackInSlot(0, getCarried().copy());
            }
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }

    @Override
    public boolean stillValid(Player player) {
        if (player.level().isClientSide) {
            return true;
        }
        return player instanceof ServerPlayer serverPlayer
                && MapRoomQueryService.isMapOperator(serverPlayer)
                && resolveCurrentShop().isPresent();
    }

    @Override
    public void removed(Player pPlayer) {
        super.removed(pPlayer);
    }

    public void saveData(ServerPlayer serverPlayer) {
        trySaveData(serverPlayer, getAmmo(), getPrice(), getGroupId());
    }

    /**
     * Validates and commits one slot edit using only the server-owned menu identity.
     * Map/team/type/index are never trusted from the client.
     */
    public SaveResult trySaveData(ServerPlayer serverPlayer, int ammoCount, int defaultCost, int groupId) {
        return trySaveData(serverPlayer, ammoCount, defaultCost, groupId, shopSlot.getListenerNames());
    }

    public SaveResult trySaveData(ServerPlayer serverPlayer, int ammoCount, int defaultCost, int groupId, List<String> modules) {
        if (serverPlayer == null || serverPlayer.containerMenu != this) {
            return SaveResult.INVALID_MENU;
        }
        if (!MapRoomQueryService.isMapOperator(serverPlayer)) {
            return SaveResult.NO_PERMISSION;
        }
        var manager = FPSMCore.getInstance().getListenerModuleManager();
        if (!ShopEditorValues.validModules(modules, manager.getListenerModules())) return SaveResult.INVALID_MODULE;
        if (defaultCost < 0 || defaultCost > 1_000_000
                || ammoCount < 0 || ammoCount > 999_999
                || groupId < -1 || groupId > 999_999) {
            return SaveResult.INVALID_VALUE;
        }
        ItemStack editedStack = itemHandler.getStackInSlot(0).copy();
        if (editedStack.isEmpty() || editedStack.getCount() <= 0
                || editedStack.getCount() > editedStack.getMaxStackSize()) {
            return SaveResult.INVALID_ITEM;
        }

        Optional<FPSMShop<?>> resolvedShop = resolveCurrentShop();
        if (resolvedShop.isEmpty()) {
            return SaveResult.SHOP_UNAVAILABLE;
        }
        FPSMShop<?> shop = resolvedShop.get();
        final List<ShopSlot> slots;
        try {
            slots = shop.getDefaultShopSlotListByType(shopType);
        } catch (IllegalArgumentException invalidType) {
            return SaveResult.INVALID_SLOT;
        }
        if (slots == null || slotNum < 0 || slotNum >= slots.size()) {
            return SaveResult.INVALID_SLOT;
        }
        ShopSlot current = slots.get(slotNum);
        if (current != shopSlot) {
            return SaveResult.STALE_SLOT;
        }

        if (GunCompatManager.isGun(editedStack)) {
            FPSMUtil.setTotalDummyAmmo(
                    editedStack,
                    GunCompatManager.findProvider(editedStack),
                    ammoCount
            );
        }
        if (sameConfiguration(current, editedStack, defaultCost, groupId)
                && current.getListenerNames().equals(modules)) {
            setAmmo(ammoCount);
            setPrice(defaultCost);
            setGroupId(groupId);
            return SaveResult.SUCCESS;
        }

        ShopSlot replacement = current.copy();
        ItemStack savedStack = editedStack.copy();
        replacement.setItemSupplier(savedStack::copy);
        replacement.setDefaultCost(defaultCost);
        replacement.setGroupId(groupId);
        for (String name : replacement.getListenerNames()) replacement.removeListenerModule(name);
        for (String name : modules) replacement.addListener(manager.getListenerModule(name));
        shop.replaceDefaultShopData(shopType, slotNum, replacement);
        shop.syncShopData();
        FPSMCore.getInstance().getFPSMDataManager().saveAllData();
        this.shopSlot = replacement;
        this.listeners = List.copyOf(replacement.getListenerNames());
        this.itemHandler.setStackInSlot(0, savedStack.copy());
        setAmmo(ammoCount);
        setPrice(defaultCost);
        setGroupId(groupId);
        return SaveResult.SUCCESS;
    }

    private Optional<FPSMShop<?>> resolveCurrentShop() {
        return MapRoomQueryService.findMap(gameType, mapName)
                .flatMap(this::resolveTeam)
                .flatMap(ShopCapability::getShop);
    }

    private static boolean sameConfiguration(
            ShopSlot current,
            ItemStack editedStack,
            int defaultCost,
            int groupId
    ) {
        return current.getDefaultCost() == defaultCost
                && current.getGroupId() == groupId
                && ItemStack.matches(current.process(), editedStack);
    }

    public enum SaveResult {
        SUCCESS("gui.fpsm.shop_editor.save.success"),
        NO_PERMISSION("gui.fpsm.shop_editor.save.no_permission"),
        INVALID_MENU("gui.fpsm.shop_editor.save.invalid_menu"),
        SHOP_UNAVAILABLE("gui.fpsm.shop_editor.save.shop_unavailable"),
        INVALID_SLOT("gui.fpsm.shop_editor.save.invalid_slot"),
        STALE_SLOT("gui.fpsm.shop_editor.save.stale_slot"),
        INVALID_ITEM("gui.fpsm.shop_editor.save.invalid_item"),
        INVALID_VALUE("gui.fpsm.shop_editor.save.invalid_value"),
        INVALID_MODULE("gui.fpsm.shop_editor.save.invalid_module");

        private final String translationKey;

        SaveResult(String translationKey) {
            this.translationKey = translationKey;
        }

        public boolean success() {
            return this == SUCCESS;
        }

        public String translationKey() {
            return translationKey;
        }
    }

    private Optional<ServerTeam> resolveTeam(BaseMap map) {
        return map.getMapTeams().getTeamByName(teamName);
    }

    public List<String> getListeners() {
        return List.copyOf(listeners);
    }

    public List<String> getAvailableListeners() { return List.copyOf(availableListeners); }

    public boolean isGun(){
        return GunCompatManager.isGun(this.slots.get(0).getItem());
    }

    public int getAmmo() {
        return this.data.get(0);
    }

    public int getPrice() {
        return this.data.get(1);
    }

    public int getGroupId() {
        return this.data.get(2);
    }

    public void setAmmo(int ammoCount) {
        this.data.set(0, ammoCount);
    }

    public void setPrice(int price) {
        this.data.set(1, price);
    }

    public void setGroupId(int groupId) {
        this.data.set(2, groupId);
    }

    public String getGameType() {
        return gameType;
    }

    public String getMapName() {
        return mapName;
    }

    public String getTeamName() {
        return teamName;
    }

    public String getShopType() {
        return shopType;
    }

    public int getSlotNum() {
        return slotNum;
    }

    public ContainerData getData() {
        return this.data;
    }
}
