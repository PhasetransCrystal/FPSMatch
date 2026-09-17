package com.ptcrys.fpsmatch.common.client.camera;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import com.mojang.logging.LogUtils;
import com.ptcrys.fpsmatch.common.camera.*;
import com.ptcrys.fpsmatch.common.client.spec.*;
import org.slf4j.Logger;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Client-thread only. Base spectator state keeps updating while a temporary scene owns the view. */
public final class CameraDirector {

    public static final int DEATH_PRIORITY = 100;
    public static final int CINEMATIC_PRIORITY = 200;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final CameraOwnership<CameraSession> ownership = new CameraOwnership<>();
    private static CameraFrame frame;
    private static boolean resetting;

    private CameraDirector() {}

    public static CameraSession play(String owner, int priority, CameraRig rig, CameraPolicy policy,
                                     SequenceClock clock, BooleanSupplier valid, Consumer<CameraEndReason> onEnd) {
        return play(owner, priority, rig, policy, clock, valid, Integer.MAX_VALUE, CameraLifetime.PLAYER_LIFE, onEnd);
    }

    public static CameraSession play(String owner, int priority, CameraRig rig, CameraPolicy policy,
                                     SequenceClock clock, BooleanSupplier valid, CameraLifetime lifetime, Consumer<CameraEndReason> onEnd) {
        return play(owner, priority, rig, policy, clock, valid, Integer.MAX_VALUE, lifetime, onEnd);
    }

    public static CameraSession playSequence(String owner, int priority, CameraSequence sequence, CameraPolicy policy,
                                             SequenceClock clock, BooleanSupplier valid, Consumer<CameraEndReason> onEnd) {
        return play(owner, priority, sequence, policy, clock, valid, sequence.duration(), CameraLifetime.SCENE, onEnd);
    }

    private static CameraSession play(String owner, int priority, CameraRig rig, CameraPolicy policy,
                                      SequenceClock clock, BooleanSupplier valid, int duration, CameraLifetime lifetime, Consumer<CameraEndReason> onEnd) {
        Minecraft mc = Minecraft.getInstance();
        if (resetting || mc.level == null || mc.player == null || !valid.getAsBoolean()) return null;
        CameraSession session = new CameraSession(rig, policy, clock, valid, mc.level, duration, lifetime);
        session.lease = ownership.acquire(owner, priority, session, reason -> {
            session.clock.cancel();
            try {
                onEnd.accept(reason);
            } catch (RuntimeException error) {
                LOGGER.error("Camera cleanup failed for {}", owner, error);
            }
        });
        if (session.lease == null || !session.isActive()) return null;
        frame = null;
        LOGGER.debug("Camera acquired by {} priority={}", owner, priority);
        return session;
    }

    public static boolean isActive(CameraSession session) {
        return ownership.active() != null && ownership.active().value() == session;
    }

    public static boolean hasSession() {
        return ownership.active() != null;
    }

    public static boolean accepts(int priority) {
        return !hasSession() || priority >= ownership.active().priority();
    }

    static void stop(CameraSession session, CameraEndReason reason) {
        if (ownership.release(session.lease, reason)) {
            frame = null;
            if (!resetting) restoreBase();
        }
    }

    public static void reset(CameraEndReason reason) {
        resetting = true;
        try {
            ownership.clear(reason);
            SpectateState.set(SpectateMode.FREE);
            SpectatorCameraController.reset();
            frame = null;
            CameraBackend.bind(null);
            CameraBackend.clear();
        } finally {
            resetting = false;
        }
    }

    /** Spawn/respawn can occur under a prearmed cinematic cover. */
    public static void playerRespawn() {
        SpectateState.set(SpectateMode.FREE);
        SpectatorCameraController.reset();
        if (hasSession() && ownership.active().value().lifetime == CameraLifetime.PLAYER_LIFE) {
            ownership.active().value().stop(CameraEndReason.INVALIDATED);
        }
        restoreBase();
    }

