package net.ptcrys.fpsmatch.core.shop.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Fail-closed report rules, also exercised against adversarial result shapes before each launch. */
final class NativeResultRules {
    private NativeResultRules() {}

    static List<String> violations(Set<String> expected, Set<String> discovered,
            List<Map<String, Object>> tests, List<Map<String, Object>> containers, List<String> issues) {
        List<String> failures = new ArrayList<>(issues);
        if (expected.isEmpty()) failures.add("Zero expected tests");
        if (!expected.equals(discovered)) failures.add("Discovered methods differ from actual @Test set");
        Set<Object> executed = tests.stream().map(row -> row.get("id")).collect(Collectors.toSet());
        if (tests.size() != expected.size() || !executed.equals(expected)) failures.add("Executed methods incomplete or duplicated");
        if (tests.stream().anyMatch(row -> !"SUCCESSFUL".equals(row.get("status")))) failures.add("Method failed/aborted/skipped/not executed");
        if (containers.isEmpty()) failures.add("Missing engine/container results");
        if (containers.stream().noneMatch(row -> "[engine:junit-jupiter]".equals(row.get("id")))) failures.add("Missing Jupiter engine");
        if (containers.stream().anyMatch(row -> !"SUCCESSFUL".equals(row.get("status")))) failures.add("Container failed/aborted/skipped/not executed");
        return failures;
    }

    static List<String> verifyCounterexamples() {
        Set<String> expected = Set.of("fixture#method");
        List<Map<String, Object>> goodTests = List.of(Map.of("id", "fixture#method", "status", "SUCCESSFUL"));
        List<Map<String, Object>> goodContainers = List.of(Map.of("id", "[engine:junit-jupiter]", "status", "SUCCESSFUL"));
        if (!violations(expected, expected, goodTests, goodContainers, List.of()).isEmpty()) throw new AssertionError("Gate rejects complete successful evidence");
        List<String> checked = new ArrayList<>();
        reject("zero-discovery", expected, Set.of(), List.of(), goodContainers, checked);
        reject("missing-engine", expected, expected, goodTests, List.of(), checked);
        reject("failed-container", expected, expected, goodTests,
                List.of(Map.of("id", "[engine:junit-jupiter]", "status", "FAILED")), checked);
        for (String status : List.of("FAILED", "ABORTED", "SKIPPED", "NOT_EXECUTED")) {
            reject("method-" + status, expected, expected,
                    List.of(Map.of("id", "fixture#method", "status", status)), goodContainers, checked);
        }
        reject("omitted-method", expected, expected, List.of(), goodContainers, checked);
        return List.copyOf(checked);
    }

    private static void reject(String name, Set<String> expected, Set<String> discovered,
            List<Map<String, Object>> tests, List<Map<String, Object>> containers, List<String> checked) {
        if (violations(expected, discovered, tests, containers, List.of()).isEmpty()) throw new AssertionError("Gate accepted " + name);
        checked.add(name + ":REJECTED");
    }
}
