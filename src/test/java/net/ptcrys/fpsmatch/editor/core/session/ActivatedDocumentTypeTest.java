package net.ptcrys.fpsmatch.editor.core.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceRef;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.DocumentType;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;

/**
 * Contract of {@link DocumentTypeCatalog#activate} and {@link ActivatedDocumentType}.
 *
 * <p>Every fixture here is a real {@link DocumentType} published through the public registry API and
 * reached through {@code DocumentTypeCatalog.activate}; nothing constructs a frozen type directly.
 * The frozen type is only exposed as {@code ActivatedDocumentType<?>}, so each check that needs a
 * value of the implementation's own type runs in a small generic method whose type parameter is the
 * capture of that wildcard.</p>
 */
class ActivatedDocumentTypeTest {

    private static final ApiVersion API = new ApiVersion(0, 1);
    private static final EditorId MODULE = EditorId.parse("q11a:documents_module");

    private static final EditorId NOTE_ID = EditorId.parse("q11a:note");
    private static final EditorId NOTE_CODEC = EditorId.parse("q11a:note_codec");
    private static final EditorId TABLE_ID = EditorId.parse("q11a:table");
    private static final EditorId TABLE_CODEC = EditorId.parse("q11a:table_codec");
    private static final EditorId FOREIGN_CODEC = EditorId.parse("q11a:foreign_codec");

    private static final EditorId FIXTURE_CODEC = EditorId.parse("q11a:fixture_codec");
    private static final EditorId NULL_OUTPUT_CODEC = EditorId.parse("q11a:null_output_codec");
    private static final EditorId LYING_CODEC = EditorId.parse("q11a:lying_codec");
    private static final EditorId LYING_CREATE_ID = EditorId.parse("q11a:lying/create");
    private static final EditorId LYING_SNAPSHOT_ID = EditorId.parse("q11a:lying/snapshot");
    private static final EditorId WIDE_ID = EditorId.parse("q11a:sound/wide_readables");

    private static final EditorId MISSING_CURRENT_SCHEMA_ID = EditorId.parse("q11a:broken/missing_current_schema");
    private static final EditorId NO_READABLES_ID = EditorId.parse("q11a:broken/no_readables");
    private static final EditorId NON_POSITIVE_READABLE_ID = EditorId.parse("q11a:broken/non_positive_readable");
    private static final EditorId NULL_CODEC_ID = EditorId.parse("q11a:broken/null_codec");
    private static final EditorId NULL_ELEMENT_READABLE_ID = EditorId.parse("q11a:broken/null_element_readable");
    private static final EditorId NULL_READABLES_ID = EditorId.parse("q11a:broken/null_readables");
    private static final EditorId NULL_VALUE_TYPE_ID = EditorId.parse("q11a:broken/null_value_type");
    private static final EditorId PRIMITIVE_VALUE_TYPE_ID = EditorId.parse("q11a:broken/primitive_value_type");
    private static final EditorId SCHEMA_ZERO_ID = EditorId.parse("q11a:broken/schema_zero");
    private static final EditorId THROWING_READABLES_ID = EditorId.parse("q11a:broken/throwing_readables");
    private static final EditorId TOO_MANY_READABLES_ID = EditorId.parse("q11a:broken/too_many_readables");

    private static final EditorId TYPE_ACTIVATION_FAILED = EditorId.parse("editor:session/type_activation_failed");
    private static final EditorId UNSUPPORTED_CODEC = EditorId.parse("editor:session/unsupported_codec");
    private static final EditorId UNSUPPORTED_SCHEMA = EditorId.parse("editor:session/unsupported_schema");

    private static final EditorId UNREADABLE = EditorId.parse("q11a:documents/unreadable");
    private static final EditorId EMPTY_TITLE = EditorId.parse("q11a:documents/empty_title");
    private static final EditorId EMPTY_NAME = EditorId.parse("q11a:documents/empty_name");

    private static final String DAMAGED = "damaged";

    /** The diagnostics the note codec reports for a damaged payload, in producer order. */
    private static final List<Problem> NOTE_CODEC_DIAGNOSTICS = List.of(
            problem(Problem.Severity.ERROR, UNREADABLE, "q11a.documents.unreadable"),
            problem(Problem.Severity.WARNING, UNREADABLE, "q11a.documents.partial_content"));

    /** The diagnostics of a note without a title, in producer order. */
    private static final List<Problem> NOTE_VALIDATION_DIAGNOSTICS = List.of(
            problem(Problem.Severity.ERROR, EMPTY_TITLE, "q11a.documents.empty_title"));

    private static final List<Problem> TABLE_DIAGNOSTICS = List.of(
            problem(Problem.Severity.ERROR, EMPTY_NAME, "q11a.documents.empty_name"));

