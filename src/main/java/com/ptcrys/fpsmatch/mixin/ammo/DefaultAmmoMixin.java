package com.ptcrys.fpsmatch.mixin.ammo;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraftforge.registries.ForgeRegistries;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.ptcrys.fpsmatch.compat.IPassThroughEntity;
import com.ptcrys.fpsmatch.mixin.accessor.FPSMClipContextAccessor;
import com.tacz.guns.config.common.AmmoConfig;
import com.tacz.guns.init.ModBlocks;
import com.tacz.guns.util.block.BlockRayTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.BiFunction;
import java.util.function.Function;

@Mixin(value = BlockRayTrace.class, remap = false)
public class DefaultAmmoMixin {

    @WrapOperation(
                   method = "rayTraceBlocks",
                   at = @At(
                            value = "INVOKE",
                            target = "Lcom/tacz/guns/util/block/BlockRayTrace;performRayTrace(Lnet/minecraft/world/level/ClipContext;Ljava/util/function/BiFunction;Ljava/util/function/Function;)Ljava/lang/Object;"))
    private static Object fpsmatch$markPassedWallWhenBlockStepIsIgnored(ClipContext context,
                                                                        BiFunction<ClipContext, BlockPos, Object> hitFunction,
                                                                        Function<ClipContext, Object> missFactory,
                                                                        Operation<Object> original,
                                                                        Level level,
                                                                        ClipContext originalContext) {
        BiFunction<ClipContext, BlockPos, Object> trackingHitFunction = (rayContext, blockPos) -> {
            Object result = hitFunction.apply(rayContext, blockPos);
            if (result == null && fpsmatch$isPassThroughWallBlock(level, blockPos)) {
                fpsmatch$markPassedWall(rayContext);
            }
            return result;
        };

        return original.call(context, trackingHitFunction, missFactory);
    }

    @Unique
    private static boolean fpsmatch$isPassThroughWallBlock(Level level, BlockPos blockPos) {
        BlockState state = level.getBlockState(blockPos);
        if (state.isAir()) return false;

        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (blockId != null && AmmoConfig.PASS_THROUGH_BLOCKS.get().contains(blockId.toString())) {
            return true;
        }

        return state.is(ModBlocks.BULLET_IGNORE_BLOCKS);
    }

    @Unique
    private static void fpsmatch$markPassedWall(ClipContext context) {
        FPSMClipContextAccessor accessor = (FPSMClipContextAccessor) context;
        if (!(accessor.getCollisionContext() instanceof EntityCollisionContext entityCollisionContext)) return;
        if (!(entityCollisionContext.getEntity() instanceof IPassThroughEntity entity)) return;
        entity.fpsmatch$setThroughWall(true);
    }
}
