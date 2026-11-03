package net.ptcrys.fpsmatch.editor.core.session;

import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.Fixture;
import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.NO_DEPENDENCIES;
import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.SourceDocument;
import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.TestType;
import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.project;
import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.registry;
import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.storage.StoreLimits;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;

/** Opening-budget coverage through real stores and access objects. */
class ProjectSessionBudgetTest {

    private static final int MAX_DEPENDENCIES = 65536;
    private static final int MAX_DOCUMENTS = 8192;

    private static final Map<String, String> EN = LangResources.load("en_us");
    private static final Map<String, String> ZH = LangResources.load("zh_cn");

    @TempDir
    Path directory;

    /**
     * Ten documents, each declaring the same 8192 edges, fit eight times into the dependency budget
     * and overflow on the ninth; the assembled session is discarded, and the tenth document is never
     * decoded. The access object is free afterwards, so the same fixture opens once the type reports
     * no dependencies at all.
     */
    @Test
    void dependencyBudgetRejectsTheNinthDocumentAndAFailedOpenReleasesTheLease() throws Exception {
        TestType type = new TestType();
        Set<UUID> edges = new LinkedHashSet<>();
        while (edges.size() < 8192) {
            edges.add(UUID.randomUUID());
        }
        type.dependencyFunction = value -> new DocumentDependencies(edges, Set.of(), Set.of());

        List<SourceDocument> sources = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            sources.add(source(UUID.randomUUID(), "note " + index));
        }