    private static final UUID DOCUMENT = new UUID(7L, 8L);
    private static final ResourceRef BACKGROUND = new ResourceRef(new UUID(1L, 2L), new UUID(3L, 4L));
    private static final ContributionKey BADGE =
            new ContributionKey(EditorId.parse("q11a:rank"), EditorId.parse("q11a:badge"));
    private static final DocumentDependencies NOTE_DEPENDENCIES = new DocumentDependencies(
            Set.of(DOCUMENT), Set.of(BACKGROUND), Set.of(BADGE));
    private static final DocumentDependencies TABLE_DEPENDENCIES =
            new DocumentDependencies(Set.of(DOCUMENT), Set.of(), Set.of());
    private static final DocumentDependencies NO_DEPENDENCIES =
            new DocumentDependencies(Set.of(), Set.of(), Set.of());

    /** The failure a fixture getter throws; the catalog must keep this very object. */
    private static final RuntimeException FIXTURE_FAILURE = new IllegalStateException("descriptor probe failed");

    /** The frozen limit of readable schemas per document type. */
    private static final int READABLE_LIMIT = 256;

    // ------------------------------------------------------------------ fixtures

    /** One note value: a title and its tags. */
    record Note(String title, List<String> tags) {
        Note {
            tags = List.copyOf(tags);
        }
    }

    /** One table value: a name and a row count. */
    record Table(String name, int rows) {
    }

    /**
     * The primary real document type. It counts every descriptor read, so a second read is visible,
     * and it fills the payload array it is handed, so the caller must still own the original bytes.
     */
    private static final class NoteType implements DocumentType<Note> {
        private final List<Problem> validationDiagnostics = new ArrayList<>(NOTE_VALIDATION_DIAGNOSTICS);

        private int valueTypeReads;
        private int codecReads;
        private int schemaReads;
        private int readableReads;
        private int decodes;
        private byte[] decodedPayload;

        @Override
        public Class<Note> valueType() {
            valueTypeReads++;
            return Note.class;
        }

        @Override
        public EditorId codecId() {
            codecReads++;
            return NOTE_CODEC;
        }

        @Override
        public int schemaVersion() {
            schemaReads++;
            return 2;
        }

        @Override
        public Set<Integer> readableSchemas() {
            readableReads++;
            return Set.of(1, 2);
        }

        @Override
        public Note create() {
            return new Note("untitled", List.of());
        }

        @Override
        public Note decode(int sourceSchema, byte[] payload) {
            decodes++;
            decodedPayload = payload;
            String text = new String(payload, StandardCharsets.UTF_8);
            if (DAMAGED.equals(text)) {
                throw new DocumentCodecException(NOTE_CODEC_DIAGNOSTICS);
            }
            Arrays.fill(payload, (byte) '!');
            return new Note(sourceSchema == 1 ? "migrated:" + text : text, List.of("tag"));
        }

        @Override
        public Note snapshot(Note value) {
            return new Note(value.title(), value.tags());
        }

        @Override
        public byte[] encode(Note value) {
            return (value.title() + '|' + String.join(",", value.tags())).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public List<Problem> validate(Note value) {
            return value.title().isBlank() ? validationDiagnostics : List.of();
        }

        @Override
        public DocumentDependencies dependencies(Note snapshot) {
            return NOTE_DEPENDENCIES;
        }
    }

    /**
     * The second real document type. Its readable set is mutable and unsorted, so a change made after
     * activation proves that the frozen type kept a copy instead of a view.
     */
    private static final class TableType implements DocumentType<Table> {
        private final Set<Integer> readables = new LinkedHashSet<>(Set.of(2, 1));

        private int valueTypeReads;
        private int codecReads;
        private int schemaReads;
        private int readableReads;

        @Override
        public Class<Table> valueType() {
            valueTypeReads++;
            return Table.class;
        }

        @Override
        public EditorId codecId() {
            codecReads++;
            return TABLE_CODEC;
        }

        @Override
        public int schemaVersion() {
            schemaReads++;
            return 2;
        }

        @Override
        public Set<Integer> readableSchemas() {
            readableReads++;
            return readables;
        }

        @Override
        public Table create() {
            return new Table("untitled", 0);
        }

        @Override
        public Table decode(int sourceSchema, byte[] payload) {
            return new Table(new String(payload, StandardCharsets.UTF_8), sourceSchema == 1 ? 1 : 2);
        }

        @Override
        public Table snapshot(Table value) {
            return new Table(value.name(), value.rows());
        }

        @Override
        public byte[] encode(Table value) {
            return (value.name() + '#' + value.rows()).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public List<Problem> validate(Table value) {
            return value.name().isBlank() ? TABLE_DIAGNOSTICS : List.of();
        }

        @Override
        public DocumentDependencies dependencies(Table snapshot) {
            return TABLE_DEPENDENCIES;
        }
    }

    /**
     * A descriptor that is sound in every field and returns null from exactly one model call, so the
     * frozen boundary has to refuse a null output without refusing the whole type.
     */
    private static final class NullOutput implements DocumentType<Object> {
        enum MissingOutput { CREATE, SNAPSHOT, ENCODE, VALIDATE, DEPENDENCIES }

