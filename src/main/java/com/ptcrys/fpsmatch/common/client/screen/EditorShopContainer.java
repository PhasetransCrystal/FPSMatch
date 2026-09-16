package com.ptcrys.fpsmatch.common.client.screen;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.capability.team.ShopCapability;
import com.ptcrys.fpsmatch.common.mapselect.MapRoomQueryService;
import com.ptcrys.fpsmatch.common.client.screen.shop.ShopEditorValues;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomToastS2CPacket;
import com.ptcrys.fpsmatch.core.shop.FPSMShop;
import com.ptcrys.fpsmatch.core.shop.INamedType;
import com.ptcrys.fpsmatch.core.shop.slot.ShopSlot;
import com.ptcrys.fpsmatch.util.FPSMCodec;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class EditorShopContainer extends AbstractContainerMenu {
    public static final int SLOT_SIZE = 18;
    public static final int SLOT_GAP = 10;
    public static final int SLOT_SPACING_X = SLOT_SIZE + 4 * SLOT_GAP;
    public static final int SLOT_SPACING_Y = SLOT_SIZE + SLOT_GAP;
    private static final int GRID_LEFT = 90;
    private static final int GRID_TOP = 52;
    private static final int ID_MAX_LENGTH = 128;

    private int rows = 0;
    private int cols = 0;

    private final String gameType;
    private final String mapName;
    private final String teamName;
    private final ItemStackHandler itemStackHandler;
    private int totalIndex;
    private final Map<String, TypeInfo> types = new LinkedHashMap<>();
    private final List<ShopSlot> allShopSlots;

    public record TypeInfo(String name, int slotCount, int startIndex) {
    }

    // Server-side constructor
    public EditorShopContainer(int containerId, Inventory playerInventory, FPSMShop<?> shop, String gameType, String mapName, String teamName) {
        super(VanillaGuiRegister.EDITOR_SHOP_CONTAINER.get(), containerId);
        this.gameType = gameType;
        this.mapName = mapName;
        this.teamName = teamName;

        List<?> enums = shop.getEnums();
        int total = 0;
        for (Object type : enums) {
            if (!(type instanceof INamedType named)) continue;
            rows++;
            int slotCount = named.slotCount();
            this.types.put(named.name(), new TypeInfo(named.name(), slotCount, total));
            total += slotCount;
            if (cols < slotCount) cols = slotCount;
        }
        this.totalIndex = total;
        this.itemStackHandler = new ItemStackHandler(total);
        this.allShopSlots = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            this.allShopSlots.add(null);
        }

        for (int row = 0; row < rows; row++) {
            List<String> typeNames = new ArrayList<>(types.keySet());
            String type = typeNames.get(row);
            List<ShopSlot> shopSlots = shop.getDefaultShopSlotListByType(type);
            int slotCount = types.get(type).slotCount();
            for (int col = 0; col < slotCount; col++) {
                int slotIndex = types.get(type).startIndex() + col;
                ShopSlot shopSlot = (shopSlots != null && col < shopSlots.size()) ? shopSlots.get(col) : null;
                ItemStack slotItem = shopSlot == null ? ItemStack.EMPTY : shopSlot.process();
                if (shopSlot != null) {
                    this.allShopSlots.set(slotIndex, shopSlot);
                }
                SlotItemHandler customSlot = new SlotItemHandler(
                        itemStackHandler,
                        slotIndex,
                        getGridLeft() + col * SLOT_SPACING_X,
                        getGridTop() + row * SLOT_SPACING_Y
                );
                this.addSlot(customSlot);
                if (!slotItem.isEmpty()) {
                    itemStackHandler.setStackInSlot(slotIndex, slotItem);
                }
            }
        }
    }

    // Client-side constructor
    public EditorShopContainer(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
        super(VanillaGuiRegister.EDITOR_SHOP_CONTAINER.get(), containerId);
        this.gameType = buf.readUtf(ID_MAX_LENGTH);
        this.mapName = buf.readUtf(ID_MAX_LENGTH);
        this.teamName = buf.readUtf(ID_MAX_LENGTH);

        int typeCount = buf.readInt();
        int total = 0;
        for (int t = 0; t < typeCount; t++) {
            String typeName = buf.readUtf(ID_MAX_LENGTH);
            int slotCount = buf.readInt();
            rows++;
            this.types.put(typeName, new TypeInfo(typeName, slotCount, total));
            total += slotCount;
            if (cols < slotCount) cols = slotCount;
        }
        this.totalIndex = total;
        this.itemStackHandler = new ItemStackHandler(total);
        this.allShopSlots = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            this.allShopSlots.add(null);
        }

        for (int row = 0; row < rows; row++) {
            List<String> typeNames = new ArrayList<>(types.keySet());
            String type = typeNames.get(row);
            int slotCount = types.get(type).slotCount();
            for (int col = 0; col < slotCount; col++) {
                int slotIndex = types.get(type).startIndex() + col;
                ItemStack slotItem = buf.readItem();
                String json = buf.readUtf();
                ShopSlot shopSlot = null;
                if (!json.isEmpty()) {
                    try {
                        shopSlot = FPSMCodec.decodeFromJson(ShopSlot.CODEC, new Gson().fromJson(json, JsonElement.class));
                    } catch (Exception ignored) {
                    }
                }
                if (shopSlot != null) {
                    this.allShopSlots.set(slotIndex, shopSlot);
                }
                SlotItemHandler customSlot = new SlotItemHandler(
                        itemStackHandler,
                        slotIndex,
                        getGridLeft() + col * SLOT_SPACING_X,
                        getGridTop() + row * SLOT_SPACING_Y
                );
                this.addSlot(customSlot);
                if (!slotItem.isEmpty()) {
                    itemStackHandler.setStackInSlot(slotIndex, slotItem);
                }
            }
        }
    }

    public Map<String, TypeInfo> getTypes() {
        return types;
    }

    public int getTotalIndex() {
        return totalIndex;
    }

    public ItemStackHandler getItemStackHandler() {
        return itemStackHandler;
    }

    public int getCols() {
        return cols;
    }

    public int getRows() {
        return rows;
    }

    public int getGridLeft() {
        return GRID_LEFT;
    }

    public int getGridTop() {
        return GRID_TOP;
    }

    public int getGridWidth() {
        return cols <= 0 ? 0 : (cols - 1) * SLOT_SPACING_X + SLOT_SIZE;
    }

    public int getGridHeight() {
        return rows <= 0 ? 0 : (rows - 1) * SLOT_SPACING_Y + SLOT_SIZE;
    }

    public int getImageWidth() {
        return getGridLeft() + getGridWidth();
    }

    public int getImageHeight() {
        return getGridTop() + getGridHeight() + 36;
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

    @Override
    public boolean stillValid(@NotNull Player player) {
        if (player.level().isClientSide) {
            return true;
        }
        return player instanceof ServerPlayer serverPlayer
                && MapRoomQueryService.isMapOperator(serverPlayer)
                && resolveCurrentShop().isPresent();
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int slotIndex) {
        return ItemStack.EMPTY;
    }

    private int getSlotIndex(String type, int col) {
        if (type == null || types.isEmpty() || col < 0) {
            return -1;
        }
        TypeInfo info = types.get(type);
        if (info == null || col >= info.slotCount()) {
            return -1;
        }
        return info.startIndex() + col;
    }

    private ShopSlot getShopSlot(int index) {
        if (index < 0 || index >= allShopSlots.size()) {
            return null;
        }
        return allShopSlots.get(index);
    }

    private SlotRef getShopSlotRef(int index) {
        if (types.isEmpty() || index < 0 || index >= totalIndex) {
            return null;
        }
        int currentIndex = 0;
        for (Map.Entry<String, TypeInfo> entry : types.entrySet()) {
            TypeInfo info = entry.getValue();
            int slotCount = info.slotCount();
            if (index >= currentIndex && index < currentIndex + slotCount) {
                return new SlotRef(entry.getKey(), index - currentIndex);
            }
            currentIndex += slotCount;
        }
        return null;
    }

    @Override
    public void clicked(int slotIndex, int button, @NotNull ClickType clickType, @NotNull Player player) {
        if (player instanceof ServerPlayer serverPlayer
                && (!MapRoomQueryService.isMapOperator(serverPlayer) || resolveCurrentShop().isEmpty())) {
            FPSMatch.sendToPlayer(serverPlayer, new MapRoomToastS2CPacket(
                    Component.translatable("gui.fpsm.shop_editor.open.no_permission"), true));
            return;
        }
        boolean isCustomContainer = slotIndex >= 0 && slotIndex < this.totalIndex;
        if (isCustomContainer) {
            ShopSlot targetShopSlot = getShopSlot(slotIndex);
            SlotRef slotRef = getShopSlotRef(slotIndex);
            if (targetShopSlot != null && slotRef != null) {
                this.openSecondMenu(player, targetShopSlot, slotRef);
            }
            return;
        }
        super.clicked(slotIndex, button, clickType, player);
    }

    @Override
    public void removed(@NotNull Player pPlayer) {
        super.removed(pPlayer);
    }

    public List<ShopSlot> getAllSlots() {
        return Collections.unmodifiableList(allShopSlots);
    }

    /** Validate the entire selection before changing any default slots. */
    public EditShopSlotMenu.SaveResult trySetGroups(ServerPlayer player, int[] indices, int groupId) {
        if (player == null || player.containerMenu != this) return EditShopSlotMenu.SaveResult.INVALID_MENU;
        if (!MapRoomQueryService.isMapOperator(player)) return EditShopSlotMenu.SaveResult.NO_PERMISSION;
        if (!ShopEditorValues.validGroup(groupId) || !ShopEditorValues.validSelection(indices, allShopSlots.size()))
            return EditShopSlotMenu.SaveResult.INVALID_VALUE;
        var resolved = resolveCurrentShop();
        if (resolved.isEmpty()) return EditShopSlotMenu.SaveResult.SHOP_UNAVAILABLE;
        var shop = resolved.get();
        List<List<ShopSlot>> lists = new ArrayList<>();
        List<SlotRef> refs = new ArrayList<>();
        List<ShopSlot> replacements = new ArrayList<>();
        for (int index : indices) {
            SlotRef ref = getShopSlotRef(index);
            if (ref == null) return EditShopSlotMenu.SaveResult.INVALID_SLOT;
            final List<ShopSlot> current;
            try { current = shop.getDefaultShopSlotListByType(ref.type()); }
            catch (IllegalArgumentException invalid) { return EditShopSlotMenu.SaveResult.INVALID_SLOT; }
            if (current == null || ref.slotNum() >= current.size() || current.get(ref.slotNum()) == null)
                return EditShopSlotMenu.SaveResult.INVALID_SLOT;
            if (current.get(ref.slotNum()) != allShopSlots.get(index)) return EditShopSlotMenu.SaveResult.STALE_SLOT;
            ShopSlot replacement = current.get(ref.slotNum()).copy();
            replacement.setGroupId(groupId);
            refs.add(ref); lists.add(current); replacements.add(replacement);
        }
        for (int i = 0; i < indices.length; i++) {
            lists.get(i).set(refs.get(i).slotNum(), replacements.get(i));
            allShopSlots.set(indices[i], replacements.get(i));
        }
        shop.resetPlayerData();
        shop.syncShopData();
        FPSMCore.getInstance().getFPSMDataManager().saveAllData();
        return EditShopSlotMenu.SaveResult.SUCCESS;
    }

    public void applyGroups(int[] indices, int groupId) {
        if (!ShopEditorValues.validGroup(groupId) || !ShopEditorValues.validSelection(indices, allShopSlots.size())) return;
        for (int index : indices) if (allShopSlots.get(index) != null) allShopSlots.get(index).setGroupId(groupId);
    }

    private void openSecondMenu(Player player, ShopSlot shopSlot, SlotRef slotRef) {
        if (player == null || shopSlot == null || slotRef == null) {
            return;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            NetworkHooks.openScreen(serverPlayer,
                    new SimpleMenuProvider(
                            (windowId, inv, p) -> new EditShopSlotMenu(windowId, inv, shopSlot, gameType, mapName, teamName, slotRef.type(), slotRef.slotNum()),
                            Component.translatable("gui.fpsm.edit_shop_slot.title")
                    ),
                    buf -> {
                        String json = "";
                        try {
                            json = new Gson().toJson(FPSMCodec.encodeToJson(ShopSlot.CODEC, shopSlot));
                        } catch (Exception e) {
                            json = "";
                        }
                        buf.writeUtf(json);
                        buf.writeUtf(gameType);
                        buf.writeUtf(mapName);
                        buf.writeUtf(teamName);
                        buf.writeUtf(slotRef.type());
                        buf.writeInt(slotRef.slotNum());
                        buf.writeCollection(shopSlot.getListenerNames(), (out, name) -> out.writeUtf(name, 256));
                        buf.writeCollection(com.ptcrys.fpsmatch.core.FPSMCore.getInstance().getListenerModuleManager()
                                .getListenerModules().stream().sorted().toList(), (out, name) -> out.writeUtf(name, 256));
                    }
            );
        }
    }

    private Optional<FPSMShop<?>> resolveCurrentShop() {
        return MapRoomQueryService.findMap(gameType, mapName)
                .flatMap(map -> map.getMapTeams().getTeamByName(teamName))
                .flatMap(ShopCapability::getShop);
    }

    private record SlotRef(String type, int slotNum) {
    }
}
