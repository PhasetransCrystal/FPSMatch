package net.ptcrys.fpsmatch.editor.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;

/**
 * Checks the frozen runtime diagnostic table against the two real language files of the module.
 *
 * <p>Every factory is called once with distinguishable identities, and the produced {@link Problem}
 * is compared with the file's own sentence through {@link LangResources#rendered}, so a changed
 * code, key, target, argument order or argument type fails here in both languages instead of only
 * breaking at runtime.</p>
 */
final class RuntimeMessagesTest {
    /** Frozen runtime namespace read from the original mod assets. */
    private static final Map<String, String> EN = loadRuntimeLang("en_us");
    private static final Map<String, String> ZH = loadRuntimeLang("zh_cn");

    private static final Set<String> KEYS = Set.of(
            "editor.runtime.document_unavailable",
            "editor.runtime.document_owner_mismatch",
            "editor.runtime.required_module_unavailable",
            "editor.runtime.module_version_mismatch",
            "editor.runtime.required_module_not_recorded",
            "editor.runtime.dependency_manifest_mismatch",
            "editor.runtime.resource_dependencies_unavailable",
            "editor.runtime.document_count_exceeded",
            "editor.runtime.reentrant_mutation",
            "editor.runtime.document_identity_conflict",
            "editor.runtime.document_type_unavailable");

    private static final String MODULE = "sandbox:core";

    @Test
    void languageFilesDeclareExactlyTheFrozenTable() {
        assertEquals(KEYS, EN.keySet());
        assertEquals(KEYS, ZH.keySet());
        for (String key : KEYS) {
            assertFalse(EN.get(key).isBlank(), "blank english sentence for " + key);
            assertFalse(ZH.get(key).isBlank(), "blank chinese sentence for " + key);
        }
    }

    @Test
    void documentUnavailableNamesTheTypeAndKeepsTheOriginalContent() {
        UUID document = UUID.randomUUID();
        String type = "sandbox:mesh_document";

        TextContent.Message message = assertDiagnostic(
                RuntimeMessages.documentUnavailable(document, type), "document_unavailable",
                document.toString());

        assertLiterals(message, document.toString(), type);
        assertFrozen(message,
                "Document " + document + " of type " + type
                        + " is unavailable; its original content is preserved.",
                "类型为 " + type + " 的文档 " + document + " 不可用；原始内容已保留。");
    }

    @Test
    void ownerMismatchReportsTheSavedOwnerBeforeTheRegisteredOne() {
        UUID document = UUID.randomUUID();

        TextContent.Message message = assertDiagnostic(
                RuntimeMessages.ownerMismatch(document, "vendor:pipeline",
                        EditorId.parse("sandbox:mesh_importer")),
                "document_owner_mismatch", document.toString());

        assertLiterals(message, document.toString(), "vendor:pipeline", "sandbox:mesh_importer");
        assertFrozen(message,
                "Document " + document
                        + " records owner vendor:pipeline, but its registered owner is"
                        + " sandbox:mesh_importer.",
                "文档 " + document + " 记录的所属模块为 vendor:pipeline，注册所属模块为 sandbox:mesh_importer。");
    }

    @Test
    void moduleUnavailableNamesTheRequiredModule() {
        TextContent.Message message = assertDiagnostic(RuntimeMessages.moduleUnavailable(MODULE),
                "required_module_unavailable", MODULE);

        assertLiterals(message, MODULE);
        assertFrozen(message, "Required module " + MODULE + " is unavailable.",
                "必需模块 " + MODULE + " 不可用。");
    }

    @Test
    void moduleVersionMismatchKeepsBothVersionsVerbatim() {
        TextContent.Message message = assertDiagnostic(
                RuntimeMessages.moduleVersionMismatch(MODULE, "1.2.3", "1.2.4"),
                "module_version_mismatch", MODULE);

        assertLiterals(message, MODULE, "1.2.3", "1.2.4");
        assertFrozen(message,
                "Module " + MODULE + " was saved with version 1.2.3; installed version 1.2.4"
                        + " requires an explicit migration.",
                "模块 " + MODULE + " 保存时的版本为 1.2.3；已安装版本 1.2.4 需要显式迁移。");

        TextContent.Message untrimmed = assertDiagnostic(
                RuntimeMessages.moduleVersionMismatch(MODULE, " 1.2.3 ", "1.2.4"),
                "module_version_mismatch", MODULE);
        assertLiterals(untrimmed, MODULE, " 1.2.3 ", "1.2.4");
    }

    @Test
    void moduleNotRecordedNamesTheModuleAndTheInstalledVersion() {
        TextContent.Message message = assertDiagnostic(
                RuntimeMessages.moduleNotRecorded(EditorId.parse("sandbox:nested"), "0.9.0"),
                "required_module_not_recorded", "sandbox:nested");

        assertLiterals(message, "sandbox:nested", "0.9.0");
        assertFrozen(message,
                "Required module sandbox:nested at version 0.9.0 is absent from the saved module"
                        + " list.",
                "必需模块 sandbox:nested 的版本 0.9.0 未记录在保存的模块列表中。");
    }