        private final MissingOutput missing;

        NullOutput(MissingOutput missing) {
            this.missing = missing;
        }

        @Override
        public Class<Object> valueType() {
            return Object.class;
        }

        @Override
        public EditorId codecId() {
            return NULL_OUTPUT_CODEC;
        }

        @Override
        public int schemaVersion() {
            return 1;
        }

        @Override
        public Set<Integer> readableSchemas() {
            return Set.of(1);
        }

        @Override
        public Object create() {
            return missing == MissingOutput.CREATE ? null : new Object();
        }

        @Override
        public Object decode(int sourceSchema, byte[] payload) {
            return new Object();
        }

        @Override
        public Object snapshot(Object value) {
            return missing == MissingOutput.SNAPSHOT ? null : value;
        }

        @Override
        public byte[] encode(Object value) {
            return missing == MissingOutput.ENCODE ? null : new byte[] {1};
        }

        @Override
        public List<Problem> validate(Object value) {
            return missing == MissingOutput.VALIDATE ? null : List.of();
        }

        @Override
        public DocumentDependencies dependencies(Object value) {
            return missing == MissingOutput.DEPENDENCIES ? null : NO_DEPENDENCIES;
        }
    }

    /**
     * A descriptor whose declared value type is a lie: it names {@code String} while the model hands
     * out an {@code Integer}. One localized unchecked cast is what writing that lie requires.
     */
    private static final class LyingValueType implements DocumentType<Object> {
        enum Lie { CREATE, SNAPSHOT }

        private final Lie lie;

        LyingValueType(Lie lie) {
            this.lie = lie;
        }

        @Override
        @SuppressWarnings("unchecked") // the lie itself: the descriptor names a type it never produces
        public Class<Object> valueType() {
            return (Class<Object>) (Class<?>) String.class;
        }

        @Override
        public EditorId codecId() {
            return LYING_CODEC;
        }

        @Override
        public int schemaVersion() {
            return 1;
        }

        @Override
        public Set<Integer> readableSchemas() {
            return Set.of(1);
        }

        @Override
        public Object create() {
            return lie == Lie.CREATE ? Integer.valueOf(7) : "created";
        }

        @Override
        public Object decode(int sourceSchema, byte[] payload) {
            return "decoded";
        }

        @Override
        public Object snapshot(Object value) {
            return lie == Lie.SNAPSHOT ? Integer.valueOf(7) : value;
        }

        @Override
        public byte[] encode(Object value) {
            return new byte[] {1};
        }

        @Override
        public List<Problem> validate(Object value) {
            return List.of();
        }

        @Override
        public DocumentDependencies dependencies(Object value) {
            return NO_DEPENDENCIES;
        }
    }

    /**
     * A descriptor that is sound by default, so a subclass can break exactly one field or getter and
     * the isolation under test is attributable to that one break alone.
     */
    private abstract static class DescriptorFixture<T> implements DocumentType<T> {
        /** The class the descriptor names; a subclass may return null or a primitive on purpose. */
        protected Class<T> declaredType() {
            throw new UnsupportedOperationException("a broken fixture never reaches a usable type");
        }

        /** The readable schemas of this descriptor. */
        protected Set<Integer> readables() {
            return Set.of(1);
        }

        @Override
        public Class<T> valueType() {
            return declaredType();
        }

        @Override
        public EditorId codecId() {
            return FIXTURE_CODEC;
        }

        @Override
        public int schemaVersion() {
            return 1;
        }

        @Override
        public Set<Integer> readableSchemas() {
            return readables();
        }

        @Override
        public T create() {
            return standIn();
        }

        @Override
        public T decode(int sourceSchema, byte[] payload) {
            return standIn();
        }

        @Override
        public T snapshot(T value) {
            return value;
        }

        @Override
        public byte[] encode(T value) {
            return new byte[] {1};
        }

        @Override
        public List<Problem> validate(T value) {
            return List.of();
        }

        @Override
        public DocumentDependencies dependencies(T value) {
            return NO_DEPENDENCIES;
        }

        /** A neutral value; none of these fixtures is ever asked for one. */
        private T standIn() {
            return null;
        }
    }

    /** A sound descriptor whose readable set is supplied, so the boundary cases stay one line each. */
    private static final class ReadablesFixture extends DescriptorFixture<Object> {
        private final Set<Integer> readables;

        ReadablesFixture(Set<Integer> readables) {
            this.readables = readables;
        }

        @Override
        protected Class<Object> declaredType() {
            return Object.class;
        }

        @Override
        protected Set<Integer> readables() {
            return readables;
        }
    }

    /** A descriptor that names no value type at all. */
    private static final class NullValueType extends DescriptorFixture<Object> {
        @Override
        protected Class<Object> declaredType() {
            return null;
        }
    }

    /** A descriptor that names a primitive value type, which no document value can ever be. */
    private static final class PrimitiveValueType extends DescriptorFixture<Integer> {
        @Override
        protected Class<Integer> declaredType() {
            return Integer.TYPE;
        }
    }

