package net.ptcrys.fpsmatch.common.capability.team;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.command.FPSMCommand;
import net.ptcrys.fpsmatch.common.command.FPSMHelpManager;
import net.ptcrys.fpsmatch.common.event.PlayerObtainItemEvent;
import net.ptcrys.fpsmatch.core.FPSMCore;
import net.ptcrys.fpsmatch.core.capability.FPSMCapability;
import net.ptcrys.fpsmatch.core.capability.FPSMCapabilityManager;
import net.ptcrys.fpsmatch.core.capability.team.TeamCapability;
import net.ptcrys.fpsmatch.core.data.PlayerData;
import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.ptcrys.fpsmatch.core.persistence.RuntimeItemStackCodec;
import net.ptcrys.fpsmatch.core.shop.runtime.RuntimeItemException;
import net.ptcrys.fpsmatch.core.shop.runtime.RuntimeKitTemplates;
import net.ptcrys.fpsmatch.core.team.BaseTeam;
import net.ptcrys.fpsmatch.core.team.ServerTeam;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.util.FPSMUtil;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;

import com.google.gson.JsonElement;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.serialization.Codec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 队伍初始装备能力：封装单个队伍的初始装备存储、管理、发放逻辑
 */
public class StartKitsCapability extends TeamCapability implements FPSMCapability.Savable<List<ItemStack>> {

    private final ArrayList<ItemStack> teamKits = new ArrayList<>();
    private RuntimeKitTemplates runtimeKits;
    private boolean managed;
    private boolean destroyed;

    public StartKitsCapability(BaseTeam team) {
        super(team);
    }

    public void useRuntimeKits(RuntimeKitTemplates kits) {
        if (destroyed) throw runtimeFailure("closed");
        if (managed || !teamKits.isEmpty() || kits == null) throw invalidSlot();
        kits.requireAvailable();
        runtimeKits = kits;
        managed = true;
    }

    public Optional<RuntimeKitTemplates> runtimeKits() {
        if (managed && destroyed) throw runtimeFailure("closed");
        RuntimeKitTemplates kits = runtimeKits;
        if (kits != null) kits.requireAvailable();
        return Optional.ofNullable(kits);
    }

    private void requireLegacy() {
        if (managed) throw invalidSlot();
    }

    private static RuntimeItemException invalidSlot() {
        return runtimeFailure("invalid_slot");
    }

    private static RuntimeItemException runtimeFailure(String reason) {
        return new RuntimeItemException(new Problem(Problem.Severity.ERROR,
                new EditorId("editor", "runtime_items/" + reason), "runtime_items",
                new TextContent.Message("editor.runtime_items." + reason, List.of())));
    }

    /**
     * 注册能力到全局管理器
     */
    public static void register() {
        FPSMCapabilityManager.register(FPSMCapabilityManager.CapabilityType.TEAM, StartKitsCapability.class, new Factory<>() {

            @Override
            public StartKitsCapability create(BaseTeam team) {
                if (team instanceof ServerTeam serverTeam) {
                    return new StartKitsCapability(serverTeam);
                } else {
                    throw new IllegalArgumentException("Team is client side");
                }
            }

            @Override
            public Command command() {
                return new StartKitsCommand();
            }
        });
    }

    /**
     * 获取当前队伍的初始装备列表
     */
    public ArrayList<ItemStack> getTeamKits() {
        requireLegacy();
        return new ArrayList<>(teamKits); // 返回副本，避免外部直接修改
    }

    /**
     * 为当前队伍添加单个初始装备
     */
    public void addKit(ItemStack itemStack) {
        requireLegacy();
        if (!itemStack.isEmpty()) {
            teamKits.add(itemStack.copy());
        }
    }

    /**
     * 为当前队伍添加多个初始装备
     */
    public void addKits(List<ItemStack> itemStacks) {
        requireLegacy();
        itemStacks.forEach(this::addKit);
    }

    /**
     * 设置当前队伍的初始装备列表
     */
    public void setTeamKits(ArrayList<ItemStack> itemStacks) {
        requireLegacy();
        clearTeamKits();
        addKits(itemStacks);
    }

    /**
     * 清空当前队伍的初始装备
     */
    public void clearTeamKits() {
        requireLegacy();
        teamKits.clear();
    }

    /**
     * 从当前队伍的初始装备中移除特定物品
     */
    public boolean removeItem(ItemStack itemStack) {
        requireLegacy();
        AtomicBoolean flag = new AtomicBoolean(false);
        teamKits.removeIf(kit -> {
            if (kit.is(itemStack.getItem())) {
                kit.shrink(itemStack.getCount());
                flag.set(true);
                return kit.getCount() <= 0;
            }
            return false;
        });
        return flag.get();
    }

