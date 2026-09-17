package com.ptcrys.fpsmatch.common.client.screen.team;

import java.util.Optional;
import java.util.UUID;

/** Tracks a player row drag without deciding whether the server will accept it. */
public final class TeamDragState {

    private static final double DRAG_THRESHOLD = 4.0;

    private UUID player;
    private String sourceTeam;
    private String hoverTeam;
    private double startX;
    private double startY;
    private boolean moved;

    public void begin(UUID player, String sourceTeam, double x, double y) {
        this.player = player;
        this.sourceTeam = sourceTeam;
        this.hoverTeam = null;
        this.startX = x;
        this.startY = y;
        this.moved = false;
    }

    public Optional<Drop> update(double x, double y, String targetTeam) {
        if (!active()) {
            return Optional.empty();
        }
        if (Math.hypot(x - startX, y - startY) >= DRAG_THRESHOLD) {
            moved = true;
        }
        hoverTeam = targetTeam;
        return Optional.empty();
    }

    public Optional<Drop> release() {
        if (!active()) {
            return Optional.empty();
        }
        Optional<Drop> result = moved && hoverTeam != null && !hoverTeam.equals(sourceTeam) ? Optional.of(new Drop(player, sourceTeam, hoverTeam)) : Optional.empty();
        clear();
        return result;
    }

    public void cancel() {
        clear();
    }

    public boolean active() {
        return player != null;
    }

    public UUID player() {
        return player;
    }

    public String sourceTeam() {
        return sourceTeam;
    }

    public String hoverTeam() {
        return hoverTeam;
    }

    public boolean moved() {
        return moved;
    }

    private void clear() {
        player = null;
        sourceTeam = null;
        hoverTeam = null;
        moved = false;
    }

    public record Drop(UUID player, String sourceTeam, String targetTeam) {}
}
