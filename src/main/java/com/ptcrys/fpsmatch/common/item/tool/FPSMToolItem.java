package com.ptcrys.fpsmatch.common.item.tool;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.item.tool.handler.ClickAction;
import com.ptcrys.fpsmatch.common.item.tool.handler.ClickActionContext;
import com.ptcrys.fpsmatch.common.item.tool.handler.EditToolClickHandler;
import com.ptcrys.fpsmatch.common.packet.EditToolClickC2SPacket;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.map.BaseMap;
import com.ptcrys.fpsmatch.core.team.ServerTeam;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

@Mod.EventBusSubscriber(modid = FPSMatch.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public abstract class FPSMToolItem extends Item implements EditToolClickHandler {

    public static final String TYPE_TAG = "SelectedType";
    public static final String MAP_TAG = "SelectedMap";
    public static final String TEAM_TAG = "SelectedTeam";
    public static final String EDIT_MODE_TAG = "EditMode";

    public static final String DOUBLE_CLICK_COUNT_TAG = "DoubleClickCount";
    public static final String DOUBLE_CLICK_LAST_TICK_TAG = "DoubleClickLastTick";
    public static final int DOUBLE_CLICK_TICK_LIMIT = 15;

    public FPSMToolItem(Properties pProperties) {
        super(pProperties);
    }

    @Override
    public void handleClick(ItemStack stack, ServerPlayer player,
                            boolean isDoubleClicked, boolean isShiftKeyDown, ClickAction action) {
        ClickActionContext context = new ClickActionContext(stack, player,
                isDoubleClicked, isShiftKeyDown, action);

        switch (action) {
            case LEFT_CLICK -> onLeftClick(context);
            case RIGHT_CLICK -> onRightClick(context);
        }
    }

    @Override
    public final @NotNull InteractionResultHolder<ItemStack> use(@NotNull Level level, @NotNull Player player, @NotNull InteractionHand interactionHand) {
        if (level.isClientSide()) return InteractionResultHolder.pass(player.getItemInHand(interactionHand));
        this.handleClick(player.getItemInHand(interactionHand), (ServerPlayer) player, false, player.isShiftKeyDown(), ClickAction.RIGHT_CLICK);
        return InteractionResultHolder.success(player.getItemInHand(interactionHand));
    }

    protected abstract void onLeftClick(ClickActionContext context);

    protected abstract void onRightClick(ClickActionContext context);

    // 标签操作方法
    public void setTag(ItemStack stack, String tagName, String value) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(tagName, value);
    }

    public String getTag(ItemStack stack, String tagName) {
        CompoundTag tag = stack.getOrCreateTag();
        return tag.contains(tagName) ? tag.getString(tagName) : "";
    }

    public int getIntTag(ItemStack stack, String tagName) {
        CompoundTag tag = stack.getOrCreateTag();
        return tag.contains(tagName) ? tag.getInt(tagName) : 0;
    }

    public void setIntTag(ItemStack stack, String tagName, int value) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putInt(tagName, value);
    }

    public void removeTag(ItemStack stack, String tagName) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.remove(tagName);
    }

    // FPSMCore相关方法
    public List<String> getAvailableMapTypes() {
        return FPSMCore.getInstance().getGameTypes();
    }

    public List<String> getMapsByType(String mapType) {
        if (mapType.isEmpty()) {
            return List.of();
        }
        return FPSMCore.getInstance().getMapNamesWithType(mapType);
    }

    public Optional<ServerTeam> getPlayerCurrentTeam(ServerPlayer player) {
        return FPSMCore.getInstance().getMapByPlayer(player).flatMap(map -> map.getMapTeams().getTeamByPlayer(player));
    }

    public Optional<BaseMap> getTeamBelongingMap(ServerTeam team) {
        return FPSMCore.getInstance().getMapByTypeWithName(team.gameType, team.mapName);
    }

    @Override
    public void inventoryTick(@NotNull ItemStack stack, @NotNull Level level, @NotNull Entity entity,
                              int slotId, boolean isSelected) {
        super.inventoryTick(stack, level, entity, slotId, isSelected);
        if (level.isClientSide) return;

        // 初始化标签
        if (!stack.getOrCreateTag().contains(DOUBLE_CLICK_COUNT_TAG)) {
            this.setIntTag(stack, DOUBLE_CLICK_COUNT_TAG, 0);
        }
        if (!stack.getOrCreateTag().contains(DOUBLE_CLICK_LAST_TICK_TAG)) {
            this.setIntTag(stack, DOUBLE_CLICK_LAST_TICK_TAG, 0);
        }

        // 双击检测逻辑
        int lastClickTick = this.getIntTag(stack, DOUBLE_CLICK_LAST_TICK_TAG);
        int clickCount = this.getIntTag(stack, DOUBLE_CLICK_COUNT_TAG);

        if (clickCount > 0) {
            lastClickTick++;
            this.setIntTag(stack, DOUBLE_CLICK_LAST_TICK_TAG, lastClickTick);

            if (lastClickTick > DOUBLE_CLICK_TICK_LIMIT) {
                this.setIntTag(stack, DOUBLE_CLICK_COUNT_TAG, 0);
                this.setIntTag(stack, DOUBLE_CLICK_LAST_TICK_TAG, 0);
            }
        }
    }

    @SubscribeEvent
    public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        Player player = event.getEntity();
        Level level = player.level();
        if (!level.isClientSide) return;
        ItemStack stack = player.getMainHandItem();

        if (!(stack.getItem() instanceof FPSMToolItem)) return;

        FPSMatch.sendToServer(new EditToolClickC2SPacket(
                ClickAction.LEFT_CLICK,
                player.isShiftKeyDown()));
    }
}
