package net.ptcrys.fpsmatch.core.data;

/** Server tick time for a whole match, independent of round and side changes. */
public final class MatchClock {

    private long ticks;

    public void tick(boolean counting) {
        if (counting) ticks++;
    }

    public long ticks() {
        return ticks;
    }

    public int seconds() {
        return (int) Math.min(Integer.MAX_VALUE, ticks / 20);
    }

    public void reset() {
        ticks = 0;
    }
}
