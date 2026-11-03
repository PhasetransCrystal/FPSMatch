package net.ptcrys.fpsmatch.mixin.editor;

import net.ptcrys.fpsmatch.editor.common.client.workbench.WorkbenchWorldSession;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.Connection;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelStorageSource;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Routes only the explicitly attached workbench session; ordinary Minecraft remains unchanged. */
@Mixin(Minecraft.class)
public abstract class WorkbenchWorldSessionMixin implements WorkbenchWorldSession.MinecraftAccess {

    @Shadow
    private Connection pendingConnection;

    @Override
    public Connection fpsmatch$pendingConnection() {
        return pendingConnection;
    }

    @Inject(method = "clearLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"), cancellable = true)
    private void fpsmatch$editorRetainServer(Screen screen, CallbackInfo callback) {
        if (WorkbenchWorldSession.interceptClearLevel()) callback.cancel();
    }

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void fpsmatch$editorChild(Screen next, CallbackInfo callback) {
        if (WorkbenchWorldSession.routeScreen(next)) callback.cancel();
    }

    @WrapMethod(method = "doWorldLoad")
    private void fpsmatch$editorLoadScope(String id, LevelStorageSource.LevelStorageAccess access, PackRepository packs, WorldStem stem, boolean fresh, Operation<Void> original) {
        var session = WorkbenchWorldSession.nativeLoadStarted();
        try {
            original.call(id, access, packs, stem, fresh);
        } catch (RuntimeException error) {
            if (session == null) throw error;
            // Before server ownership is established, the native asynchronous chain would leak
            // the opened world lock into its delayed-crash path. Close that failed attempt here.
            if (Minecraft.getInstance().getSingleplayerServer() == null) {
                try {
                    stem.close();
                } catch (RuntimeException cleanup) {
                    error.addSuppressed(cleanup);
                }
                try {
                    access.close();
                } catch (Exception cleanup) {
                    error.addSuppressed(cleanup);
                }
            }
            WorkbenchWorldSession.nativeLoadFailed(session, error);
        } finally {
            WorkbenchWorldSession.nativeLoadFinished(session);
        }
    }
}