    /** A descriptor without a codec identity. */
    private static final class NullCodec extends DescriptorFixture<Object> {
        @Override
        protected Class<Object> declaredType() {
            return Object.class;
        }

        @Override
        public EditorId codecId() {
            return null;
        }
    }

    /** A descriptor that claims schema version zero. */
    private static final class SchemaZero extends DescriptorFixture<Object> {
        @Override
        protected Class<Object> declaredType() {
            return Object.class;
        }

        @Override
        public int schemaVersion() {
            return 0;
        }
    }

    /** A descriptor whose getter fails outright. */
    private static final class ThrowingReadables extends DescriptorFixture<Object> {
        @Override
        protected Class<Object> declaredType() {
            return Object.class;
        }

        @Override
        protected Set<Integer> readables() {
            throw FIXTURE_FAILURE;
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    void activatesRealTypesAndReadsEachDescriptorGetterOnce() {
        NoteType note = new NoteType();
        TableType table = new TableType();
        ExtensionRegistryBuilder builder = builder();
        publish(builder, NOTE_ID, note);
        publish(builder, TABLE_ID, table);
        DocumentTypeCatalog catalog = catalog(builder);

        assertEquals(List.of(NOTE_ID, TABLE_ID), List.copyOf(catalog.entries().keySet()),
                "the catalog orders the usable types by id");
        assertEquals(Map.of(), catalog.activationFailures(), "both fixtures are usable descriptors");
        assertEquals(List.of(), catalog.problems(), "a sound activation reports nothing");

        ActivatedDocumentType<?> noteType = type(catalog, NOTE_ID);
        assertEquals(NOTE_ID, noteType.id());
        assertEquals(MODULE, noteType.owner());
        assertEquals(Note.class, noteType.valueType());
        assertEquals(NOTE_CODEC, noteType.codecId());
        assertEquals(2, noteType.schemaVersion());
        assertEquals(Set.of(1, 2), noteType.readableSchemas(), "Set.of(1, 2) is a legal descriptor set");
        assertEquals(List.of(1, 2), List.copyOf(noteType.readableSchemas()), "the kept copy is sorted");
        assertThrows(UnsupportedOperationException.class, () -> noteType.readableSchemas().add(3),
                "the readable set of a frozen type cannot be widened");

        ActivatedDocumentType<?> tableType = type(catalog, TABLE_ID);
        assertEquals(Table.class, tableType.valueType());
        assertEquals(2, tableType.schemaVersion());
        assertEquals(Set.of(1, 2), tableType.readableSchemas(), "the copy keeps the producer's content");

        assertEquals(1, note.valueTypeReads, "valueType() is read exactly once");
        assertEquals(1, note.codecReads, "codecId() is read exactly once");
        assertEquals(1, note.schemaReads, "schemaVersion() is read exactly once");
        assertEquals(1, note.readableReads, "readableSchemas() is read exactly once");
        assertEquals(1, table.valueTypeReads);
        assertEquals(1, table.codecReads);
        assertEquals(1, table.schemaReads);
        assertEquals(1, table.readableReads);

        table.readables.clear();
        table.readables.add(31);
        assertEquals(Set.of(1, 2), tableType.readableSchemas(),
                "the frozen set is a copy, so a later descriptor change cannot reach it");

        assertTrue(catalog.find(EditorId.parse("q11a:nowhere")).isEmpty(),
                "an id nobody contributed is not invented");
        assertThrows(NullPointerException.class, () -> catalog.find(null));
        assertThrows(UnsupportedOperationException.class, () -> catalog.entries().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.problems().add(problem(Problem.Severity.ERROR, NOTE_ID, "q11a.unused")));
    }

    @Test
    void coversTheWholeModelSurfaceOfBothTypesWithoutRereadingTheDescriptor() {
        NoteType note = new NoteType();
        TableType table = new TableType();
        ExtensionRegistryBuilder builder = builder();
        publish(builder, NOTE_ID, note);
        publish(builder, TABLE_ID, table);
        DocumentTypeCatalog catalog = catalog(builder);

        // The descriptor keeps changing after activation; the frozen contract must not follow it.
        table.readables.clear();
        table.readables.add(31);
        table.readables.add(3);

        exerciseNote(type(catalog, NOTE_ID), note);
        exerciseTable(type(catalog, TABLE_ID), table);

        assertEquals(1, note.valueTypeReads, "the model surface re-reads no descriptor getter");
        assertEquals(1, note.codecReads);
        assertEquals(1, note.schemaReads);
        assertEquals(1, note.readableReads);
        assertEquals(1, table.valueTypeReads);
        assertEquals(1, table.codecReads);
        assertEquals(1, table.schemaReads);
        assertEquals(1, table.readableReads);
    }

    @Test
    void refusesAForeignCodecBeforeConsultingTheSchema() {
        NoteType note = new NoteType();
        ExtensionRegistryBuilder builder = builder();
        publish(builder, NOTE_ID, note);
        DocumentTypeCatalog catalog = catalog(builder);
        ActivatedDocumentType<?> noteType = type(catalog, NOTE_ID);
        byte[] payload = bytes("hello");

        SessionRejection foreignCodec = assertThrows(SessionRejection.class,
                () -> noteType.decode(FOREIGN_CODEC, 9, payload),
                "a payload of another codec is refused before the schema is consulted");
        assertEquals(1, foreignCodec.problems().size(), "one refused call is one diagnostic");
        assertPlatformDiagnostic(foreignCodec.problems().get(0), UNSUPPORTED_CODEC, NOTE_ID.toString(),
                List.of(NOTE_ID.toString(), FOREIGN_CODEC.toString(), NOTE_CODEC.toString()));
        assertEquals(UNSUPPORTED_CODEC.toString(), foreignCodec.getMessage(),
                "getMessage() repeats the first code as a technical locator");
        assertNull(foreignCodec.getCause(), "the session detected the mismatch itself");
        assertEquals(0, note.decodes, "no decoder ran for a foreign codec");
        assertNull(note.decodedPayload, "the implementation never saw the payload");
        assertArrayEquals(bytes("hello"), payload, "the caller's bytes stay untouched");

        SessionRejection unreadableSchema = assertThrows(SessionRejection.class,
                () -> noteType.decode(NOTE_CODEC, 9, payload),
                "the expected codec with a schema outside the readable set is refused too");
        assertPlatformDiagnostic(unreadableSchema.problems().get(0), UNSUPPORTED_SCHEMA, NOTE_ID.toString(),
                List.of(NOTE_ID.toString(), "9"));
        assertEquals(UNSUPPORTED_SCHEMA.toString(), unreadableSchema.getMessage());
        assertEquals(0, note.decodes, "the implementation is not asked to migrate an unknown schema");
        assertArrayEquals(bytes("hello"), payload);
    }

    @Test
    void handsTheDecoderAPrivateCopyAndKeepsTheOriginalCodecFailure() {
        NoteType note = new NoteType();
        ExtensionRegistryBuilder builder = builder();
        publish(builder, NOTE_ID, note);
        DocumentTypeCatalog catalog = catalog(builder);
        ActivatedDocumentType<?> noteType = type(catalog, NOTE_ID);

        handsAPrivatePayloadCopy(noteType, note);

        DocumentCodecException failure = assertThrows(DocumentCodecException.class,
                () -> noteType.decode(NOTE_CODEC, 2, bytes(DAMAGED)),
                "the codec's own failure travels out unchanged, never as a session rejection");
        assertEquals(NOTE_CODEC_DIAGNOSTICS, failure.problems(), "the Problems survive in producer order");
        assertSame(NOTE_CODEC_DIAGNOSTICS.get(0), failure.problems().get(0),
                "each Problem stays the producer's own object");
        assertSame(NOTE_CODEC_DIAGNOSTICS.get(1), failure.problems().get(1));
        assertEquals(UNREADABLE.toString(), failure.getMessage(), "the first code stays the locator");
        assertEquals(2, note.decodes, "the payload was handed to the decoder twice in total");
    }

    @Test
    void refusesNullDecodeInputsAndNullValuesAtTheBoundary() {
        NoteType note = new NoteType();
        ExtensionRegistryBuilder builder = builder();
        publish(builder, NOTE_ID, note);
        DocumentTypeCatalog catalog = catalog(builder);
        ActivatedDocumentType<?> noteType = type(catalog, NOTE_ID);

        assertThrows(NullPointerException.class, () -> noteType.decode(null, 2, bytes("hello")));
        assertThrows(NullPointerException.class, () -> noteType.decode(NOTE_CODEC, 2, null));
        assertEquals(0, note.decodes, "a null argument never reaches the implementation");

        assertThrows(NullPointerException.class, () -> noteType.snapshot(null));
        assertThrows(NullPointerException.class, () -> noteType.encode(null));
        assertThrows(NullPointerException.class, () -> noteType.validate(null));
        assertThrows(NullPointerException.class, () -> noteType.dependencies(null));
    }

    @Test
    void refusesEveryNullOutputOfAnOtherwiseUsableType() {
        ExtensionRegistryBuilder builder = builder();
        for (NullOutput.MissingOutput missing : NullOutput.MissingOutput.values()) {
            publish(builder, nullOutputId(missing), new NullOutput(missing));
        }
        DocumentTypeCatalog catalog = catalog(builder);

        assertEquals(NullOutput.MissingOutput.values().length, catalog.entries().size(),
                "a descriptor that misbehaves later is still a usable descriptor");
        assertEquals(List.of(), catalog.problems());

        for (NullOutput.MissingOutput missing : NullOutput.MissingOutput.values()) {
            refusesTheNullOutput(type(catalog, nullOutputId(missing)), missing);
        }
    }

    @Test
    void refusesADescriptorOutputThatIsNotTheFrozenValueType() {
        ExtensionRegistryBuilder builder = builder();
        publish(builder, LYING_CREATE_ID, new LyingValueType(LyingValueType.Lie.CREATE));
        publish(builder, LYING_SNAPSHOT_ID, new LyingValueType(LyingValueType.Lie.SNAPSHOT));
        DocumentTypeCatalog catalog = catalog(builder);

        assertEquals(2, catalog.entries().size(), "the descriptor checks cannot see the lie");

        refusesTheWrongValueType(type(catalog, LYING_CREATE_ID), LyingValueType.Lie.CREATE);
        refusesTheWrongValueType(type(catalog, LYING_SNAPSHOT_ID), LyingValueType.Lie.SNAPSHOT);
    }

    @Test
    void isolatesOneBrokenDescriptorToItsOwnTypeAndKeepsTheFailureObject() {
        NoteType note = new NoteType();
        ExtensionRegistryBuilder builder = builder();
        publish(builder, NOTE_ID, note);
        publish(builder, WIDE_ID, new ReadablesFixture(schemas(READABLE_LIMIT)));

        Map<EditorId, DocumentType<?>> broken = new TreeMap<>();
        broken.put(MISSING_CURRENT_SCHEMA_ID, new ReadablesFixture(Set.of(2)));
        broken.put(NO_READABLES_ID, new ReadablesFixture(Set.of()));
        broken.put(NON_POSITIVE_READABLE_ID, new ReadablesFixture(Set.of(0)));
        broken.put(NULL_CODEC_ID, new NullCodec());
        broken.put(NULL_ELEMENT_READABLE_ID, new ReadablesFixture(withNullElement()));
        broken.put(NULL_READABLES_ID, new ReadablesFixture(null));
        broken.put(NULL_VALUE_TYPE_ID, new NullValueType());
        broken.put(PRIMITIVE_VALUE_TYPE_ID, new PrimitiveValueType());
        broken.put(SCHEMA_ZERO_ID, new SchemaZero());
        broken.put(THROWING_READABLES_ID, new ThrowingReadables());
        broken.put(TOO_MANY_READABLES_ID, new ReadablesFixture(schemas(READABLE_LIMIT + 1)));
        for (Map.Entry<EditorId, DocumentType<?>> entry : broken.entrySet()) {
            publish(builder, entry.getKey(), entry.getValue());
        }

        DocumentTypeCatalog catalog = catalog(builder);

        assertEquals(Set.of(NOTE_ID, WIDE_ID), catalog.entries().keySet(),
                "the sound types stay usable next to eleven broken ones");
        assertEquals(READABLE_LIMIT, type(catalog, WIDE_ID).readableSchemas().size(),
                "a descriptor exactly at the readable limit is still accepted");
        assertEquals(1, note.valueTypeReads, "the sound type was activated once");

        assertEquals(broken.keySet(), catalog.activationFailures().keySet(),
                "every broken descriptor is recorded under its own id");
        for (EditorId id : broken.keySet()) {
            assertTrue(catalog.find(id).isEmpty(), "a broken type is absent from the usable entries");
            if (!THROWING_READABLES_ID.equals(id)) {
                assertInstanceOf(IllegalArgumentException.class, catalog.activationFailures().get(id),
                        "a refused descriptor is a programming error of its producer: " + id);
            }
        }
        assertSame(FIXTURE_FAILURE, catalog.activationFailures().get(THROWING_READABLES_ID),
                "a getter that fails on its own is kept as the very object it threw, unwrapped");
        assertEquals("descriptor probe failed", catalog.activationFailures().get(THROWING_READABLES_ID).getMessage());
        for (EditorId id : broken.keySet()) {
            if (!THROWING_READABLES_ID.equals(id)) {
                assertTrue(catalog.activationFailures().get(id).getMessage().contains(id.toString()),
                        "a descriptor error names the type it refused: " + id);
            }
        }

        List<EditorId> brokenIds = List.copyOf(broken.keySet());
        List<Problem> reported = catalog.problems();
        assertEquals(brokenIds.size(), reported.size(), "one diagnostic per broken type, none for a sound one");
        for (int index = 0; index < brokenIds.size(); index++) {
            EditorId id = brokenIds.get(index);
            assertEquals(Problem.Severity.ERROR, reported.get(index).severity());
            assertPlatformDiagnostic(reported.get(index), TYPE_ACTIVATION_FAILED, id.toString(),
                    List.of(id.toString()));
        }

        assertThrows(UnsupportedOperationException.class, () -> catalog.activationFailures().clear());
        assertEquals(brokenIds, List.copyOf(catalog.activationFailures().keySet()));
    }

    @Test
    void anUndefinedDocumentKindAndANullRegistryAreAssemblyErrors() {
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, Contribution.Side.COMMON);
        builder.addModule(new ModuleSpec(MODULE, "0.1.0", API, Set.of(), Set.of()));
        ExtensionRegistry registry = builder.freeze();

        IllegalArgumentException undefinedKind = assertThrows(IllegalArgumentException.class,
                () -> DocumentTypeCatalog.activate(registry),
                "a kind nobody defined cannot yield an empty catalog");
        assertTrue(undefinedKind.getMessage().contains("editor:document_type"),
                "the assembly error names the missing kind: " + undefinedKind.getMessage());

        assertThrows(NullPointerException.class, () -> DocumentTypeCatalog.activate(null));
    }