    /**
     * 为指定玩家发放当前队伍的初始装备
     */
    public void givePlayerKits(@NotNull ServerPlayer player) {
        if (managed && destroyed) throw runtimeFailure("closed");
        // 验证玩家是否属于当前队伍（避免跨队伍发放）
        if (!team.hasPlayer(player.getUUID())) {
            FPSMatch.LOGGER.info("givePlayerKits: player {} not in team {}", player.getDisplayName().getString(), team.name);
            return;
        }

        RuntimeKitTemplates managed = runtimeKits;
        if (managed != null) managed.requireAvailable();
        player.getInventory().clearContent();
        if (managed != null) {
            for (int index = 0, count = managed.templates().size(); index < count; index++) {
                if (managed.isEmpty(index)) continue;
                ItemStack delivery = managed.createDelivery(index);
                // preparePlayerItem posts an external event: ownership transfers at its entry.
                ItemStack prepared = FPSMUtil.preparePlayerItem(player, delivery);
                if (prepared.getItem() instanceof ArmorItem armorItem) {
                    MinecraftForge.EVENT_BUS.post(new PlayerObtainItemEvent(player, prepared));
                    player.setItemSlot(armorItem.getEquipmentSlot(), prepared);
                } else {
                    MinecraftForge.EVENT_BUS.post(new PlayerObtainItemEvent(player, prepared));
                    player.getInventory().add(prepared);
                }
            }
        } else teamKits.forEach(itemStack -> {
            ItemStack copy = FPSMUtil.preparePlayerItem(player, itemStack.copy());
            if (copy.getItem() instanceof ArmorItem armorItem) {
                MinecraftForge.EVENT_BUS.post(new PlayerObtainItemEvent(player, copy));
                player.setItemSlot(armorItem.getEquipmentSlot(), copy);
            } else {
                FPSMUtil.fixGunItem(copy);
                MinecraftForge.EVENT_BUS.post(new PlayerObtainItemEvent(player, copy));
                player.getInventory().add(copy);
            }
        });

        player.inventoryMenu.broadcastChanges();
        player.inventoryMenu.slotsChanged(player.getInventory());
        FPSMUtil.sortPlayerInventory(player);
    }

    /**
     * 为当前队伍的所有在线玩家发放初始装备
     */
    public void giveAllPlayersKits() {
        if (managed && destroyed) throw runtimeFailure("closed");
        BaseMap map = ((ServerTeam) team).getMap();
        if (map == null) return;

        // 遍历队伍所有玩家，发放装备
        for (PlayerData data : team.getPlayersData()) {
            data.getPlayer().ifPresent(this::givePlayerKits);
        }
    }

    @Override
    public void destroy() {
        destroyed = true;
        runtimeKits = null;
        teamKits.clear();
    }

    @Override
    public Codec<List<ItemStack>> codec() {
        return RuntimeItemStackCodec.owned(RuntimeItemStackCodec.CODEC.listOf());
    }

    @Override
    public List<ItemStack> write(List<ItemStack> value) {
        requireLegacy();
        teamKits.clear();
        for (ItemStack itemStack : value) {
            if (!itemStack.isEmpty()) {
                teamKits.add(itemStack);
            }
        }
        return teamKits;
    }

    @Override
    public @Nullable List<ItemStack> read() {
        requireLegacy();
        return teamKits;
    }

    @Override
    public JsonElement toJson() {
        RuntimeKitTemplates kits = runtimeKits;
        return kits == null ? FPSMCapability.Savable.super.toJson() : kits.toJson();
    }

    @Override
    public JsonElement toJson(List<ItemStack> value) {
        requireLegacy();
        return FPSMCapability.Savable.super.toJson(value);
    }

    @Override
    public void decode(JsonElement json) {
        requireLegacy();
        FPSMCapability.Savable.super.decode(json);
    }

    protected static class StartKitsCommand implements Factory.Command {

        @Override
        public String getName() {
            return "kits";
        }

        @Override
        public LiteralArgumentBuilder<CommandSourceStack> builder(LiteralArgumentBuilder<CommandSourceStack> builder, CommandBuildContext context) {
            return builder
                    .then(Commands.literal("add")
                            .executes(StartKitsCommand::handleAddKitWithoutItem)
                            .then(Commands.argument("item", ItemArgument.item(context))
                                    .executes(c -> handleAddKitWithItem(c, 1))
                                    .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                            .executes(c -> handleAddKitWithItem(c, IntegerArgumentType.getInteger(c, "amount"))))))
                    .then(Commands.literal("clear")
                            .executes(StartKitsCommand::handleClearKits))
                    .then(Commands.literal("list")
                            .executes(StartKitsCommand::handleListKits));
        }

        @Override
        public void help(FPSMHelpManager helper) {
            helper.registerCommandHelp(FPSMHelpManager.withTeamCapability("kits"));
            helper.registerCommandHelp(FPSMHelpManager.withTeamCapability("kits add"), Component.translatable("commands.fpsm.help.capability.kits.add"));
            helper.registerCommandHelp(FPSMHelpManager.withTeamCapability("kits clear"), Component.translatable("commands.fpsm.help.capability.kits.clear"));
            helper.registerCommandHelp(FPSMHelpManager.withTeamCapability("kits list"), Component.translatable("commands.fpsm.help.capability.kits.list"));
            helper.registerCommandParameters(FPSMHelpManager.withTeamCapability("kits add"), "item", "amount");
        }

