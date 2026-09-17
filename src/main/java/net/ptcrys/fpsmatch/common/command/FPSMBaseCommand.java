package net.ptcrys.fpsmatch.common.command;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.event.FPSMReloadEvent;
import net.ptcrys.fpsmatch.common.shop.functional.ChangeShopItemModule;
import net.ptcrys.fpsmatch.compat.gun.GunCompatManager;
import net.ptcrys.fpsmatch.compat.gun.GunDataDTO;
import net.ptcrys.fpsmatch.compat.gun.IGunProvider;
import net.ptcrys.fpsmatch.core.FPSMCore;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import java.util.Optional;

import static net.ptcrys.fpsmatch.common.command.FPSMCommand.*;

public class FPSMBaseCommand {

    public static <T extends ArgumentBuilder<CommandSourceStack, T>> ArgumentBuilder<CommandSourceStack, T> init(ArgumentBuilder<CommandSourceStack, T> builder) {        // 获取HelpManager实例
        FPSMHelpManager helpManager = FPSMHelpManager.getInstance();

        // 注册基本命令帮助
        helpManager.registerCommandHelp("fpsm save", Component.translatable("commands.fpsm.help.basic.save"));
        helpManager.registerCommandHelp("fpsm reload", Component.translatable("commands.fpsm.help.basic.reload"));
        helpManager.registerCommandHelp("fpsm debug", Component.translatable("commands.fpsm.help.basic.debug"));

        // 注册tacz命令帮助
        helpManager.registerCommandHelp("fpsm tacz dummy", Component.translatable("commands.fpsm.help.tacz.dummy"));
        helpManager.registerCommandParameters("fpsm tacz dummy", "*amount");

        // 注册listener_module命令帮助
        helpManager.registerCommandHelp("fpsm listener_module");
        helpManager.registerCommandHelp("fpsm listener_module add", Component.translatable("commands.fpsm.help.listener.add"));
        helpManager.registerCommandHelp("fpsm listener_module add change_item_module", Component.translatable("commands.fpsm.help.listener.add_change_item"), Component.translatable("commands.fpsm.help.listener.add_change_item.hover"));
        helpManager.registerCommandParameters("fpsm listener_module add change_item_module", "*changed_cost", "*default_cost");

        return builder
                .then(Commands.literal("save").requires(source -> source.hasPermission(2)).executes(FPSMBaseCommand::handleSave))
                .then(Commands.literal("reload").requires(source -> source.hasPermission(2)).executes(FPSMBaseCommand::handleReLoad))
                .then(Commands.literal("debug").requires(source -> source.hasPermission(2)).executes(FPSMBaseCommand::handleDebug))
                .then(Commands.literal("tacz").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("dummy")
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                        .executes(FPSMBaseCommand::handleSetDummyAmmoAmount))))
                .then(Commands.literal("listener_module").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("add")
                                .then(Commands.literal("change_item_module")
                                        .then(Commands.argument("changed_cost", IntegerArgumentType.integer(1))
                                                .then(Commands.argument("default_cost", IntegerArgumentType.integer(1))
                                                        .executes(FPSMBaseCommand::handleChangeItemModule))))));
    }

    // 命令处理方法
    private static int handleSave(CommandContext<CommandSourceStack> context) {
        FPSMCore.getInstance().getFPSMDataManager().saveAllData();
        sendSuccess(context.getSource(), Component.translatable("commands.fpsm.save.success"));
        return 1;
    }

    private static int handleReLoad(CommandContext<CommandSourceStack> context) {
        MinecraftForge.EVENT_BUS.post(new FPSMReloadEvent(FPSMCore.getInstance()));
        sendSuccess(context.getSource(), Component.translatable("commands.fpsm.reload.success"));
        return 1;
    }

    private static int handleDebug(CommandContext<CommandSourceStack> context) {
        sendSuccess(context.getSource(), Component.translatable("commands.fpsm.debug.success", FPSMatch.switchDebug()));
        return 1;
    }

    /**
     * 处理设置虚拟弹药数量
     */
    private static int handleSetDummyAmmoAmount(CommandContext<CommandSourceStack> context) {
        try {
            ServerPlayer player = context.getSource().getPlayerOrException();
            int amount = IntegerArgumentType.getInteger(context, "amount");

            ItemStack itemStack = player.getMainHandItem();
            if (itemStack.isEmpty()) {
                sendFailure(context.getSource(), Component.translatable("commands.fpsm.tacz.dummy.amount.empty_hand"));
                return 0;
            }

            IGunProvider provider = GunCompatManager.findProvider(itemStack);
            if (provider.isGun(itemStack)) {
                Optional<GunDataDTO> gunData = provider.getGunData(itemStack);
                if (gunData.isPresent()) {
                    // 设置虚拟弹药
                    provider.useDummyAmmo(itemStack);
                    provider.setMaxDummyAmmo(itemStack, amount);
                    provider.setDummyAmmo(itemStack, amount);
                    provider.setCurrentAmmo(itemStack, gunData.get().getAmmoAmount());

                    sendSuccess(context.getSource(), Component.translatable("commands.fpsm.tacz.dummy.amount.success",
                            itemStack.getDisplayName(), amount));
                    return 1;
                } else {
                    sendFailure(context.getSource(), Component.translatable("commands.fpsm.tacz.dummy.amount.invalid_gun"));
                    return 0;
                }
            } else {
                sendFailure(context.getSource(), Component.translatable("commands.fpsm.tacz.dummy.amount.not_gun"));
                return 0;
            }
        } catch (CommandSyntaxException e) {
            sendFailure(context.getSource(), Component.translatable("commands.fpsm.only.player"));
            return 0;
        }
    }

    private static int handleChangeItemModule(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player player = getPlayerOrFail(context);
        int defaultCost = IntegerArgumentType.getInteger(context, "default_cost");
        int changedCost = IntegerArgumentType.getInteger(context, "changed_cost");

        ItemStack changedItem = player.getMainHandItem().copy();
        ItemStack defaultItem = player.getOffhandItem().copy();

        ChangeShopItemModule module = new ChangeShopItemModule(defaultItem, defaultCost, changedItem, changedCost);
        FPSMCore.getInstance().getListenerModuleManager().addListenerType(module);

        sendSuccess(context.getSource(), Component.translatable("commands.fpsm.listener.add.success", module.getName()));
        return 1;
    }
}