    static void validate() {
        if (!hasSession()) return;
        CameraSession session = ownership.active().value();
        Minecraft mc = Minecraft.getInstance();
        if (session.level != mc.level || mc.player == null) {
            reset(CameraEndReason.WORLD_CHANGED);
        } else {
            try {
                if (!session.valid.getAsBoolean()) session.stop(CameraEndReason.INVALIDATED);
            } catch (RuntimeException error) {
                LOGGER.error("Camera validity check failed", error);
                session.stop(CameraEndReason.FAILED);
            }
        }
    }

    static void tick() {
        validate();
        if (!hasSession()) return;
        CameraSession session = ownership.active().value();
        try {
            session.clock.tick();
            if (session.clock.ticks() >= session.duration) session.stop(CameraEndReason.COMPLETED);
        } catch (RuntimeException error) {
            LOGGER.error("Camera sequence failed", error);
            session.stop(CameraEndReason.FAILED);
        }
    }

    public static CameraPolicy policy() {
        if (hasSession()) return ownership.active().value().policy;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !mc.player.isSpectator() || !SpectateState.isRestricted()) return CameraPolicy.PLAYER;
        return SpectateState.isAttach() ? CameraPolicy.SPECTATOR : CameraPolicy.ORBIT;
    }

    public static boolean prepareFrame(float partialTick) {
        validate();
        if (hasSession()) {
            CameraSession session = ownership.active().value();
            try {
                frame = session.rig.sample(session.clock.sample(partialTick));
                if (frame != null && CameraBackend.bind(frame)) {
                    if (session.bindingRevision != CameraBackend.bindingRevision()) {
                        session.bindingRevision = CameraBackend.bindingRevision();
                        session.onBound.run();
                    }
                    return session.isActive();
                }
            } catch (RuntimeException error) {
                LOGGER.error("Camera sampling failed", error);
            }
            session.stop(CameraEndReason.FAILED);
            return false;
        }
        frame = baseFrame();
        if (frame != null) CameraBackend.bind(frame);
        return true;
    }

    /** Called on packet changes and ownership handoff, never by competing controllers. */
    public static void refresh() {
        if (!resetting) prepareFrame(0);
    }

    public static CameraFrame frame() {
        return frame;
    }

    public static void apply(Camera camera) {
        CameraBackend.apply(camera, frame);
    }

    public static void turn(float yaw, float pitch) {
        if (policy().look() != CameraPolicy.LookInput.ORBIT) return;
        if (hasSession()) ownership.active().value().rig.turn(yaw, pitch);
        else SpectatorCameraController.applyAngles(yaw, pitch);
    }

    public static boolean spectatorTargetReady() {
        Minecraft mc = Minecraft.getInstance();
        SpectateTarget target = SpectateState.getTarget();
        if (mc.level == null || mc.player == null || target == null) return false;
        if (target.mode() == SpectateMode.C4_ORBIT || target.mode() == SpectateMode.DEATH_SPOT) return true;
        Entity entity = mc.level.getEntity(target.entityId());
        return entity instanceof Player player && player.isAlive() && !player.isSpectator();
    }

    private static CameraFrame baseFrame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        if (!mc.player.isSpectator() || !SpectateState.isRestricted()) return null;
        SpectateTarget target = SpectateState.getTarget();
        if (target == null) return CameraFrame.attached(mc.player);
        if (SpectateState.isAttach()) {
            if (spectatorTargetReady()) return CameraFrame.attached(mc.level.getEntity(target.entityId()));
            // Never keep following an obsolete/unauthorized player while the replacement loads.
            return CameraFrame.independent(new CameraPose(target.anchor(), target.yaw(), target.pitch()));
        }
        return SpectatorCameraController.rig().sample(0);
    }

    /** Re-evaluate the base on release; do not restore a stale saved entity. */
    public static void restoreBase() {
        if (hasSession()) {
            refresh();
            return;
        }
        frame = baseFrame();
        CameraBackend.bind(frame);
    }

    public static String status() {
        return hasSession() ? ownership.active().owner() + " tick=" + ownership.active().value().clock.ticks() + " priority=" + ownership.active().priority() : "base=" + SpectateState.get();
    }
}
