package com.ptcrys.fpsmatch.common.camera;

import com.ptcrys.fpsmatch.common.client.camera.*;
import com.ptcrys.fpsmatch.common.client.camera.rig.*;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

/** Standalone regression checks for arbitration, clock delivery and continuous shot sampling. */
public final class CameraSystemCheck {
    public static void main(String[] args) {
        ownership();
        markers();
        shots();
        System.out.println("Camera system checks passed");
    }

    private static void ownership() {
        CameraOwnership<String> control = new CameraOwnership<>();
        List<String> ended = new ArrayList<>();
        var death = control.acquire("death", 100, "death", reason -> ended.add("death:" + reason));
        check(control.acquire("low", 20, "low", reason -> ended.add("low")) == null, "lower priority cannot interrupt death");
        var intro = control.acquire("intro", 200, "intro", reason -> ended.add("intro:" + reason));
        check(ended.equals(List.of("death:REPLACED")), "death cleanup runs once on cinematic takeover");
        check(!control.release(death, CameraEndReason.CANCELLED), "old death completion cannot stop intro");
        check(control.active() == intro, "intro retains ownership");
        control.clear(CameraEndReason.WORLD_CHANGED);
        check(control.active() == null && ended.size() == 2, "world unload releases scene");
        control.clear(CameraEndReason.WORLD_CHANGED);
        check(ended.size() == 2, "cleanup is idempotent");
        // Cleanup may itself start another scene. The returned outer lease must not become current again.
        control.acquire("a", 10, "a", reason -> control.acquire("c", 30, "c", ignored -> {}));
        var replaced = control.acquire("b", 20, "b", reason -> ended.add("b:" + reason));
        check(control.active() != replaced && control.active().value().equals("c"), "reentrant replacement keeps newest owner");
        check(!control.release(replaced, CameraEndReason.CANCELLED), "stale reentrant lease is harmless");
    }

    private static void markers() {
        SequenceClock clock = new SequenceClock();
        List<String> events = new ArrayList<>();
        clock.at(0, true, () -> events.add("start"));
        clock.at(2, false, () -> events.add("sound"));
        clock.at(3, true, () -> events.add("end"));
        for (int i = 0; i < 200; ++i) clock.sample(i / 200F);
        check(events.isEmpty(), "rendering never dispatches markers");
        clock.tick(); clock.tick(); clock.tick();
        check(events.equals(List.of("start", "sound", "end")), "completion tick marker is not lost");
        clock.seek(10);
        check(events.size() == 3, "seeking never repeats delivered markers");
        clock.reset(); events.clear(); clock.seek(3);
        check(events.equals(List.of("start", "end")), "late join catches state up without replaying transient audio");
        clock.tick();
        check(events.size() == 2, "skipped audio does not fire on the next tick");
        near(clock.sample(-1), 4, "negative partial tick clamped");
        near(clock.sample(2), 5, "partial tick bounded");
        SequenceClock cancelled = new SequenceClock();
        cancelled.at(0, true, cancelled::cancel);
        cancelled.at(0, true, () -> { throw new AssertionError("cancelled scene must not play later markers"); });
        cancelled.tick();
        check(cancelled.ticks() == 0, "marker cancellation stops the old clock in the same tick");
    }

    private static void shots() {
        CameraPose a = new CameraPose(Vec3.ZERO, 179, 0, 0, 70);
        CameraPose b = new CameraPose(new Vec3(10, 0, 0), -179, 10, 10, 90);
        CameraPose half = a.blend(b, 0.5);
        near(half.position().x, 5, "position interpolation");
        near(half.yaw(), 180, "yaw follows shortest arc");
        near(half.fov(), 80, "FOV interpolation");
        PathRig path = new PathRig(List.of(new PathRig.Keyframe(0, a), new PathRig.Keyframe(10, b)), null);
        near(path.sample(5).pose().position().x, 5, "path midpoint");
        near(path.sample(100).pose().position().x, 10, "path holds final pose");
        check(path.sample(5.5).pose().position().x > 5, "sub-tick sampling advances position");
        CameraSequence blend = sequence(a, b, CameraSequence.Transition.BLEND);
        near(blend.sample(10).pose().position().x, 0, "incoming blend starts at outgoing pose");
        near(blend.sample(12).pose().position().x, 5, "blend midpoint");
        near(blend.sample(14).pose().position().x, 10, "blend reaches incoming shot");
        CameraSequence fade = sequence(a, b, CameraSequence.Transition.FADE);
        near(fade.sample(12).fade(), 1, "switch occurs under full black");
        near(fade.sample(12).pose().position().x, 10, "fade binds incoming shot at midpoint");
        near(fade.sample(14).fade(), 0, "fade clears after transition");
        near(sequence(a, b, CameraSequence.Transition.CUT).sample(10).pose().position().x, 10, "hard cut at exact shot boundary");
        near(blend.sample(200).pose().position().x, 10, "sequence clamps beyond end");
        boolean rejected = false;
        try { new CameraPose(new Vec3(Double.NaN, 0, 0), 0, 0); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "invalid poses cannot enter the renderer");
    }

    private static CameraSequence sequence(CameraPose a, CameraPose b, CameraSequence.Transition transition) {
        return new CameraSequence(List.of(new CameraSequence.Shot(10, new FixedRig(a), CameraSequence.Transition.CUT, 0),
                new CameraSequence.Shot(10, new FixedRig(b), transition, 4)));
    }

    private static void near(double actual, double expected, String message) { check(Math.abs(actual - expected) < 0.0001, message); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
