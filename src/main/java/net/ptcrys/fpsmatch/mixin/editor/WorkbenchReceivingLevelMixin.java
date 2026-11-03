package net.ptcrys.fpsmatch.mixin.editor;

import net.ptcrys.fpsmatch.editor.common.client.workbench.WorkbenchWorldSession;

import net.minecraft.client.multiplayer.ClientPacketListener;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class WorkbenchReceivingLevelMixin {

    @Inject(method = "handleSetSpawn", at = @At("TAIL"))
    private void fpsmatch$editorReceivedSpawn(CallbackInfo callback) {
        WorkbenchWorldSession.receivedSpawn();
    }
}
