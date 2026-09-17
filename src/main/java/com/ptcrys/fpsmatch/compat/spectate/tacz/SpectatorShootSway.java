package com.ptcrys.fpsmatch.compat.spectate.tacz;

import com.mojang.logging.LogUtils;
import com.tacz.guns.client.event.FirstPersonRenderGunEvent;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Marks TACZ first-person shoot sway for spectator views.
 */
public final class SpectatorShootSway {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean LOGGED_FAILURE = new AtomicBoolean(false);
    private static volatile Field shootTimeField;
    private static volatile boolean initDone;

    private SpectatorShootSway() {}

    public static void markShoot() {
        Field field = resolveField();
        if (field == null) {
            return;
        }
        try {
            field.setLong(null, System.currentTimeMillis());
        } catch (Throwable ex) {
            logOnce("Failed to update TACZ shoot sway timestamp", ex);
        }
    }

    private static Field resolveField() {
        if (initDone) {
            return shootTimeField;
        }
        synchronized (SpectatorShootSway.class) {
            if (initDone) {
                return shootTimeField;
            }
            try {
                Field field = FirstPersonRenderGunEvent.class.getDeclaredField("shootTimeStamp");
                field.setAccessible(true);
                shootTimeField = field;
            } catch (Throwable ex) {
                logOnce("Failed to resolve TACZ shootTimeStamp field", ex);
            } finally {
                initDone = true;
            }
        }
        return shootTimeField;
    }

    private static void logOnce(String msg, Throwable ex) {
        if (LOGGED_FAILURE.compareAndSet(false, true)) {
            LOGGER.warn("[FPSMatch] {}", msg, ex);
        }
    }
}