    // ------------------------------------------------------------------ checks

    /**
     * Covers the whole frozen surface of the note type: creation, both readable schemas, snapshot,
     * encoding, validation and dependencies, plus the copies the boundary makes.
     */
    private static <T> void exerciseNote(ActivatedDocumentType<T> type, NoteType implementation) {
        assertEquals(new Note("untitled", List.of()), type.create());

        T current = type.decode(type.codecId(), 2, bytes("hello"));
        assertEquals(new Note("hello", List.of("tag")), current);
        T snapshot = type.snapshot(current);
        assertEquals(current, snapshot);
        assertNotSame(current, snapshot, "the snapshot is a value of its own");
        assertArrayEquals(bytes("hello|tag"), type.encode(snapshot));
        assertEquals(List.of(), type.validate(snapshot), "a complete note reports nothing");
        assertEquals(NOTE_DEPENDENCIES, type.dependencies(snapshot));

        T migrated = type.decode(type.codecId(), 1, bytes("old"));
        assertEquals(new Note("migrated:old", List.of("tag")), migrated,
                "the frozen readable set still covers schema 1");
        assertArrayEquals(bytes("migrated:old|tag"), type.encode(type.snapshot(migrated)));

        T blank = type.decode(type.codecId(), 2, new byte[0]);
        assertEquals(new Note("", List.of("tag")), blank);
        List<Problem> reported = type.validate(blank);
        assertEquals(NOTE_VALIDATION_DIAGNOSTICS, reported);
        assertThrows(UnsupportedOperationException.class,
                () -> reported.add(NOTE_VALIDATION_DIAGNOSTICS.get(0)),
                "the reported diagnostics are a copy the caller cannot change");
        implementation.validationDiagnostics.add(NOTE_CODEC_DIAGNOSTICS.get(1));
        assertEquals(NOTE_VALIDATION_DIAGNOSTICS, reported,
                "a later change of the producer's list is not visible in the copy the caller holds");
        assertEquals(NOTE_VALIDATION_DIAGNOSTICS.size() + 1, type.validate(blank).size(),
                "each call copies the producer's current list");
        assertArrayEquals(bytes("|tag"), type.encode(blank));
        assertEquals(NOTE_DEPENDENCIES, type.dependencies(blank));
    }

