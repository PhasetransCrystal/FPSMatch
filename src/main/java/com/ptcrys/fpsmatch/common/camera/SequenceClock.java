package com.ptcrys.fpsmatch.common.camera;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Tick clock shared by camera, actors and audio. Sampling never dispatches markers. */
public final class SequenceClock {

    private final List<Marker> markers = new ArrayList<>();
    private int ticks;
    private int dispatchedThrough = -1;
    private long generation;
    private boolean cancelled;

    public int ticks() {
        return ticks;
    }

    public double sample(float partialTick) {
        return ticks + Math.max(0, Math.min(1, partialTick));
    }

    public void at(int tick, boolean catchUp, Runnable action) {
        if (tick < 0 || tick <= dispatchedThrough) throw new IllegalArgumentException("Marker is in the past");
        markers.add(new Marker(tick, catchUp, action));
        markers.sort(Comparator.comparingInt(Marker::tick));
    }

    public void tick() {
        if (cancelled) return;
        long currentGeneration = generation;
        dispatch(false);
        if (currentGeneration != generation) return;
        ++ticks;
        dispatch(false);
    }

    /** Late join: catch-up markers run once; transient audio markers can opt out. */
    public void seek(int target) {
        if (cancelled) throw new IllegalStateException("Sequence has ended");
        if (target < ticks) throw new IllegalArgumentException("A running sequence cannot rewind");
        ticks = target;
        dispatch(true);
    }

    public void cancel() {
        cancelled = true;
        ++generation;
    }

    public void reset() {
        ticks = 0;
        dispatchedThrough = -1;
        cancelled = false;
        ++generation;
    }

    private void dispatch(boolean seeking) {
        long currentGeneration = generation;
        int previous = dispatchedThrough;
        dispatchedThrough = ticks;
        for (Marker marker : List.copyOf(markers)) {
            if (currentGeneration != generation) break;
            if (marker.tick > previous && marker.tick <= ticks && (!seeking || marker.catchUp)) {
                marker.action.run();
            }
        }
    }

    private record Marker(int tick, boolean catchUp, Runnable action) {}
}
