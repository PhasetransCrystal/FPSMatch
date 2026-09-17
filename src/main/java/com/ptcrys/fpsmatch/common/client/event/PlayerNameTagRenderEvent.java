package com.ptcrys.fpsmatch.common.client.event;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.Event;

import com.mojang.blaze3d.vertex.PoseStack;

public class PlayerNameTagRenderEvent extends Event {

    private final Player player;
    private final PoseStack poseStack;
    private final MultiBufferSource bufferSource;
    private final int packedLight;
    private final float partialTick;

    private PlayerNameTagRenderEvent(Player player, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, float partialTick) {
        this.player = player;
        this.poseStack = poseStack;
        this.bufferSource = bufferSource;
        this.packedLight = packedLight;
        this.partialTick = partialTick;
    }

    public Player getPlayer() {
        return player;
    }

    public PoseStack getPoseStack() {
        return poseStack;
    }

    public MultiBufferSource getBufferSource() {
        return bufferSource;
    }

    public int getPackedLight() {
        return packedLight;
    }

    public float getPartialTick() {
        return partialTick;
    }

    public static class Pre extends PlayerNameTagRenderEvent {

        public Pre(Player player, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, float partialTick) {
            super(player, poseStack, bufferSource, packedLight, partialTick);
        }

        @Override
        public boolean isCancelable() {
            return true;
        }
    }

    public static class Post extends PlayerNameTagRenderEvent {

        public Post(Player player, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, float partialTick) {
            super(player, poseStack, bufferSource, packedLight, partialTick);
        }

        @Override
        public boolean isCancelable() {
            return false;
        }
    }
}