        /**
         * 处理添加初始装备（无物品参数）
         */
        private static int handleAddKitWithoutItem(CommandContext<CommandSourceStack> context) {
            ServerPlayer player = context.getSource().getPlayer();
            if (player == null) {
                context.getSource().sendFailure(Component.translatable("commands.fpsm.only.player"));
                return 0;
            }

            Optional<StartKitsCapability> capability = FPSMCommand.getTeamCapability(context, StartKitsCapability.class);
            if (capability.isEmpty() || rejectManagedAdd(context, capability.get())) return 0;

            ItemStack itemStack = player.getMainHandItem().copy();
            return addLegacyKit(context, capability.get(), itemStack, true);
        }

        /**
         * 处理添加初始装备（有物品参数）
         */
        private static int handleAddKitWithItem(CommandContext<CommandSourceStack> context, int count) {
            Optional<StartKitsCapability> capability = FPSMCommand.getTeamCapability(context, StartKitsCapability.class);
            if (capability.isEmpty()) {
                context.getSource().sendFailure(Component.translatable("commands.fpsm.capability.missing", StartKitsCapability.class.getSimpleName()));
                return 0;
            }
            if (rejectManagedAdd(context, capability.get())) return 0;

            ItemStack itemStack;
            try {
                itemStack = ItemArgument.getItem(context, "item").createItemStack(count, false);
            } catch (Exception e) {
                context.getSource().sendFailure(Component.translatable("commands.fpsm.modify.kits.add.failed"));
                return 0;
            }
            return addLegacyKit(context, capability.get(), itemStack, false);
        }

        private static boolean rejectManagedAdd(CommandContext<CommandSourceStack> context, StartKitsCapability capability) {
            if (!capability.managed) return false;
            context.getSource().sendFailure(Component.translatable("editor.runtime_items.invalid_slot"));
            return true;
        }

        private static int addLegacyKit(CommandContext<CommandSourceStack> context, StartKitsCapability capability,
                                        ItemStack itemStack, boolean rejectEmpty) {
            Throwable failure = null;
            try {
                if (rejectEmpty && itemStack.isEmpty()) {
                    context.getSource().sendFailure(Component.translatable("commands.fpsm.modify.kits.add.empty"));
                    return 0;
                }
                capability.addKit(itemStack);
                FPSMCore.getInstance().getFPSMDataManager().saveAllData();
                Component message = Component.translatable("commands.fpsm.modify.kits.add.success",
                        itemStack.getDisplayName().copy(), capability.team.name);
                context.getSource().sendSuccess(() -> message, true);
                return 1;
            } catch (RuntimeException | Error thrown) {
                failure = thrown;
                throw thrown;
            } finally {
                if (itemStack != ItemStack.EMPTY) {
                    try {
                        itemStack.invalidateCaps();
                    } catch (RuntimeException | Error cleanup) {
                        if (failure == null) throw cleanup;
                        if (failure != cleanup) failure.addSuppressed(cleanup);
                    }
                }
            }
        }

        /**
         * 处理清空初始装备
         */
        private static int handleClearKits(CommandContext<CommandSourceStack> context) {
            return FPSMCommand.getTeamCapability(context, StartKitsCapability.class).map(capability -> {
                capability.clearTeamKits();
                FPSMCore.getInstance().getFPSMDataManager().saveAllData();
                context.getSource().sendSuccess(() -> Component.translatable("commands.fpsm.modify.kits.clear.success",
                        capability.team.name), true);
                return 1;
            }).orElseGet(() -> {
                context.getSource().sendFailure(Component.translatable("commands.fpsm.capability.missing", StartKitsCapability.class.getSimpleName()));
                return 0;
            });
        }

        /**
         * 处理列出初始装备
         */
        private static int handleListKits(CommandContext<CommandSourceStack> context) {
            return FPSMCommand.getTeamCapability(context, StartKitsCapability.class).map(capability -> {
                Optional<RuntimeKitTemplates> managed = capability.runtimeKits();
                int count;
                if (managed.isPresent()) {
                    List<Component> names = managed.get().displayNames();
                    names.forEach(name -> context.getSource().sendSuccess(() -> name, true));
                    count = names.size();
                } else {
                    List<ItemStack> kits = capability.getTeamKits();
                    kits.forEach(itemStack -> context.getSource().sendSuccess(itemStack::getDisplayName, true));
                    count = kits.size();
                }
                context.getSource().sendSuccess(() -> Component.translatable("commands.fpsm.modify.kits.list.success",
                        capability.getHolder().name, count), true);
                return 1;
            }).orElseGet(() -> {
                context.getSource().sendFailure(Component.translatable("commands.fpsm.capability.missing", StartKitsCapability.class.getSimpleName()));
                return 0;
            });
        }
    };
}