    /** Covers the whole frozen surface of the table type, which keeps its readable set mutable. */
    private static <T> void exerciseTable(ActivatedDocumentType<T> type, TableType implementation) {
        assertEquals(new Table("untitled", 0), type.create());

        T current = type.decode(type.codecId(), 2, bytes("report"));
        assertEquals(new Table("report", 2), current);
        T snapshot = type.snapshot(current);
        assertEquals(current, snapshot);
        assertNotSame(current, snapshot);
        assertArrayEquals(bytes("report#2"), type.encode(snapshot));
        assertEquals(List.of(), type.validate(snapshot));
        assertEquals(TABLE_DEPENDENCIES, type.dependencies(snapshot));

        T migrated = type.decode(type.codecId(), 1, bytes("legacy"));
        assertEquals(new Table("legacy", 1), migrated,
                "a schema the descriptor no longer lists was frozen before it changed");

        T blank = type.decode(type.codecId(), 2, new byte[0]);
        assertEquals(new Table("", 2), blank);
        assertEquals(TABLE_DIAGNOSTICS, type.validate(blank));
        assertThrows(UnsupportedOperationException.class, () -> type.validate(blank).clear(),
                "the reported diagnostics are a copy the caller cannot change");
        assertArrayEquals(bytes("#2"), type.encode(blank));
        assertEquals(TABLE_DEPENDENCIES, type.dependencies(blank));
        assertTrue(implementation.readables.contains(31),
                "the descriptor set was widened after activation, so nothing above followed it");
    }

