package com.ptcrys.fpsmatch.common.item.tool;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.item.tool.handler.ClickAction;
import com.ptcrys.fpsmatch.common.item.tool.handler.ClickActionContext;
import com.ptcrys.fpsmatch.common.packet.EditToolClickC2SPacket;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.map.BaseMap;
import com.ptcrys.fpsmatch.core.team.ServerTeam;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

@Mod.EventBusSubscriber(modid = FPSMatch.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public abstract class EditToolItem extends FPSMToolItem {

    public static final String TYPE_TAG = "SelectedType";
    public static final String MAP_TAG = "SelectedMap";
    public static final String TEAM_TAG = "SelectedTeam";
    public static final String EDIT_MODE_TAG = "EditMode";

    public static final String DOUBLE_CLICK_COUNT_TAG = "DoubleClickCount";
    public static final String DOUBLE_CLICK_LAST_TICK_TAG = "DoubleClickLastTick";
    public static final int DOUBLE_CLICK_TICK_LIMIT = 15;

    public EditToolItem(Properties pProperties) {
        super(pProperties);
    }

    @Override
    protected void onLeftClick(ClickActionContext context) {
        if (context.isDoubleClicked()) {
            if (context.isShiftKeyDown()) {
                clearAllSelections(context);
            }
        } else {
            EditMode currentMode = this.getCurrentEditMode(context.stack());
            EditMode nextMode = getNextEditMode(currentMode);
            this.setEditMode(context.stack(), nextMode);
            context.player().displayClientMessage(
                    Component.translatable("message.fpsm.edit_tool.switch_mode",
                            getModeName(nextMode)).withStyle(ChatFormatting.DARK_AQUA),
                    true);
        }
    }

    @Override
    protected void onRightClick(ClickActionContext context) {
        if (context.isShiftKeyDown()) {
            EditMode currentMode = this.getCurrentEditMode(context.stack());
            this.modifyCurrentModeContent(context.stack(),
                    context.player(), currentMode);
        } else {
            this.doEdit(context);
        }
    }

    public abstract void doEdit(ClickActionContext context);

    private EditMode getNextEditMode(EditMode currentMode) {
        return switch (currentMode) {
            case TYPE -> EditMode.MAP;
            case MAP -> EditMode.TEAM;
            case TEAM -> EditMode.TYPE;
        };
    }

    private Component getModeName(EditMode mode) {
        return switch (mode) {
            case TYPE -> Component.translatable("message.fpsm.edit_tool.mode.type");
            case MAP -> Component.translatable("message.fpsm.edit_tool.mode.map");
            case TEAM -> Component.translatable("message.fpsm.edit_tool.mode.team");
        };
    }

    protected void clearAllSelections(ClickActionContext context) {
        this.removeTag(context.stack(), TYPE_TAG);
        this.removeTag(context.stack(), MAP_TAG);
        this.removeTag(context.stack(), TEAM_TAG);
        context.player().displayClientMessage(
                Component.translatable("message.fpsm.edit_tool.clear").withStyle(ChatFormatting.DARK_AQUA), true);
    }

    public EditMode getCurrentEditMode(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTag();
        if (!tag.contains(EDIT_MODE_TAG)) {
            setEditMode(stack, EditMode.TYPE);
            return EditMode.TYPE;
        }
        try {
            return EditMode.valueOf(tag.getString(EDIT_MODE_TAG));
        } catch (Exception e) {
            setEditMode(stack, EditMode.TYPE);
            return EditMode.TYPE;
        }
    }

    public void setEditMode(ItemStack stack, EditMode mode) {
        setTag(stack, EDIT_MODE_TAG, mode.name());
    }

    public List<String> getMapsByType(String mapType) {
        if (mapType.isEmpty()) {
            return new ArrayList<>();
        }
        return FPSMCore.getInstance().getMapNamesWithType(mapType);
    }

    public abstract List<String> getTeamsByMap(String type, String mapName);

    public List<String> getTeamsByMap(String type, String mapName, Function<ServerTeam, Boolean> checker) {
        List<String> teamList = new ArrayList<>();
        Optional<BaseMap> map = FPSMCore.getInstance().getMapByTypeWithName(type, mapName);
        map.ifPresent(baseMap -> baseMap.getMapTeams().getNormalTeams().forEach(team -> {
            if (checker.apply(team)) {
                teamList.add(team.name);
            }
        }));
        return teamList;
    }

    @Override
    public void inventoryTick(@NotNull ItemStack stack, @NotNull Level level, @NotNull Entity entity,
                              int slotId, boolean isSelected) {
        super.inventoryTick(stack, level, entity, slotId, isSelected);

        if (!stack.getOrCreateTag().contains(EDIT_MODE_TAG)) {
            this.setEditMode(stack, EditMode.TYPE);
        }
    }

    public void modifyCurrentModeContent(ItemStack stack, ServerPlayer player, EditMode currentMode) {
        switch (currentMode) {
            case TYPE -> modifyType(stack, player);
            case MAP -> {
                String currentType = this.getTag(stack, TYPE_TAG);
                if (currentType.isEmpty()) {
                    player.displayClientMessage(Component.translatable("message.fpsm.edit_tool.missing_type")
                            .withStyle(ChatFormatting.RED), true);
                    return;
                }
                modifyMap(stack, player, currentType);
            }
            case TEAM -> {
                String type = this.getTag(stack, TYPE_TAG);
                String map = this.getTag(stack, MAP_TAG);
                if (type.isEmpty() || map.isEmpty()) {
                    MutableComponent msg = type.isEmpty() ? Component.translatable("message.fpsm.edit_tool.missing_type") : Component.translatable("message.fpsm.edit_tool.missing_map");
                    player.displayClientMessage(msg
                            .withStyle(ChatFormatting.RED), true);
                    return;
                }
                modifyTeam(stack, player, type, map);
            }
        }
    }

    public void modifyType(ItemStack stack, ServerPlayer player) {
        List<String> typeList = getAvailableMapTypes();
        if (typeList.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.fpsm.edit_tool.missing_type")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }

        String currentType = this.getTag(stack, TYPE_TAG);
        int currentIndex = typeList.indexOf(currentType);
        currentIndex = (currentIndex + 1) % typeList.size();
        String newType = typeList.get(currentIndex);

        this.setTag(stack, TYPE_TAG, newType);
        player.displayClientMessage(Component.translatable("message.fpsm.edit_tool.switch_type", newType)
                .withStyle(ChatFormatting.LIGHT_PURPLE), true);

        List<String> newMapList = getMapsByType(newType);
        String currentMap = this.getTag(stack, MAP_TAG);
        if (!newMapList.contains(currentMap) && !newMapList.isEmpty()) {
            this.setTag(stack, MAP_TAG, newMapList.get(0));
            player.sendSystemMessage(Component.translatable("message.fpsm.edit_tool.map_reset_after_type_switch",
                    newMapList.get(0)).withStyle(ChatFormatting.YELLOW));
            this.removeTag(stack, TEAM_TAG);
        } else if (newMapList.isEmpty()) {
            this.removeTag(stack, MAP_TAG);
            this.removeTag(stack, TEAM_TAG);
        }
    }

    public void modifyMap(ItemStack stack, ServerPlayer player, String currentType) {
        List<String> mapList = getMapsByType(currentType);
        if (mapList.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.fpsm.edit_tool.missing_map_by_type", currentType)
                    .withStyle(ChatFormatting.RED), true);
            return;
        }

        String currentMap = this.getTag(stack, MAP_TAG);
        int currentIndex = mapList.indexOf(currentMap);
        currentIndex = (currentIndex + 1) % mapList.size();
        String newMap = mapList.get(currentIndex);

        this.setTag(stack, MAP_TAG, newMap);
        player.displayClientMessage(Component.translatable("message.fpsm.edit_tool.switch_map", newMap)
                .withStyle(ChatFormatting.AQUA), true);

        this.removeTag(stack, TEAM_TAG);
    }

    public void modifyTeam(ItemStack stack, ServerPlayer player, String type, String map) {
        List<String> teamList = getTeamsByMap(type, map);
        if (teamList.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.fpsm.edit_tool.missing_team")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }

        String currentTeam = this.getTag(stack, TEAM_TAG);
        int currentIndex = teamList.indexOf(currentTeam);
        currentIndex = (currentIndex == -1 || currentIndex + 1 >= teamList.size()) ? 0 : currentIndex + 1;
        String newTeam = teamList.get(currentIndex);

        this.setTag(stack, TEAM_TAG, newTeam);
        player.displayClientMessage(Component.translatable("message.fpsm.edit_tool.switch_team", newTeam)
                .withStyle(ChatFormatting.GREEN), true);
    }

    public String getMissingTagMessage(ItemStack stack) {
        EditToolItem editTool = (EditToolItem) stack.getItem();
        if (editTool.getTag(stack, TYPE_TAG).isEmpty()) {
            return Component.translatable("message.fpsm.edit_tool.missing_type").getString();
        }
        if (editTool.getTag(stack, MAP_TAG).isEmpty()) {
            return Component.translatable("message.fpsm.edit_tool.missing_map").getString();
        }
        if (editTool.getTag(stack, TEAM_TAG).isEmpty()) {
            return Component.translatable("message.fpsm.edit_tool.missing_team").getString();
        }
        return "";
    }

    public void switchEditMode(Player player) {
        ItemStack stack = player.getMainHandItem();

        if (!(stack.getItem() instanceof EditToolItem editTool)) return;

        EditMode currentMode = editTool.getCurrentEditMode(stack);
        EditMode newMode = switch (currentMode) {
            case TYPE -> EditMode.MAP;
            case MAP -> EditMode.TEAM;
            case TEAM -> EditMode.TYPE;
        };

        String type = editTool.getTag(stack, TYPE_TAG);
        String map = editTool.getTag(stack, MAP_TAG);

        if (newMode == EditMode.MAP && type.isEmpty()) {
            newMode = EditMode.TYPE;
        }

        if (newMode == EditMode.TEAM && (type.isEmpty() || map.isEmpty())) {
            newMode = type.isEmpty() ? EditMode.TYPE : EditMode.MAP;
        }

        editTool.setEditMode(stack, newMode);
        Component modeName = switch (newMode) {
            case TYPE -> Component.translatable("message.fpsm.edit_tool.mode.type");
            case MAP -> Component.translatable("message.fpsm.edit_tool.mode.map");
            case TEAM -> Component.translatable("message.fpsm.edit_tool.mode.team");
        };
        player.displayClientMessage(Component.translatable("message.fpsm.edit_tool.switch_mode", modeName)
                .withStyle(ChatFormatting.DARK_AQUA), true);
    }

    public Optional<ServerTeam> getPlayerCurrentTeam(ServerPlayer player) {
        return FPSMCore.getInstance().getMapByPlayer(player).flatMap(map -> map.getMapTeams().getTeamByPlayer(player));
    }

    public Optional<BaseMap> getTeamBelongingMap(ServerTeam team) {
        return FPSMCore.getInstance().getMapByTypeWithName(team.gameType, team.mapName);
    }

    @Override
    public void appendHoverText(@NotNull ItemStack pStack, @Nullable Level pLevel,
                                @NotNull List<Component> pTooltipComponents, @NotNull TooltipFlag pIsAdvanced) {
        super.appendHoverText(pStack, pLevel, pTooltipComponents, pIsAdvanced);
        EditToolItem editTool = (EditToolItem) pStack.getItem();

        EditMode currentMode = editTool.getCurrentEditMode(pStack);
        MutableComponent modeName = switch (currentMode) {
            case TYPE -> Component.translatable("message.fpsm.edit_tool.mode.type");
            case MAP -> Component.translatable("message.fpsm.edit_tool.mode.map");
            case TEAM -> Component.translatable("message.fpsm.edit_tool.mode.team");
        };
        pTooltipComponents.add(Component.translatable("tooltip.fpsm.edit_tool.selected.mode")
                .append(": ")
                .append(modeName.withStyle(ChatFormatting.BLUE)));

        pTooltipComponents.add(Component.translatable("tooltip.fpsm.separator").withStyle(ChatFormatting.GOLD));

        String selectedType = getTag(pStack, TYPE_TAG);
        String selectedMap = getTag(pStack, MAP_TAG);
        String selectedShop = getTag(pStack, TEAM_TAG);

        pTooltipComponents.add(Component.translatable("tooltip.fpsm.edit_tool.selected.type")
                .append(": ")
                .append(Component.literal(selectedType.isEmpty() ?
                        Component.translatable("tooltip.fpsm.none").getString() : selectedType)
                        .withStyle(ChatFormatting.LIGHT_PURPLE)));

        pTooltipComponents.add(Component.translatable("tooltip.fpsm.edit_tool.selected.map")
                .append(": ")
                .append(Component.literal(selectedMap.isEmpty() ?
                        Component.translatable("tooltip.fpsm.none").getString() : selectedMap)
                        .withStyle(ChatFormatting.AQUA)));

        pTooltipComponents.add(Component.translatable("tooltip.fpsm.edit_tool.selected.team")
                .append(": ")
                .append(Component.literal(selectedShop.isEmpty() ?
                        Component.translatable("tooltip.fpsm.none").getString() : selectedShop)
                        .withStyle(ChatFormatting.GREEN)));

        pTooltipComponents.add(Component.translatable("tooltip.fpsm.separator").withStyle(ChatFormatting.GOLD));
        pTooltipComponents.add(Component.translatable("tooltip.fpsm.usage").withStyle(ChatFormatting.GRAY));
    }

    @SubscribeEvent
    public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        Player player = event.getEntity();
        Level level = player.level();
        if (!level.isClientSide) return;
        ItemStack stack = player.getMainHandItem();

        if (!(stack.getItem() instanceof EditToolItem)) return;

        FPSMatch.sendToServer(new EditToolClickC2SPacket(
                ClickAction.LEFT_CLICK,
                player.isShiftKeyDown()));
    }
}
