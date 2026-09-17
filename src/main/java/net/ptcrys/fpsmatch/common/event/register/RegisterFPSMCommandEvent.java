package net.ptcrys.fpsmatch.common.event.register;

import net.ptcrys.fpsmatch.common.command.FPSMHelpManager;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.eventbus.api.Event;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

public class RegisterFPSMCommandEvent extends Event {

    private final LiteralArgumentBuilder<CommandSourceStack> builder;
    private final FPSMHelpManager helper;
    private final CommandBuildContext context;

    public RegisterFPSMCommandEvent(LiteralArgumentBuilder<CommandSourceStack> builder, CommandBuildContext context, FPSMHelpManager helper) {
        this.builder = builder;
        this.context = context;
        this.helper = helper;
    }

    public void addChild(LiteralArgumentBuilder<CommandSourceStack> child) {
        var requirement = child.getRequirement();
        child.requires(source -> source.hasPermission(2) && requirement.test(source));
        this.builder.then(child);
    }

    /** Explicit opt-in for commands available to ordinary players. */
    public void addPlayerChild(LiteralArgumentBuilder<CommandSourceStack> child) {
        this.builder.then(child);
    }

    public CommandBuildContext getContext() {
        return this.context;
    }

    public LiteralArgumentBuilder<CommandSourceStack> getTree() {
        return this.builder;
    }

    /**
     * 注册命令帮助信息
     * 
     * @param commandPath 命令路径，如 "fpsm map modify capability mycap"
     * @param description 命令描述
     */
    public void registerHelp(String commandPath, MutableComponent description) {
        this.helper.registerCommandHelp(commandPath, description);
    }

    /**
     * 注册命令帮助信息
     * 
     * @param commandPath    命令路径，如 "fpsm map modify capability mycap"
     * @param descriptionKey 命令描述的语言键
     */
    public void registerHelp(String commandPath, String descriptionKey) {
        this.helper.registerCommandHelp(commandPath, descriptionKey);
    }

    /**
     * 注册命令参数
     * 
     * @param commandPath 命令路径，如 "fpsm map modify capability mycap"
     * @param parameters  参数列表，如 "amount"
     */
    public void registerParameters(String commandPath, String... parameters) {
        this.helper.registerCommandParameters(commandPath, parameters);
    }
}
