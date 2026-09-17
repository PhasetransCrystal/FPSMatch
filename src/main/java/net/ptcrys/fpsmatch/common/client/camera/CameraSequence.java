package net.ptcrys.fpsmatch.common.client.camera;

import java.util.List;

/** Consecutive shots. Transitions occupy the beginning of the incoming shot. */
public final class CameraSequence implements CameraRig {

    public enum Transition {
        CUT,
        BLEND,
        FADE
    }

    public record Shot(int duration, CameraRig rig, Transition transition, int transitionTicks) {

        public Shot {
            if (duration <= 0 || transitionTicks < 0 || transitionTicks > duration) throw new IllegalArgumentException("Invalid shot duration");
            java.util.Objects.requireNonNull(rig);
            java.util.Objects.requireNonNull(transition);
        }
    }

    private final List<Shot> shots;
    private final int duration;

    public CameraSequence(List<Shot> shots) {
        if (shots.isEmpty()) throw new IllegalArgumentException("Empty camera sequence");
        this.shots = List.copyOf(shots);
        int total = 0;
        for (Shot shot : shots) total = Math.addExact(total, shot.duration);
        duration = total;
    }

    public int duration() {
        return duration;
    }

    @Override
    public CameraFrame sample(double ticks) {
        double local = Math.max(0, ticks);
        for (int i = 0; i < shots.size(); ++i) {
            Shot shot = shots.get(i);
            if (local < shot.duration || i == shots.size() - 1) {
                CameraFrame next = shot.rig.sample(Math.min(local, shot.duration));
                if (i == 0 || shot.transitionTicks == 0 || local >= shot.transitionTicks || shot.transition == Transition.CUT) return next;
                Shot previous = shots.get(i - 1);
                CameraFrame from = previous.rig.sample(previous.duration);
                double t = local / shot.transitionTicks;
                if (shot.transition == Transition.BLEND) {
                    if (from.pose() == null || next.pose() == null) return next;
                    return CameraFrame.independent(from.pose().blend(next.pose(), t * t * (3 - 2 * t)));
                }
                CameraFrame frame = t < 0.5 ? from : next;
                return new CameraFrame(frame.entity(), frame.pose(), (float) (1 - Math.abs(t * 2 - 1)));
            }
            local -= shot.duration;
        }
        throw new IllegalStateException("No camera shot");
    }
}
