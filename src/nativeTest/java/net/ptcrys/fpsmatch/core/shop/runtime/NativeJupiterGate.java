package net.ptcrys.fpsmatch.core.shop.runtime;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.launcher.EngineFilter.includeEngines;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/** No asynchronous execution or custom bootstrap: all native assertions run on the GameTest owner. */
final class NativeJupiterGate {
    private NativeJupiterGate() {}

    static void execute(Thread owner, Map<String, Object> report) throws Exception {
        Set<String> expected = expectedMethods();
        report.put("expectedTests", new ArrayList<>(expected));
        report.put("gateCounterexamples", NativeResultRules.verifyCounterexamples());
        Recorder recorder = new Recorder(owner);
        var configuration = LauncherConfig.builder()
                .enableTestEngineAutoRegistration(false)
                .addTestEngines(new JupiterTestEngine())
                .enableLauncherSessionListenerAutoRegistration(false)
                .enableLauncherDiscoveryListenerAutoRegistration(false)
                .enableTestExecutionListenerAutoRegistration(false)
                .enablePostDiscoveryFilterAutoRegistration(false)
                .build();
        try (var session = LauncherFactory.openSession(configuration)) {
            var request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(selectClass(RuntimeItemTemplatesTest.class), selectClass(RuntimeShopSlotTest.class),
                            selectClass(RuntimeKitTemplatesTest.class), selectClass(RuntimeShopDefinitionTest.class),
                            selectClass(RuntimeShopPublicationTest.class), selectClass(RuntimeShopCapabilityTest.class),
                            selectClass(RuntimeMapPersistenceTest.class))
                    .filters(includeEngines("junit-jupiter"))
                    .configurationParameter("junit.jupiter.execution.parallel.enabled", "false")
                    .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "false")
                    .configurationParameter("junit.jupiter.execution.timeout.mode", "disabled")
                    .enableImplicitConfigurationParameters(false)
                    .build();
            var launcher = session.getLauncher();
            TestPlan plan = launcher.discover(request);
            recorder.discovered(plan);
            // Execute this exact discovered plan, so discovery cannot silently differ from execution.
            launcher.execute(plan, recorder);
        } finally {
            recorder.publish(report);
        }
        List<String> violations = NativeResultRules.violations(expected, recorder.discoveredMethods,
                recorder.tests(), recorder.containers(), recorder.issues);
        report.put("violations", violations);
        if (!violations.isEmpty()) throw new AssertionError(String.join("; ", violations));
    }

    private static Set<String> expectedMethods() throws Exception {
        Set<String> pinned = new TreeSet<>();
        try (var stream = NativeJupiterGate.class.getResourceAsStream("/native-item-expected-methods.txt")) {
            if (stream == null) throw new IllegalStateException("Missing native method manifest");
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                for (String line : reader.lines().toList()) {
                    if (line.isBlank() || !pinned.add(line)) throw new IllegalStateException("Invalid native method manifest");
                }
            }
        }
        Set<String> reflected = new TreeSet<>();
        for (Class<?> suite : List.of(RuntimeItemTemplatesTest.class, RuntimeShopSlotTest.class,
                RuntimeKitTemplatesTest.class, RuntimeShopDefinitionTest.class, RuntimeShopPublicationTest.class,
                RuntimeShopCapabilityTest.class, RuntimeMapPersistenceTest.class)) {
            List<Method> methods = Arrays.stream(suite.getDeclaredMethods()).filter(m -> m.isAnnotationPresent(Test.class)).toList();
            int expectedCount = suite == RuntimeItemTemplatesTest.class ? 17 : suite == RuntimeShopSlotTest.class ? 11
                    : suite == RuntimeKitTemplatesTest.class ? 9 : suite == RuntimeShopDefinitionTest.class ? 14
                    : suite == RuntimeShopPublicationTest.class ? 20 : suite == RuntimeShopCapabilityTest.class ? 18 : 17;
            if (methods.size() != expectedCount) throw new AssertionError("Native suite method count changed: " + suite.getName());
            for (Method method : methods) {
                if (method.getParameterCount() != 0 || method.getReturnType() != void.class
                        || !reflected.add(suite.getName() + "#" + method.getName())) {
                    throw new AssertionError("Unexpected native @Test signature: " + method);
                }
            }
        }
        if (!pinned.equals(reflected)) throw new AssertionError("Native manifest differs from actual @Test methods");
        return reflected;
    }

    private static final class Recorder implements TestExecutionListener {
        private final Thread owner;
        private final Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        private final Set<String> discoveredMethods = new LinkedHashSet<>();
        private final List<String> issues = new ArrayList<>();

        Recorder(Thread owner) { this.owner = owner; }

        private void owner() {
            if (Thread.currentThread() != owner) issues.add("Jupiter callback on foreign thread: " + Thread.currentThread().getName());
        }

        synchronized void discovered(TestPlan plan) {
            owner();
            Set<TestIdentifier> all = new LinkedHashSet<>();
            for (TestIdentifier root : plan.getRoots()) {
                all.add(root);
                all.addAll(plan.getDescendants(root));
            }
            for (TestIdentifier node : all) {
                Map<String, Object> row = new LinkedHashMap<>();
                String method = methodId(node);
                row.put("id", node.isTest() ? method : node.getUniqueId());
                row.put("uniqueId", node.getUniqueId());
                row.put("displayName", node.getDisplayName());
                row.put("test", node.isTest());
                row.put("container", node.isContainer());
                row.put("status", "NOT_EXECUTED");
                nodes.put(node.getUniqueId(), row);
                if (node.isTest() && (method == null || !discoveredMethods.add(method))) {
                    issues.add("Missing/duplicate method source: " + node.getUniqueId());
                }
            }
        }

        private static String methodId(TestIdentifier id) {
            return id.getSource().filter(MethodSource.class::isInstance).map(MethodSource.class::cast)
                    .map(source -> source.getClassName() + "#" + source.getMethodName()).orElse(null);
        }

        private Map<String, Object> row(TestIdentifier id) {
            Map<String, Object> row = nodes.get(id.getUniqueId());
            if (row == null) {
                issues.add("Undiscovered execution node: " + id.getUniqueId());
                row = new LinkedHashMap<>();
                row.put("id", id.isTest() ? methodId(id) : id.getUniqueId());
                row.put("test", id.isTest());
                row.put("container", id.isContainer());
                nodes.put(id.getUniqueId(), row);
            }
            return row;
        }

        @Override public synchronized void executionStarted(TestIdentifier id) {
            owner();
            Map<String, Object> row = row(id);
            if (row.containsKey("startedAt")) issues.add("Duplicate start: " + id.getUniqueId());
            row.put("startedAt", Instant.now().toString());
            row.put("threadId", Thread.currentThread().getId());
        }

        @Override public synchronized void executionSkipped(TestIdentifier id, String reason) {
            owner();
            Map<String, Object> row = row(id);
            row.put("status", "SKIPPED");
            row.put("reason", reason);
            row.put("endedAt", Instant.now().toString());
        }

        @Override public synchronized void executionFinished(TestIdentifier id, TestExecutionResult outcome) {
            owner();
            Map<String, Object> row = row(id);
            if (!row.containsKey("startedAt") || row.containsKey("endedAt")) issues.add("Unpaired finish: " + id.getUniqueId());
            row.put("status", outcome.getStatus().name());
            row.put("endedAt", Instant.now().toString());
            outcome.getThrowable().ifPresent(error -> row.put("error", NativeItemGameTests.describe(error)));
        }

        synchronized List<Map<String, Object>> tests() {
            return nodes.values().stream().filter(row -> Boolean.TRUE.equals(row.get("test"))).collect(Collectors.toList());
        }

        synchronized List<Map<String, Object>> containers() {
            return nodes.values().stream().filter(row -> Boolean.TRUE.equals(row.get("container"))).collect(Collectors.toList());
        }

        synchronized void publish(Map<String, Object> report) {
            owner();
            List<Map<String, Object>> tests = tests();
            report.put("tests", tests);
            report.put("containers", containers());
            report.put("discoveredTests", discoveredMethods.size());
            report.put("succeededTests", count(tests, "SUCCESSFUL"));
            report.put("failedTests", count(tests, "FAILED"));
            report.put("abortedTests", count(tests, "ABORTED"));
            report.put("skippedTests", count(tests, "SKIPPED"));
            report.put("callbackIssues", List.copyOf(issues));
        }

        private static long count(List<Map<String, Object>> rows, String status) {
            return rows.stream().filter(row -> status.equals(row.get("status"))).count();
        }
    }
}