    /** The decoder works on its own copy, so the caller keeps the bytes it handed over. */
    private static <T> void handsAPrivatePayloadCopy(ActivatedDocumentType<T> type, NoteType implementation) {
        byte[] payload = bytes("hello");

        T decoded = type.decode(type.codecId(), 2, payload);

        assertEquals(new Note("hello", List.of("tag")), decoded);
        assertArrayEquals(bytes("!!!!!"), implementation.decodedPayload,
                "the decoder wrote only into the array it received");
        assertNotSame(payload, implementation.decodedPayload, "that array is not the caller's array");
        assertArrayEquals(bytes("hello"), payload, "the bytes of the caller are unchanged");
    }

    /** One null output is refused, while the very same type still decodes a valid payload. */
    private static <T> void refusesTheNullOutput(ActivatedDocumentType<T> type, NullOutput.MissingOutput missing) {
        assertNotNull(type.decode(type.codecId(), 1, new byte[0]),
                "the defective type still decodes a valid payload");

        switch (missing) {
            case CREATE -> assertThrows(NullPointerException.class, () -> type.create());
            case SNAPSHOT -> assertThrows(NullPointerException.class, () -> type.snapshot(type.create()));
            case ENCODE -> assertThrows(NullPointerException.class, () -> type.encode(type.create()));
            case VALIDATE -> assertThrows(NullPointerException.class, () -> type.validate(type.create()));
            case DEPENDENCIES -> assertThrows(NullPointerException.class, () -> type.dependencies(type.create()));
        }
    }

