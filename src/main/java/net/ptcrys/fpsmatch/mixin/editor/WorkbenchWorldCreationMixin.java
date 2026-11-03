package net.ptcrys.fpsmatch.mixin.editor;

import net.ptcrys.fpsmatch.editor.common.client.workbench.WorkbenchWorldSession;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.logging.LogUtils;
import org.spongepowered.asm.mixin.Mixin;

/** Retains the world lock until native creation either starts its server or fails. */
@Mixin(WorldOpenFlows.class)
public abstract class WorkbenchWorldCreationMixin {

    @WrapMethod(method = "createLevelFromExistingSettings")
    private void fpsmatch$editorCreateScope(LevelStorageSource.LevelStorageAccess access, ReloadableServerResources resources, LayeredRegistryAccess<RegistryLayer> registries, WorldData data, Operation<Void> original) {
        var session = WorkbenchWorldSession.nativeLoadStarted();
        String id = access.getLevelId();
        try {
            if (!WorkbenchWorldSession.nativeCreationAllowed(session, id)) {
                Exception closeFailure = null;
                try {
                    access.close();
                } catch (Exception error) {
                    closeFailure = error;
                    LogUtils.getLogger().warn("Failed to close discarded editor world creation", error);
                }
                WorkbenchWorldSession.nativeCreationDiscarded(session, id, closeFailure);
                return;
            }
            original.call(access, resources, registries, data);
        } catch (RuntimeException error) {
            if (session == null) throw error;
            // doWorldLoad has its own stem/access cleanup. This outer scope also covers
            // pack repository/resource setup that can fail before doWorldLoad is called.
            if (Minecraft.getInstance().getSingleplayerServer() == null) {
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