    @Test
    void manifestMismatchPointsAtTheDocument() {
        UUID document = UUID.randomUUID();

        TextContent.Message message = assertDiagnostic(RuntimeMessages.manifestMismatch(document),
                "dependency_manifest_mismatch", document.toString());

        assertLiterals(message, document.toString());
        assertFrozen(message,
                "Document " + document + " has dependencies that differ from its saved manifest;"
                        + " explicit migration is required.",
                "文档 " + document + " 的依赖与已存清单不同；需要显式迁移。");
    }

    @Test
    void resourceUnavailablePointsAtTheProject() {
        UUID project = UUID.randomUUID();

        TextContent.Message message = assertDiagnostic(RuntimeMessages.resourceUnavailable(project),
                "resource_dependencies_unavailable", project.toString());

        assertLiterals(message, project.toString());
        assertFrozen(message,
                "Project " + project + " has unavailable resource dependencies; original assets"
                        + " are preserved.",
                "工程 " + project + " 的资源依赖不可用；原始资产已保留。");
    }

    @Test
    void documentLimitReportsBothCountsAsNumbers() {
        UUID project = UUID.randomUUID();

        TextContent.Message message = assertDiagnostic(
                RuntimeMessages.documentLimit(project, 8193L, 8192L), "document_count_exceeded",
                project.toString());

        assertEquals(2, message.arguments().size(), message.key());
        assertNumber(message, 0, 8193L);
        assertNumber(message, 1, 8192L);
        assertFrozen(message, "Document count 8193 exceeds the session limit 8192.",
                "文档数量 8193 超过会话上限 8192。");
    }

    @Test
    void reentrantMutationIsARejectionOfTheSession() {
        UUID session = UUID.randomUUID();

        SessionRejection rejection = RuntimeMessages.reentrant(session);

        assertNull(rejection.getCause(), "a self detected rejection has no technical cause");
        assertEquals("editor:runtime/reentrant_mutation", rejection.getMessage());
        assertEquals(1, rejection.problems().size());
        Problem problem = rejection.problems().get(0);
        TextContent.Message message =
                assertDiagnostic(problem, "reentrant_mutation", session.toString());
        assertLiterals(message, session.toString());
        assertFrozen(message,
                "Session " + session + " cannot change while a document callback or command is"
                        + " running.",
                "会话 " + session + " 不能在文档回调或命令执行期间修改。");
    }

    @Test
    void duplicateDocumentNamesTheIdentity() {
        UUID identity = UUID.randomUUID();

        TextContent.Message message = assertDiagnostic(RuntimeMessages.duplicateDocument(identity),
                "document_identity_conflict", identity.toString());

        assertLiterals(message, identity.toString());
        assertFrozen(message, "Identity " + identity + " is already used by this project.",
                "身份 " + identity + " 已被此工程使用。");
    }

    @Test
    void typeUnavailableNamesTheRequestedType() {
        TextContent.Message message = assertDiagnostic(
                RuntimeMessages.typeUnavailable(EditorId.parse("sandbox:mesh_document")),
                "document_type_unavailable", "sandbox:mesh_document");

        assertLiterals(message, "sandbox:mesh_document");
        assertFrozen(message, "Document type sandbox:mesh_document is unavailable.",
                "文档类型 sandbox:mesh_document 不可用。");
    }

    @Test
    void everyCodeAndKeyFollowsTheRuntimeNamespace() {
        List<Problem> problems = completeTable();

        Set<EditorId> codes = new TreeSet<>();
        for (Problem problem : problems) {
            assertEquals(Problem.Severity.ERROR, problem.severity());
            assertEquals("editor", problem.code().namespace());
            assertTrue(problem.code().path().startsWith("runtime/"), problem.code().toString());
            assertTrue(codes.add(problem.code()), "duplicate code " + problem.code());

            TextContent.Message message =
                    assertInstanceOf(TextContent.Message.class, problem.message());
            String suffix = problem.code().path().substring("runtime/".length());
            assertEquals("editor.runtime." + suffix, message.key());
            assertTrue(EN.containsKey(message.key()), message.key());
            assertTrue(ZH.containsKey(message.key()), message.key());
        }
        assertEquals(11, codes.size(), "the frozen table has eleven diagnostics");
    }

    @Test
    void identityArgumentsAreNeverNumbersOrNested() {
        for (Problem problem : completeTable()) {
            TextContent.Message message =
                    assertInstanceOf(TextContent.Message.class, problem.message());
            if (message.key().endsWith("document_count_exceeded")) {
                continue;
            }
            for (TextContent.Argument argument : message.arguments()) {
                assertInstanceOf(TextContent.LiteralArgument.class, argument,
                        "non numeric argument of " + message.key());
            }
        }
    }

