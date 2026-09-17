package com.ptcrys.fpsmatch.core.capability;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.ptcrys.fpsmatch.common.command.FPSMHelpManager;
import com.ptcrys.fpsmatch.common.event.register.RegisterFPSMCommandEvent;
import com.ptcrys.fpsmatch.core.persistence.DataPersistenceException;
import org.jetbrains.annotations.Nullable;

/**
 * 基础能力接口，定义所有能力的通用规范
 * 
 * @param <H> 能力的持有者类型（如BaseTeam、BaseMap）
 * @apiNote 更多功能拓展请见：
 * @see Factory 注册在Manager的能力工厂 - 当command方法不为null时自动在指令树注册命令
 * @see CapabilitySynchronizable 允许网络同步整个能力到客户端的功能
 * @see DataSynchronizable 允许能力数据同步到客户端的功能
 * @see Savable 允许持久化数据
 * @apiNote FPSMCapability中的
 */
public abstract class FPSMCapability<H> {

    public void tick() {};

    /**
     * 初始化能力（持有者添加能力时调用）
     */
    public void init() {}

    /**
     * 重置能力状态
     */
    public void reset() {}

    /**
     * 销毁能力（持有者移除能力时调用）
     */
    public void destroy() {}

    /**
     * 获取能力的持有者
     */
    public abstract H getHolder();

    /**
     * 不允许在CapabilityMap中移除此能力
     */
    public boolean isImmutable() {
        return false;
    };

    /**
     * 获取能力名称（默认使用类名）
     */
    public String getName() {
        return this.getClass().getSimpleName();
    }

    /**
     * 能力工厂接口，负责创建能力实例
     * 
     * @param <H> 持有者类型
     * @param <T> 能力类型
     */
    @FunctionalInterface
    public interface Factory<H, T extends FPSMCapability<H>> {

        /**
         * 是否在初始化Holder的CapabilityMap时自动添加这个能力
         */
        default boolean isOriginal() {
            return false;
        }

        /**
         * 创建能力实例
         * 
         * @param holder 能力的持有者
         */
        T create(H holder);

        /**
         * 获取能力对应的命令（可选）
         */
        default Command command() {
            return null;
        }

        /**
         * 能力相关的命令接口
         * 
         * @see RegisterFPSMCommandEvent 命令注册相关
         */
        interface Command {

            String getName();

            LiteralArgumentBuilder<CommandSourceStack> builder(LiteralArgumentBuilder<CommandSourceStack> builder, CommandBuildContext context);

            default void help(FPSMHelpManager helper) {}
        }
    }

    public interface Savable<T> {

        String getName();

        Codec<T> codec();   // 获取数据的编解码器

        T write(T value);

        @Nullable
        T read();

        default JsonElement toJson() {
            T result = read();
            return toJson(result);
        }

        default void decode(JsonElement json) {
            if (json == null || json.isJsonNull()) {
                return;
            }

            if (json.isJsonPrimitive()) {
                JsonPrimitive primitive = json.getAsJsonPrimitive();
                if (primitive.isString() && primitive.getAsString().isEmpty()) {
                    return;
                }
            }

            write(codec().decode(JsonOps.INSTANCE, json).getOrThrow(false, e -> {
                throw new DataPersistenceException("Error decoding data from JSON", e);
            }).getFirst());
        }

        default JsonElement toJson(T value) {
            if (value == null) {
                return new JsonPrimitive("");
            }

            return codec().encodeStart(JsonOps.INSTANCE, value).getOrThrow(false, e -> {
                throw new DataPersistenceException("Error encoding data to JSON", e);
            });
        }
    }

    /**
     * 支持网络同步接口
     * 
     * @apiNote 你不应该在初始化能力时对Holder进行任何操作
     * @see FPSMCapability 使用init()方法对holder进行操作;
     */
    public interface CapabilitySynchronizable {

        boolean isDirty();

        void readFromBuf(FriendlyByteBuf buf);

        void writeToBuf(FriendlyByteBuf buf);

        /** A targeted initial snapshot must not consume changes owed to existing viewers. */
        default void onBroadcast() {}
    }

    public interface DataSynchronizable {

        default void sync() {}

        default void sync(Player player) {}
    }
}