    /**
     * A descriptor that names a value type it does not produce must not be able to hand that value to
     * a caller. The frozen type is the only source of a value of its own type, so a wrong input cannot
     * be produced without lying about an output first; the output cast is the reachable boundary.
     */
    private static <T> void refusesTheWrongValueType(ActivatedDocumentType<T> type, LyingValueType.Lie lie) {
        switch (lie) {
            case CREATE -> assertThrows(ClassCastException.class, () -> type.create());
            case SNAPSHOT -> {
                T created = type.create();
                assertEquals("created", created, "this value still matches the type the descriptor names");
                assertThrows(ClassCastException.class, () -> type.snapshot(created));
            }
        }
    }

    /**
     * Checks one produced platform diagnostic: its identity, its typed arguments in the order the code
     * declares, and both shipped translations of its key.
     */
    private static void assertPlatformDiagnostic(Problem problem, EditorId code, String target,
            List<String> arguments) {
        assertEquals(code, problem.code());
        assertEquals(target, problem.target());
        assertEquals(Problem.Severity.ERROR, problem.severity());
        TextContent.Message message = assertInstanceOf(TextContent.Message.class, problem.message());
        assertEquals(arguments, message.arguments().stream().map(LangResources::argumentText).toList(),
                "the argument order of " + code + " is part of its contract");

        for (String language : List.of("en_us", "zh_cn")) {
            String sentence = LangResources.rendered(LangResources.load(language), message);
            int position = -1;
            for (String argument : arguments) {
                int found = sentence.indexOf(argument);
                assertTrue(found > position, language + " renders " + code + " out of order: " + sentence);
                position = found;
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static ExtensionRegistryBuilder builder() {
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, Contribution.Side.COMMON);
        builder.addModule(new ModuleSpec(MODULE, "0.1.0", API, Set.of(), Set.of()));
        builder.define(DocumentTypes.KIND);
        return builder;
    }

    /** Publishes one document type the way a real module does. */
    private static void publish(ExtensionRegistryBuilder builder, EditorId id, DocumentType<?> implementation) {
        Contribution contribution = new Contribution(
                new ContributionKey(DocumentTypes.KIND.id(), id), MODULE, Contribution.Side.COMMON, Set.of());
        builder.add(DocumentTypes.KIND, contribution, implementation);
    }

    private static DocumentTypeCatalog catalog(ExtensionRegistryBuilder builder) {
        ExtensionRegistry registry = builder.freeze();
        assertEquals(List.of(), registry.metadata().problems(), "the fixture registry itself must be sound");
        return DocumentTypeCatalog.activate(registry);
    }

    private static ActivatedDocumentType<?> type(DocumentTypeCatalog catalog, EditorId id) {
        assertTrue(catalog.find(id).isPresent(), "activated type " + id);
        return catalog.find(id).orElseThrow();
    }

    private static EditorId nullOutputId(NullOutput.MissingOutput missing) {
        return switch (missing) {
            case CREATE -> EditorId.parse("q11a:null_output/create");
            case SNAPSHOT -> EditorId.parse("q11a:null_output/snapshot");
            case ENCODE -> EditorId.parse("q11a:null_output/encode");
            case VALIDATE -> EditorId.parse("q11a:null_output/validate");
            case DEPENDENCIES -> EditorId.parse("q11a:null_output/dependencies");
        };
    }

    /** {@code count} positive schemas starting at 1, so the claimed schema is always included. */
    private static Set<Integer> schemas(int count) {
        Set<Integer> readables = new LinkedHashSet<>();
        for (int schema = 1; schema <= count; schema++) {
            readables.add(schema);
        }
        return readables;
    }

    private static Set<Integer> withNullElement() {
        Set<Integer> readables = new LinkedHashSet<>();
        readables.add(1);
        readables.add(null);
        return readables;
    }

    private static Problem problem(Problem.Severity severity, EditorId code, String key) {
        return new Problem(severity, code, NOTE_ID.toString(),
                new TextContent.Message(key, List.of(new TextContent.LiteralArgument(NOTE_ID.toString()))));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
