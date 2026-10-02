package net.ptcrys.fpsmatch.common.client.screen.shop;

import net.ptcrys.fpsmatch.common.packet.shop.ShopEditorResultS2CPacket;
import net.ptcrys.fpsmatch.common.shop.editor.ShopEditorSnapshot;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/** Correlates results even after a timeout; a new request supersedes the old one. */
public final class ShopEditorRequest {

    private static final AtomicLong IDS = new AtomicLong(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 2));
    private long id;
    private ShopEditorResultS2CPacket.Operation operation;
    private final ShopEditorSnapshot.Target target;
    private boolean waiting;
    private int ticks;

    public ShopEditorRequest(ShopEditorSnapshot.Target target) {
        this.target = target;
    }

    public long begin(ShopEditorResultS2CPacket.Operation operation) {
        id = nextId();
        this.operation = operation;
        retry();
        return id;
    }

    public static long nextId() {
        return IDS.incrementAndGet();
    }

    public void retry() {
        waiting = true;
        ticks = 0;
    }

    public boolean tick() {
        if (waiting && ++ticks >= 200) {
            waiting = false;
            return true;
        }
        return false;
    }

    public boolean accepts(ShopEditorResultS2CPacket packet) {
        return operation != null && packet.requestId() == id && packet.operation() == operation && target.equals(packet.target()) && (packet.snapshot() == null || target.equals(packet.snapshot().target()));
    }

    public void complete() {
        waiting = false;
        operation = null;
    }

    public boolean waiting() {
        return waiting;
    }

    public long id() {
        return id;
    }
}
