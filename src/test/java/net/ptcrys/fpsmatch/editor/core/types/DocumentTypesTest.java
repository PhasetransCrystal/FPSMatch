package net.ptcrys.fpsmatch.editor.core.types;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.core.documents.DocumentCapture;
import net.ptcrys.fpsmatch.editor.core.documents.DocumentSession;
import net.ptcrys.fpsmatch.editor.core.documents.DocumentToken;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;

/**
 * Contract of {@link DocumentTypes}: the fixed extension point, the four public types and the way a
 * real {@link DocumentType} implementation travels through the public registry API.
 */
class DocumentTypesTest {

    private static final ApiVersion API = new ApiVersion(0, 1);
    private static final EditorId MODULE = EditorId.parse("sample:documents_module");

    private static ExtensionRegistry registryWith(DocumentType<?> type, EditorId contributionId) {
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, Contribution.Side.COMMON);
        builder.addModule(new ModuleSpec(MODULE, "0.1.0", API, Set.of(), Set.of()));
        builder.define(DocumentTypes.KIND);
        builder.add(DocumentTypes.KIND, new Contribution(
                new ContributionKey(DocumentTypes.KIND.id(), contributionId),
                MODULE, Contribution.Side.COMMON, Set.of()), type);
        return builder.freeze();
    }

    @Test
    void theKindIdAndValueTypeAreFixed() {
        assertEquals(EditorId.parse("editor:document_type"), DocumentTypes.KIND.id());
        assertEquals("net.ptcrys.fpsmatch.editor.core.types.DocumentType", DocumentTypes.KIND.valueType().getName());
        assertTrue(DocumentTypes.KIND.valueType().isInstance(new SampleDocumentType()));
        assertFalse(DocumentTypes.KIND.valueType().isPrimitive());
    }

    @Test
    void documentTypesHasOnePublicMemberAndNoInstance() {
        for (Constructor<?> constructor : DocumentTypes.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()), "constructor must be private");
        }
        List<String> publicFields = Arrays.stream(DocumentTypes.class.getDeclaredFields())
                .filter(field -> Modifier.isPublic(field.getModifiers()))
                .map(Field::getName)
                .toList();
        assertEquals(List.of("KIND"), publicFields);
        assertTrue(Arrays.stream(DocumentTypes.class.getDeclaredMethods())
                .noneMatch(method -> Modifier.isPublic(method.getModifiers())));
        assertTrue(Arrays.stream(DocumentTypes.class.getDeclaredFields())
                .noneMatch(field -> Modifier.isPublic(field.getModifiers()) && !Modifier.isStatic(field.getModifiers())));
    }

    @Test
    void aDocumentTypeIsPublishedAndReadBackThroughThePublicKind() {
        ExtensionRegistry registry = registryWith(new SampleDocumentType(), SampleDocumentType.NOTE_ID);

        Optional<DocumentType<?>> found = registry.find(DocumentTypes.KIND, SampleDocumentType.NOTE_ID);
        assertTrue(found.isPresent());
        assertEquals(Set.of(SampleDocumentType.NOTE_ID), registry.entries(DocumentTypes.KIND).keySet());

        // The fixture builds the concrete value and its edited bytes. Consuming the registered
        // wildcard then happens in the generic method alone: no cast, no unchecked capture helper.
        SampleDocumentType fixture = new SampleDocumentType();
        SampleDocumentType.Note edited = new SampleDocumentType.Note(SampleDocumentType.id(4),
                "编辑后的标题|a,b\nsecond line", SampleDocumentType.BACKGROUND_OLD,
                List.of("a,b", "", "x|y"));
        byte[] editedBytes = fixture.encode(edited);
        DocumentDependencies expected = fixture.dependencies(edited);

        exercise(found.orElseThrow(), editedBytes, expected);

        assertEquals(Set.of(SampleDocumentType.BACKGROUND_OLD), expected.resources(),
                "the fixture locks the older version of the same resource");
    }

    @Test
    void anUnknownDocumentTypeIdIsSimplyAbsent() {
        ExtensionRegistry registry = registryWith(new SampleDocumentType(), SampleDocumentType.NOTE_ID);

        assertTrue(registry.find(DocumentTypes.KIND, EditorId.parse("sample:missing")).isEmpty());
    }

    @Test
    void theSampleCodecMigratesItsOlderSchemaAndReportsUnreadableInput() {
        SampleDocumentType type = new SampleDocumentType();
        byte[] legacy = ("1|" + SampleDocumentType.id(4) + "|legacy note").getBytes(StandardCharsets.UTF_8);
        byte[] untouched = legacy.clone();

        SampleDocumentType.Note migrated = type.decode(1, legacy);

        assertEquals("legacy note", migrated.title());
        assertEquals(SampleDocumentType.BACKGROUND, migrated.background());
        assertArrayEquals(untouched, legacy, "decode must not write the caller's array");

        DocumentCodecException unreadable =
                assertThrows(DocumentCodecException.class, () -> type.decode(99, legacy));
        assertEquals(SampleDocumentType.UNREADABLE_SCHEMA, unreadable.problems().get(0).code());
        assertTrue(unreadable.problems().get(0).message() instanceof TextContent.Message);

        byte[] damaged = Arrays.copyOf(validBytes(type), 20);
        DocumentCodecException broken =
                assertThrows(DocumentCodecException.class, () -> type.decode(2, damaged));
        assertEquals(SampleDocumentType.DAMAGED_ENCODING, broken.problems().get(0).code());
        assertEquals(SampleDocumentType.DAMAGED_ENCODING.toString(), broken.getMessage());
        assertFalse(renders(broken).contains(PAYLOAD_MARKER),
                "a damaged message carries a reason, never the whole payload");
        assertTrue(broken.problems().get(0).message() instanceof TextContent.Message);
    }

    /** A payload a damaged message must never quote back. */
    private static final String PAYLOAD_MARKER = "绝不出现在损坏消息里的标题";

    private static byte[] validBytes(SampleDocumentType type) {
        return type.encode(new SampleDocumentType.Note(SampleDocumentType.id(4), PAYLOAD_MARKER,
                SampleDocumentType.BACKGROUND, List.of("a,b", "", "x|y")));
    }

    /** Renders every code, key and argument of a failure so a test can look for a leaked payload. */
    private static String renders(DocumentCodecException failure) {
        StringBuilder text = new StringBuilder();
        for (Problem problem : failure.problems()) {
            text.append(problem.code()).append(' ').append(problem.message()).append(' ');
        }
        return text.toString();
    }

    /**
     * Runs the full public contract of one document type through the generic methods alone: create,
     * session edit with undo and redo, a save capture, decode of the captured encoding and a fresh
     * session over the decoded value, comparing content and dependencies on both sides.
     *
     * <p>The caller hands in the wildcard that {@code ExtensionRegistry.find} returned, so {@code T}
     * is that captured type: this method never casts, never names a concrete value type and never
     * branches on one.</p>
     */
    private static <T> void exercise(DocumentType<T> type, byte[] editedBytes, DocumentDependencies expected) {
        T created = type.create();
        assertNotNull(created);
        assertTrue(type.valueType().isInstance(created));
        assertTrue(type.schemaVersion() >= 1);
        assertFalse(type.readableSchemas().isEmpty());
        assertTrue(type.readableSchemas().size() <= 256);
        assertTrue(type.readableSchemas().contains(type.schemaVersion()));
        assertTrue(type.readableSchemas().stream().allMatch(schema -> schema > 0));

        UUID documentId = UUID.randomUUID();
        DocumentSession<T> session = new DocumentSession<>(documentId, created, type, 4);
        DocumentToken initial = session.view().token();
        byte[] createdEncoding = type.encode(created);

        T edited = type.decode(type.schemaVersion(), editedBytes);
        session.edit(initial, new TextContent.Literal("rewrite"), current -> edited);
        DocumentToken published = session.view().token();
        assertNotEquals(initial, published);
        assertTrue(session.view().dirty());
        assertArrayEquals(editedBytes, type.encode(session.view().content()));

        session.undo(published);
        assertArrayEquals(createdEncoding, type.encode(session.view().content()));
        assertTrue(session.view().canRedo());

        session.redo(session.view().token());
        assertArrayEquals(editedBytes, type.encode(session.view().content()));

        DocumentCapture<T> capture = session.captureForSave();
        byte[] encoding = capture.encoding();
        byte[] beforeDecode = encoding.clone();
        assertArrayEquals(editedBytes, encoding, "the capture carries the published encoding");

        T decoded = type.decode(type.schemaVersion(), encoding);
        assertArrayEquals(beforeDecode, encoding, "decode must not write the captured bytes");

        DocumentSession<T> reopened = new DocumentSession<>(documentId, decoded, type, 4);
        assertArrayEquals(editedBytes, type.encode(reopened.view().content()),
                "content survives the in-memory re-decode");
        assertEquals(expected, type.dependencies(decoded));
        assertEquals(expected, type.dependencies(reopened.view().content()));
        assertFalse(expected.documents().isEmpty());
        assertFalse(expected.resources().isEmpty());
        assertEquals(2, type.dependencies(decoded).resources().size()
                + type.dependencies(decoded).contributions().size());
    }
}