    @Test
    void nullArgumentsRemainProgrammingErrors() {
        UUID id = UUID.randomUUID();
        EditorId type = EditorId.parse("sandbox:mesh_document");

        assertThrows(NullPointerException.class, () -> RuntimeMessages.documentUnavailable(null, "t"));
        assertThrows(NullPointerException.class, () -> RuntimeMessages.documentUnavailable(id, null));
        assertThrows(NullPointerException.class,
                () -> RuntimeMessages.ownerMismatch(id, null, type));
        assertThrows(NullPointerException.class,
                () -> RuntimeMessages.ownerMismatch(id, "vendor:base", null));
        assertThrows(NullPointerException.class, () -> RuntimeMessages.moduleUnavailable(null));
        assertThrows(NullPointerException.class,
                () -> RuntimeMessages.moduleVersionMismatch(MODULE, null, "1.0"));
        assertThrows(NullPointerException.class,
                () -> RuntimeMessages.moduleVersionMismatch(MODULE, "1.0", null));
        assertThrows(NullPointerException.class, () -> RuntimeMessages.moduleNotRecorded(null, "1.0"));
        assertThrows(NullPointerException.class, () -> RuntimeMessages.moduleNotRecorded(type, null));
        assertThrows(NullPointerException.class, () -> RuntimeMessages.manifestMismatch(null));
        assertThrows(NullPointerException.class, () -> RuntimeMessages.resourceUnavailable(null));
        assertThrows(NullPointerException.class, () -> RuntimeMessages.documentLimit(null, 1L, 1L));
        assertThrows(NullPointerException.class, () -> RuntimeMessages.reentrant(null));
        assertThrows(NullPointerException.class, () -> RuntimeMessages.duplicateDocument(null));
        assertThrows(NullPointerException.class, () -> RuntimeMessages.typeUnavailable(null));
    }

    // ------------------------------------------------------------------ helpers

    /** Calls every factory once, including the one diagnostic that travels as a rejection. */
    private static List<Problem> completeTable() {
        UUID identity = UUID.randomUUID();
        List<Problem> problems = new ArrayList<>(List.of(
                RuntimeMessages.documentUnavailable(identity, "sandbox:mesh_document"),
                RuntimeMessages.ownerMismatch(identity, "vendor:pipeline", EditorId.parse("sandbox:mesh_importer")),
                RuntimeMessages.moduleUnavailable(MODULE),
                RuntimeMessages.moduleVersionMismatch(MODULE, "1.2.3", "1.2.4"),
                RuntimeMessages.moduleNotRecorded(EditorId.parse("sandbox:nested"), "0.9.0"),
                RuntimeMessages.manifestMismatch(identity),
                RuntimeMessages.resourceUnavailable(identity),
                RuntimeMessages.documentLimit(identity, 8193L, 8192L),
                RuntimeMessages.duplicateDocument(identity),
                RuntimeMessages.typeUnavailable(EditorId.parse("sandbox:mesh_document"))));
        problems.addAll(RuntimeMessages.reentrant(identity).problems());
        return problems;
    }

    private static TextContent.Message assertDiagnostic(Problem problem, String suffix,
            String target) {
        assertEquals(Problem.Severity.ERROR, problem.severity());
        assertEquals(new EditorId("editor", "runtime/" + suffix), problem.code());
        assertEquals(target, problem.target());
        return assertInstanceOf(TextContent.Message.class, problem.message());
    }

    private static void assertLiterals(TextContent.Message message, String... expected) {
        assertEquals(expected.length, message.arguments().size(), message.key());
        for (int index = 0; index < expected.length; index++) {
            TextContent.LiteralArgument literal = assertInstanceOf(TextContent.LiteralArgument.class,
                    message.arguments().get(index), "argument " + index + " of " + message.key());
            assertEquals(expected[index], literal.value(), "argument " + index + " of " + message.key());
        }
    }

    private static void assertNumber(TextContent.Message message, int index, long value) {
        TextContent.NumberArgument number = assertInstanceOf(TextContent.NumberArgument.class,
                message.arguments().get(index), "argument " + index + " of " + message.key());
        assertEquals(BigDecimal.valueOf(value), number.value());
        assertEquals(Long.toString(value), number.value().toPlainString());
    }

    /** Renders the produced sentence through both real language files. */
    private static void assertFrozen(TextContent.Message message, String english, String chinese) {
        assertEquals(english, LangResources.rendered(EN, message), "english sentence of " + message.key());
        assertEquals(chinese, LangResources.rendered(ZH, message), "chinese sentence of " + message.key());
    }

    /** Reads the strict original assets table, retaining the frozen runtime namespace. */
    private static Map<String, String> loadRuntimeLang(String language) {
        String resource = "/assets/fpsmatch/lang/" + language + ".json";
        try (InputStream stream = RuntimeMessagesTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, "language resource is missing: " + resource);
            Map<String, String> entries = new TreeMap<>(LangResources.parse(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8), resource));
            entries.keySet().removeIf(key -> !key.startsWith("editor.runtime."));
            return Map.copyOf(entries);
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read " + resource, failure);
        }
    }
}