        try (Fixture fixture = project(directory.resolve("project"), registry(type), sources)) {
            assertEquals(10, fixture.loaded().documentIds().size());

            SessionRejection rejected = assertThrows(SessionRejection.class, fixture::open);
            // Eight documents are retained at 8192 edges each; the ninth pushes the total to 73728.
            assertEquals(9, type.decodes, "the ninth document is decoded before the total is added");
            assertEquals(9, type.dependencies, "and reports its edges exactly once");
            Problem problem = singleProblem(rejected);
            assertEquals("editor:session/dependency_budget_exceeded", problem.code().toString());
            assertEquals("dependency_graph", problem.target());
            assertNumbers(problem, 73728L, MAX_DEPENDENCIES);
            assertFrozen(problem, "Required dependency edge count 73728 exceeds limit 65536.",
                    "必需依赖边数量 73728 超过上限 65536。");

            // Nothing was written: the saved project still holds all ten documents with empty manifests.
            assertEquals(10, fixture.store().load().state().documents().size());
            assertEquals(Set.of(), fixture.store().load().state().documents()
                    .get(sources.get(9).id()).documentDependencies());

            // The rejected open released its lease, so a corrected registry opens the same project.
            type.dependencyFunction = value -> NO_DEPENDENCIES;
            try (ProjectSession session = fixture.open()) {
                assertEquals(10, session.documents().size());
                assertEquals(Set.of(), session.unavailableDocuments());
                assertEquals(List.of(), session.problems());
                assertTrue(session.find(sources.get(0).id(), String.class).isPresent());
            }
        }
    }

    /** 8193 stored documents are rejected before the registered type decodes any of them. */
    @Test
    void documentCountBudgetRejectsBeforeAnyDecode() throws Exception {
        TestType type = new TestType();
        int[] decodes = {0};
        type.decodeTransform = value -> {
            decodes[0]++;
            return value;
        };

        StoreLimits defaults = StoreLimits.DEFAULT;
        StoreLimits limits = new StoreLimits(64 * 1024 * 1024, defaults.maxBlobBytes(),
                defaults.maxProjectBytes(), MAX_DOCUMENTS + 1, defaults.maxDependencies(),
                defaults.retainedReceipts());
        UUID projectId = UUID.randomUUID();

        try (LocalProjectStore store = LocalProjectStore.create(directory.resolve("large-project"), limits)) {
            BlobRef content = store.stage(BlobRef.Kind.DOCUMENT,
                    "identical payload".getBytes(StandardCharsets.UTF_8));
            Map<UUID, ProjectState.Entry> documents = new TreeMap<>();
            for (int index = 0; index < MAX_DOCUMENTS + 1; index++) {
                UUID id = UUID.randomUUID();
                documents.put(id, new ProjectState.Entry(id,
                        ProjectSessionFixtures.OWNER.toString(), ProjectSessionFixtures.TYPE.toString(),
                        ProjectSessionFixtures.CODEC.toString(), 1, "note", content, Set.of(), Set.of()));
            }
            store.commit(null, UUID.randomUUID(), new ProjectState(projectId, "Large project",
                    Map.of(ProjectSessionFixtures.OWNER.toString(), ProjectSessionFixtures.BUILD),
                    documents, Map.of(), null));
            LocalProjectAccess access = new LocalProjectAccess(store);
            LoadedProject loaded = access.read();
            assertEquals(MAX_DOCUMENTS + 1, loaded.documentIds().size());

            SessionRejection rejected = assertThrows(SessionRejection.class,
                    () -> ProjectSession.open(access, loaded, registry(type), 8));
            Problem problem = singleProblem(rejected);
            assertEquals("editor:runtime/document_count_exceeded", problem.code().toString());
            assertEquals(projectId.toString(), problem.target());
            assertNumbers(problem, MAX_DOCUMENTS + 1L, MAX_DOCUMENTS);
            assertRuntimeCountMessage(problem);

            assertEquals(0, decodes[0], "the count is refused before the type decodes any document");
            assertEquals(0, type.decodes);
            assertEquals(0, type.dependencies);

            // Exactly 8192 documents remain openable; the next create is refused before its callback.
            documents.remove(documents.keySet().iterator().next());
            store.commit(store.load().commitId(), UUID.randomUUID(), new ProjectState(projectId,
                    "At the session limit", Map.of(ProjectSessionFixtures.OWNER.toString(),
                            ProjectSessionFixtures.BUILD), documents, Map.of(), null));
            LoadedProject atLimit = access.read();
            try (ProjectSession session = ProjectSession.open(access, atLimit, registry(type), 8)) {
                assertEquals(MAX_DOCUMENTS, session.documents().size());
                UUID created = UUID.randomUUID();
                SessionRejection creation = assertThrows(SessionRejection.class,
                        () -> session.create(created, ProjectSessionFixtures.TYPE, String.class, "over limit"));
                Problem creationProblem = singleProblem(creation);
                assertEquals("editor:runtime/document_count_exceeded", creationProblem.code().toString());
                assertNumbers(creationProblem, MAX_DOCUMENTS + 1L, MAX_DOCUMENTS);
                assertEquals(0, type.creates);
                assertEquals(MAX_DOCUMENTS, session.documents().size());
                assertTrue(session.find(created, String.class).isEmpty());
                assertEquals(atLimit.saved(), store.load());
            }
        }
    }

    // ---------------------------------------------------------------- assertions

    private static Problem singleProblem(SessionRejection rejection) {
        List<Problem> problems = rejection.problems();
        assertEquals(1, problems.size(), "exactly one diagnostic: " + problems);
        Problem problem = problems.get(0);
        assertEquals(Problem.Severity.ERROR, problem.severity());
        return problem;
    }

    /** The observed value first, the limit second: the argument order of a code is its contract. */
    private static void assertNumbers(Problem problem, long observed, long limit) {
        TextContent.Message message = assertInstanceOf(TextContent.Message.class, problem.message());
        List<TextContent.Argument> arguments = message.arguments();
        assertEquals(2, arguments.size(), "two numbers: " + arguments);
        assertEquals(0, BigDecimal.valueOf(observed)
                .compareTo(number(arguments.get(0)).value()), "observed count");
        assertEquals(0, BigDecimal.valueOf(limit)
                .compareTo(number(arguments.get(1)).value()), "session limit");
    }

    private static TextContent.NumberArgument number(TextContent.Argument argument) {
        return assertInstanceOf(TextContent.NumberArgument.class, argument);
    }

    /** Cross-checks one produced diagnostic against the frozen sentences of both language files. */
    private static void assertFrozen(Problem problem, String english, String chinese) {
        TextContent.Message message = assertInstanceOf(TextContent.Message.class, problem.message());
        assertEquals(english, LangResources.rendered(EN, message));
        assertEquals(chinese, LangResources.rendered(ZH, message));
    }

    private static void assertRuntimeCountMessage(Problem problem) throws Exception {
        TextContent.Message message = assertInstanceOf(TextContent.Message.class, problem.message());
        assertEquals("editor.runtime.document_count_exceeded", message.key());
        for (String language : List.of("en_us", "zh_cn")) {
            String resource = "/assets/fpsmatch/lang/" + language + ".json";
            try (var input = ProjectSessionBudgetTest.class.getResourceAsStream(resource)) {
                org.junit.jupiter.api.Assertions.assertNotNull(input, resource);
                String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                var pattern = java.util.regex.Pattern.compile("\""
                        + java.util.regex.Pattern.quote(message.key()) + "\"\\s*:\\s*\"([^\"\\\\]*)\"");
                var match = pattern.matcher(json);
                assertTrue(match.find(), "runtime sentence missing: " + message.key());
                String rendered = LangResources.render(message, match.group(1));
                assertEquals(language.equals("en_us")
                        ? "Document count 8193 exceeds the session limit 8192."
                        : "文档数量 8193 超过会话上限 8192。", rendered);
                org.junit.jupiter.api.Assertions.assertFalse(match.find(), "duplicate runtime key");
            }
        }
    }
}
