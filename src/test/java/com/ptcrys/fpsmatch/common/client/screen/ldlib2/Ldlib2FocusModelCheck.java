package com.ptcrys.fpsmatch.common.client.screen.ldlib2;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Standalone checks for the ordered keyboard navigation used by the screen adapter. */
public final class Ldlib2FocusModelCheck {
    public static void main(String[] args) {
        Set<String> unavailable = new HashSet<>(Set.of("hidden", "disabled"));
        Ldlib2FocusModel<String> model = new Ldlib2FocusModel<>(target -> !unavailable.contains(target));
        model.setTargets(List.of("first", "hidden", "disabled", "last"));
        expect(model.move(false).orElseThrow(), "first");
        expect(model.move(false).orElseThrow(), "last");
        expect(model.move(false).orElseThrow(), "first");
        expect(model.move(true).orElseThrow(), "last");
        model.clear();
        expect(model.move(true).orElseThrow(), "last");
        model.adopt("first");
        expect(model.move(true).orElseThrow(), "last");
        unavailable.add("last");
        expect(model.reconcile().orElseThrow(), "first");
        model.setTargets(List.of("hidden", "replacement"));
        expect(model.reconcile().orElseThrow(), "replacement");
        unavailable.add("replacement");
        if (model.move(false).isPresent()) throw new AssertionError("Disabled list must have no focus");
        model.setTargets(List.of());
        if (model.move(true).isPresent()) throw new AssertionError("Empty list must have no focus");
        System.out.println("Ldlib2FocusModel: 10 navigation cases passed");
    }

    private static void expect(String actual, String expected) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
