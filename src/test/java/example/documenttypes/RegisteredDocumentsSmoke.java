package example.documenttypes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.core.documents.DocumentCapture;
import net.ptcrys.fpsmatch.editor.core.documents.DocumentSession;
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
 * Third-party evidence for the document type seam.
 *
 * <p>Two independent custom document types are published as real implementations of the public
 * {@link DocumentTypes#KIND} and are then consumed through the registry wildcard only. The
 * consumption lifecycle lives in one generic method: the value that {@code find} returns is captured
 * as {@code T} at the call, so create, edit, undo, redo, capture, encode/decode and a fresh session
 * all run without a cast, without an unchecked capture helper and without a fixed-type branch. The
 * concrete nested classes are still used where a concrete value has to be built, which is fixture
 * construction and not the consumption path.</p>
 *
 * <p>The published codec of the current schema is a bounded binary format, not a free text format
 * with delimiters: a leading {@code int} schema, an UUID as two {@code long}s, a string as an
 * {@code int} UTF-16 length followed by one {@code writeChar} per code unit, an {@code int} weight,
 * and a tag list as an {@code int} count followed by that many strings, in record field order. It
 * therefore round-trips a title that contains the old separators, a newline and Chinese, and it
 * distinguishes an empty tag list from one empty tag. Lengths, counts and the remaining bytes are
 * checked before anything is allocated, negative lengths, truncation, a wrong embedded schema and
 * trailing data are refused, and a damaged payload is reported with translatable problems that name
 * a reason, never the whole payload. The per-item budgets do not bound the total, so {@code validate}
 * also sums the exact encoded size in {@code long} and reports {@code encoding_too_large} above one
 * MiB, which is what {@code encode} refuses on before it allocates a buffer; one MiB exactly is still
 * written and read back. Only the note keeps its fixed three-field schema 1 sample for
 * in-memory migration, and it is read, never written back.</p>
 */
public final class RegisteredDocumentsSmoke {

    private static final ApiVersion API = new ApiVersion(0, 1);
    private static final EditorId MODULE = EditorId.parse("example:documents");
    private static final EditorId NOTE_ID = EditorId.parse("example:note");
    private static final EditorId PALETTE_ID = EditorId.parse("example:palette");
    private static final EditorId NOTE_CODEC = EditorId.parse("example:note_codec_v2");
    private static final EditorId PALETTE_CODEC = EditorId.parse("example:palette_codec_v1");
    private static final EditorId OTHER_CODEC = EditorId.parse("example:consumer/other_codec");

    /** Problem codes of this example module; every one is a {@code namespace:path} id. */
    private static final EditorId EMPTY_TITLE = EditorId.parse("example:documents/empty_title");
    private static final EditorId UNREADABLE_SCHEMA = EditorId.parse("example:documents/unreadable_schema");
    private static final EditorId DAMAGED_ENCODING = EditorId.parse("example:documents/damaged_encoding");
    private static final EditorId FOREIGN_CODEC = EditorId.parse("example:consumer/foreign_codec");
    private static final EditorId TITLE_TOO_LONG = EditorId.parse("example:documents/title_too_long");
    private static final EditorId CAPTION_TOO_LONG = EditorId.parse("example:documents/caption_too_long");
    private static final EditorId TOO_MANY_TAGS = EditorId.parse("example:documents/too_many_tags");
    private static final EditorId TAG_TOO_LONG = EditorId.parse("example:documents/tag_too_long");
    private static final EditorId ENCODING_TOO_LARGE = EditorId.parse("example:documents/encoding_too_large");
    private static final EditorId NAME_TOO_LONG = EditorId.parse("example:documents/name_too_long");

    /** Domain budgets of the bounded format: enforced by validate and by encode. */
    private static final int MAX_INPUT_BYTES = 1 << 20;
    private static final int MAX_STRING_UNITS = 4096;
    private static final int MAX_TAGS = 256;

    /** Two locked versions of the very same resource; both are legitimate direct dependencies. */
    private static final ResourceRef STYLE_LOCKED = new ResourceRef(id(2), id(21));
    private static final ResourceRef STYLE_OLDER = new ResourceRef(id(2), id(20));
    /** A third-party contribution embedded in the note payload. */
    private static final ContributionKey EMBEDDED_RANK =
            new ContributionKey(EditorId.parse("thirdparty:badge"), EditorId.parse("thirdparty:rank"));
    private static final UUID STYLE_DOCUMENT = id(41);
    private static final UUID BASE_DOCUMENT = id(42);

    private RegisteredDocumentsSmoke() {
    }

    public static void main(String[] args) {
        ExtensionRegistry registry = publish();
        check(registry.entries(DocumentTypes.KIND).size() == 2, "two document types in one registry");
        for (EditorId id : List.of(NOTE_ID, PALETTE_ID)) {
            check(registry.find(DocumentTypes.KIND, id).isPresent(), "real implementation for " + id);
        }
        System.out.println("publish: 2 document types under one COMMON registry, kind "
                + DocumentTypes.KIND.id());

        // Concrete fixtures build the values and their edited bytes; everything below that consumes a
        // registered type goes through the generic methods.
        NoteType noteFixture = new NoteType();
        PaletteType paletteFixture = new PaletteType();
        Note editedNote = new Note(id(7), "标题|a,b\nsecond line", STYLE_DOCUMENT,
                new Badge("badge|caption,中文", 3), STYLE_LOCKED, List.of("a,b", "", "x|y"));
        byte[] noteBytes = noteFixture.encode(editedNote);
        Palette editedPalette = new Palette(id(5), "调色板|a,b\nmain", BASE_DOCUMENT, STYLE_OLDER);
        byte[] paletteBytes = paletteFixture.encode(editedPalette);
        DocumentDependencies noteDependencies = noteFixture.dependencies(editedNote);
        DocumentDependencies paletteDependencies = paletteFixture.dependencies(editedPalette);

        codecEvidence(noteFixture, editedNote, noteBytes, paletteFixture, editedPalette, paletteBytes);
        edgeEvidence(noteFixture, noteBytes);
        budgetEvidence(noteFixture, paletteFixture);

        DocumentType<?> registeredNote = registry.find(DocumentTypes.KIND, NOTE_ID).orElseThrow();
        DocumentType<?> registeredPalette = registry.find(DocumentTypes.KIND, PALETTE_ID).orElseThrow();
        check(registeredNote.valueType() == Note.class, "note value type");
        check(registeredPalette.valueType() == Palette.class, "palette value type");

        // The wildcard of the registry value is captured here, at the call into the generic method.
        exercise(registeredNote, noteBytes, noteDependencies);
        exercise(registeredPalette, paletteBytes, paletteDependencies);

        System.out.println("unreadable schema -> " + describe(
                expectCodecFailure(() -> registeredNote.decode(99, noteBytes))));
        System.out.println("damaged bytes -> " + describe(
                expectCodecFailure(() -> registeredNote.decode(2, truncate(noteBytes)))));
        System.out.println("foreign codec -> " + describe(
                expectCodecFailure(() -> open(registeredNote, OTHER_CODEC, 2, noteBytes, noteDependencies))));
        System.out.println("consumer: an unsupported codecId is refused before decode, so the payload"
                + " stays opaque; both own codec ids open and keep their dependencies");
        open(registeredNote, NOTE_CODEC, 2, noteBytes, noteDependencies);
        open(registeredPalette, PALETTE_CODEC, 1, paletteBytes, paletteDependencies);

        System.out.println("locked versions: the note keeps " + STYLE_LOCKED.versionId()
                + " and the palette keeps " + STYLE_OLDER.versionId()
                + " of the same resource - no current pointer replaces either");
        System.out.println("RegisteredDocumentsSmoke: all checks passed");
    }

    /** Publishes both custom document types as real implementations of the public document kind. */
    private static ExtensionRegistry publish() {
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, Contribution.Side.COMMON);
        builder.addModule(new ModuleSpec(MODULE, "0.1.0", API, Set.of(), Set.of()));
        builder.define(DocumentTypes.KIND);
        builder.add(DocumentTypes.KIND, contribution(NOTE_ID), new NoteType());
        builder.add(DocumentTypes.KIND, contribution(PALETTE_ID), new PaletteType());
        return builder.freeze();
    }

    private static Contribution contribution(EditorId id) {
        return new Contribution(new ContributionKey(DocumentTypes.KIND.id(), id), MODULE,
                Contribution.Side.COMMON, Set.of());
    }

    /**
     * Consumes one registered document type through the generic methods only.
     *
     * <p>The caller hands in the wildcard that {@code ExtensionRegistry.find} returned, so {@code T}
     * is that captured type: this method never casts, never names a concrete value type and never
     * branches on one. It runs create, edit, undo, redo, capture, decode and a fresh session, and
     * compares content encodings and dependencies on both sides of the in-memory re-decode.</p>
     */
    private static <T> void exercise(DocumentType<T> type, byte[] editedBytes, DocumentDependencies expected) {
        String what = type.codecId().toString();
        T created = type.create();
        check(created != null, what + ": create is non-null");
        check(type.valueType().isInstance(created), what + ": create matches valueType");
        check(type.readableSchemas().contains(type.schemaVersion()),
                what + ": the current schema is readable");

        UUID documentId = UUID.randomUUID();
        DocumentSession<T> session = new DocumentSession<>(documentId, created, type, 8);
        byte[] createdEncoding = type.encode(session.view().content());

        T edited = type.decode(type.schemaVersion(), editedBytes);
        session.edit(session.view().token(), new TextContent.Literal(what), current -> edited);
        check(Arrays.equals(editedBytes, type.encode(session.view().content())),
                what + ": edit publishes the decoded value");
        check(session.view().canUndo(), what + ": edit leaves an undo entry");

        session.undo(session.view().token());
        check(Arrays.equals(createdEncoding, type.encode(session.view().content())),
                what + ": undo restores create()");
        check(session.view().canRedo(), what + ": undo leaves a redo entry");

        session.redo(session.view().token());
        check(Arrays.equals(editedBytes, type.encode(session.view().content())),
                what + ": redo restores the edit");

        DocumentCapture<T> capture = session.captureForSave();
        byte[] encoding = capture.encoding();
        check(Arrays.equals(editedBytes, encoding), what + ": the capture carries the edited encoding");

        T decoded = type.decode(type.schemaVersion(), encoding);
        check(Arrays.equals(editedBytes, encoding), what + ": decode left the captured bytes unchanged");
        check(Arrays.equals(editedBytes, type.encode(decoded)), what + ": encode/decode is exact");

        DocumentSession<T> inMemory = new DocumentSession<>(documentId, decoded, type, 8);
        check(Arrays.equals(editedBytes, type.encode(inMemory.view().content())),
                what + ": content survives the in-memory re-decode");
        check(expected.equals(type.dependencies(decoded)), what + ": decoded dependencies");
        check(expected.equals(type.dependencies(inMemory.view().content())),
                what + ": dependencies of the fresh session");
        check(!expected.documents().isEmpty() && !expected.resources().isEmpty(),
                what + ": dependencies name documents and resources");
        System.out.println(what + ": create/edit/undo/redo/capture -> encode/decode -> new session,"
                + " content and dependencies match " + expected
                + " (in-memory re-decode, not a disk reopen)");
    }

    /**
     * A storage consumer checks the codec identity before decoding and refuses a foreign one, so a
     * document type is never asked to guess another codec and the payload stays opaque.
     */
    private static <T> void open(DocumentType<T> type, EditorId codecId, int schema, byte[] encoding,
                                 DocumentDependencies expected) {
        if (!type.codecId().equals(codecId)) {
            throw new DocumentCodecException(List.of(new Problem(Problem.Severity.ERROR, FOREIGN_CODEC,
                    type.codecId().toString(), new TextContent.Message("example.consumer.foreign_codec",
                            List.of(new TextContent.LiteralArgument(codecId.toString()))))));
        }
        T decoded = type.decode(schema, encoding);
        check(expected.equals(type.dependencies(decoded)), "consumer dependencies for " + codecId);
    }

    /** Fixture-level codec evidence: the exact round trip and the legacy sample. */
    private static void codecEvidence(NoteType noteFixture, Note editedNote, byte[] noteBytes,
                                      PaletteType paletteFixture, Palette editedPalette, byte[] paletteBytes) {
        check(noteBytes.length > 4 && noteBytes[3] == 2, "the payload starts with its schema int");
        check(Arrays.equals(noteBytes, noteFixture.encode(noteFixture.decode(2, noteBytes))),
                "note bytes -> value -> bytes is exact");
        Note noteAgain = noteFixture.decode(2, noteBytes);
        check(noteAgain.title().equals(editedNote.title())
                && noteAgain.badge().caption().equals(editedNote.badge().caption())
                && noteAgain.badge().weight() == editedNote.badge().weight()
                && noteAgain.styleDocument().equals(editedNote.styleDocument())
                && noteAgain.palette().equals(editedNote.palette())
                && noteAgain.tags().equals(editedNote.tags()),
                "separators, newline, Chinese and the tag list survive exactly");

        byte[] emptyTagList = noteFixture.encode(withTags(editedNote, List.of()));
        byte[] oneEmptyTag = noteFixture.encode(withTags(editedNote, List.of("")));
        check(!Arrays.equals(emptyTagList, oneEmptyTag), "no tag and one empty tag encode differently");
        check(noteFixture.decode(2, emptyTagList).tags().isEmpty(), "no tag decodes to an empty list");
        check(noteFixture.decode(2, oneEmptyTag).tags().equals(List.of("")),
                "one empty tag decodes to one empty tag");

        check(Arrays.equals(paletteBytes, paletteFixture.encode(paletteFixture.decode(1, paletteBytes))),
                "palette bytes -> value -> bytes is exact");
        check(paletteFixture.decode(1, paletteBytes).name().equals(editedPalette.name()),
                "the palette name with separators, newline and Chinese survives exactly");

        byte[] legacy = ("1|" + id(7) + "|legacy note").getBytes(StandardCharsets.UTF_8);
        byte[] legacyBefore = legacy.clone();
        Note migrated = noteFixture.decode(1, legacy);
        check(Arrays.equals(legacyBefore, legacy), "old schema decode kept the original bytes");
        check(migrated.title().equals("legacy note") && migrated.palette().equals(STYLE_LOCKED)
                        && migrated.tags().isEmpty(),
                "schema 1 migrates to explicit in-memory defaults");
        System.out.println("codec: bounded binary schema 2; separators, newlines, Chinese, an empty tag"
                + " next to delimited tags and the note/palette fields round trip exactly");
        System.out.println("codec: schema 1 keeps its fixed three-field sample and migrates in memory"
                + " only, the source bytes are unchanged");
    }

    /** Fixture-level codec evidence: the budgets and every refusal. */
    private static void edgeEvidence(NoteType fixture, byte[] noteBytes) {
        byte[] badLength = noteBytes.clone();
        putInt(badLength, 20, -1);
        checkRejected(fixture, badLength, "title/bad_length");
        byte[] oversizedLength = noteBytes.clone();
        putInt(oversizedLength, 20, MAX_STRING_UNITS + 1);
        checkRejected(fixture, oversizedLength, "title/bad_length");
        checkRejected(fixture, truncate(noteBytes), "tag/truncated");
        checkRejected(fixture, Arrays.copyOf(noteBytes, 22), "truncated");
        checkRejected(fixture, Arrays.copyOf(noteBytes, 26), "title/truncated");
        checkRejected(fixture, Arrays.copyOf(noteBytes, noteBytes.length + 1), "trailing_bytes");
        byte[] wrongSchema = noteBytes.clone();
        putInt(wrongSchema, 0, 3);
        checkRejected(fixture, wrongSchema, "schema_mismatch");
        checkRejected(fixture, new byte[MAX_INPUT_BYTES + 1], "input/too_large");

        List<String> manyTags = new ArrayList<>();
        for (int i = 0; i <= MAX_TAGS; i++) {
            manyTags.add("t" + i);
        }
        Note overloaded = new Note(id(7), "title", STYLE_DOCUMENT, new Badge("badge", 1), STYLE_LOCKED,
                List.copyOf(manyTags));
        List<Problem> problems = fixture.validate(overloaded);
        check(problems.size() == 1 && problems.get(0).code().equals(TOO_MANY_TAGS),
                "validate reports the tag-count budget");
        check(expectIllegalArgument(() -> fixture.encode(overloaded)).getMessage()
                .contains(TOO_MANY_TAGS.toString()), "encode refuses an unrepresentable tag list");

        Note longTitled = new Note(id(7), "t".repeat(MAX_STRING_UNITS + 1), STYLE_DOCUMENT,
                new Badge("badge", 1), STYLE_LOCKED, List.of());
        check(fixture.validate(longTitled).get(0).code().equals(TITLE_TOO_LONG),
                "validate reports the title budget");
        check(expectIllegalArgument(() -> fixture.encode(longTitled)) != null,
                "encode refuses an over-long title");
        System.out.println("codec edges: negative and oversized lengths, truncation, trailing data, a"
                + " wrong embedded schema and oversized input are refused without touching the"
                + " caller's array; validate reports the domain budgets before encode");
    }

    /** Cumulative-budget evidence: items that each respect their own budget can still exceed one MiB. */
    private static void budgetEvidence(NoteType fixture, PaletteType paletteFixture) {
        Note overBudget = new Note(id(7), "a", STYLE_DOCUMENT, new Badge("", 1), STYLE_LOCKED,
                budgetTags(MAX_STRING_UNITS));
        List<Problem> problems = fixture.validate(overBudget);
        // 84 fixed + 2 for the one character title + 128 * (4 + 2 * 4096) = 598 bytes past 1 MiB.
        check(problems.size() == 1 && problems.get(0).code().equals(ENCODING_TOO_LARGE),
                "every single item is legal, only the cumulative size is not");
        check(problems.get(0).message().equals(
                        new TextContent.Message("example.documents.encoding_too_large", List.of(
                                new TextContent.NumberArgument(BigDecimal.valueOf(MAX_INPUT_BYTES + 598L)),
                                new TextContent.NumberArgument(BigDecimal.valueOf(MAX_INPUT_BYTES))))),
                "the cumulative report names the exact size and the limit, received "
                        + problems.get(0).message());
        check(expectIllegalArgument(() -> fixture.encode(overBudget)).getMessage()
                        .contains(ENCODING_TOO_LARGE.toString()),
                "encode refuses the cumulative overrun before it allocates a buffer");

        Note exact = new Note(id(7), "a", STYLE_DOCUMENT, new Badge("", 1), STYLE_LOCKED, budgetTags(3_797));
        byte[] exactBytes = fixture.encode(exact);
        check(exactBytes.length == MAX_INPUT_BYTES, "exactly 1 MiB is still representable");
        check(fixture.decode(2, exactBytes).equals(exact), "exactly 1 MiB round trips exactly");
        check(Arrays.equals(exactBytes, fixture.encode(fixture.decode(2, exactBytes))),
                "exactly 1 MiB bytes -> value -> bytes is exact");

        Note twoBytesMore = new Note(id(7), "a", STYLE_DOCUMENT, new Badge("", 1), STYLE_LOCKED,
                budgetTags(3_798));
        check(fixture.validate(twoBytesMore).get(0).message().equals(
                        new TextContent.Message("example.documents.encoding_too_large", List.of(
                                new TextContent.NumberArgument(BigDecimal.valueOf(MAX_INPUT_BYTES + 2L)),
                                new TextContent.NumberArgument(BigDecimal.valueOf(MAX_INPUT_BYTES))))),
                "two bytes above the limit is refused with the exact size");
        check(expectIllegalArgument(() -> fixture.encode(twoBytesMore)) != null,
                "encode refuses two bytes above the limit");

        // The palette fixes every other field, so its single name budget already bounds the total.
        Palette widest = new Palette(id(5), "n".repeat(MAX_STRING_UNITS), BASE_DOCUMENT, STYLE_OLDER);
        check(paletteFixture.validate(widest).isEmpty()
                        && paletteFixture.encode(widest).length < MAX_INPUT_BYTES,
                "the palette item budgets already prove its whole encoding");
        System.out.println("cumulative budget: 128 tags of the per-item maximum next to a one character"
                + " title are 598 bytes over 1 MiB, so validate reports " + ENCODING_TOO_LARGE
                + " and encode refuses before allocating; exactly 1 MiB round trips and 2 bytes more is"
                + " refused");
    }

    /**
     * The counterexample's tag list: {@code MAX_TAGS / 2} tags, the first {@code MAX_TAGS / 2 - 1} at
     * the per-item maximum, the last {@code lastLength} units long. Half the tag budget of maximum
     * length is already a mebibyte of UTF-16 units.
     */
    private static List<String> budgetTags(int lastLength) {
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < MAX_TAGS / 2 - 1; i++) {
            tags.add("t".repeat(MAX_STRING_UNITS));
        }
        tags.add("t".repeat(lastLength));
        return List.copyOf(tags);
    }

    /** Runs {@code decode} on {@code encoding} and asserts a damaged-encoding refusal with a reason. */
    private static void checkRejected(NoteType fixture, byte[] encoding, String reason) {
        byte[] before = encoding.clone();
        DocumentCodecException failure = expectCodecFailure(() -> fixture.decode(2, encoding));
        Problem first = failure.problems().get(0);
        check(first.code().equals(DAMAGED_ENCODING), "damaged code for " + reason);
        TextContent.Message message = (TextContent.Message) first.message();
        check(message.key().equals("example.documents.damaged_encoding"), "damaged key for " + reason);
        boolean named = message.arguments().stream()
                .anyMatch(argument -> argument instanceof TextContent.LiteralArgument literal
                        && literal.value().equals(reason));
        check(named, "the reason travels as a translatable argument: " + reason + ", received " + message);
        check(Arrays.equals(before, encoding), "decode left the caller's array untouched for " + reason);
    }

    // ------------------------------------------------------------------ bounded binary codec

    private static void writeUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        out.writeInt(value.length());
        for (int i = 0; i < value.length(); i++) {
            out.writeChar(value.charAt(i));
        }
    }

    private static String readString(DataInputStream in, EditorId target, String field) throws IOException {
        int units = in.readInt();
        if (units < 0 || units > MAX_STRING_UNITS) {
            throw damaged(target, field + "/bad_length", units);
        }
        if (in.available() < 2L * units) {
            throw damaged(target, field + "/truncated");
        }
        char[] chars = new char[units];
        for (int i = 0; i < units; i++) {
            chars[i] = in.readChar();
        }
        return new String(chars);
    }

    private static void writeTags(DataOutputStream out, List<String> tags) throws IOException {
        out.writeInt(tags.size());
        for (String tag : tags) {
            writeString(out, tag);
        }
    }

    private static List<String> readTags(DataInputStream in, EditorId target) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_TAGS) {
            throw damaged(target, "tags/bad_count", count);
        }
        if (in.available() < 4L * count) {
            throw damaged(target, "tags/truncated");
        }
        List<String> tags = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            tags.add(readString(in, target, "tag"));
        }
        return List.copyOf(tags);
    }

    private static void expectEnd(DataInputStream in, EditorId target) throws IOException {
        if (in.available() != 0) {
            throw damaged(target, "trailing_bytes");
        }
    }

    private static Problem tooLong(EditorId target, EditorId code, String key, long actual, long limit) {
        return new Problem(Problem.Severity.ERROR, code, target.toString(),
                new TextContent.Message(key, List.of(
                        new TextContent.NumberArgument(BigDecimal.valueOf(actual)),
                        new TextContent.NumberArgument(BigDecimal.valueOf(limit)))));
    }

    private static DocumentCodecException unreadable(EditorId target, int schema) {
        return new DocumentCodecException(List.of(new Problem(Problem.Severity.ERROR, UNREADABLE_SCHEMA,
                target.toString(), new TextContent.Message("example.documents.unreadable_schema",
                        List.of(new TextContent.NumberArgument(BigDecimal.valueOf(schema)))))));
    }

    private static DocumentCodecException damaged(EditorId target, String reason) {
        return new DocumentCodecException(List.of(new Problem(Problem.Severity.ERROR, DAMAGED_ENCODING,
                target.toString(), new TextContent.Message("example.documents.damaged_encoding",
                        List.of(new TextContent.LiteralArgument(reason))))));
    }

    private static DocumentCodecException damaged(EditorId target, String reason, long actual) {
        return new DocumentCodecException(List.of(new Problem(Problem.Severity.ERROR, DAMAGED_ENCODING,
                target.toString(), new TextContent.Message("example.documents.damaged_encoding",
                        List.of(new TextContent.LiteralArgument(reason),
                                new TextContent.NumberArgument(BigDecimal.valueOf(actual)))))));
    }

    private static String describe(DocumentCodecException failure) {
        Problem first = failure.problems().get(0);
        String key = first.message() instanceof TextContent.Message message ? message.key() : "?";
        return first.code() + " (key " + key + ", " + failure.problems().size() + " problem(s))";
    }

    private static DocumentCodecException expectCodecFailure(CodecCall call) {
        try {
            call.run();
        } catch (DocumentCodecException failure) {
            return failure;
        }
        throw new IllegalStateException("expected a DocumentCodecException");
    }

    /** Asserts that a value the bounded format cannot represent is refused before anything is written. */
    private static IllegalArgumentException expectIllegalArgument(CodecCall call) {
        try {
            call.run();
        } catch (IllegalArgumentException refusal) {
            return refusal;
        }
        throw new IllegalStateException("expected an IllegalArgumentException");
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }

    private static byte[] truncate(byte[] encoding) {
        return Arrays.copyOf(encoding, encoding.length - 1);
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static Note withTags(Note note, List<String> tags) {
        return new Note(note.id(), note.title(), note.styleDocument(), note.badge(), note.palette(), tags);
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new IllegalStateException("check failed: " + what);
        }
    }

    @FunctionalInterface
    private interface CodecCall {
        void run();
    }

    /** The third-party value embedded in a note payload. */
    private record Badge(String caption, int weight) {
        private Badge {
            Objects.requireNonNull(caption, "caption");
        }
    }

    /** One custom document value: an embedded third-party badge and a locked resource version. */
    private record Note(UUID id, String title, UUID styleDocument, Badge badge, ResourceRef palette,
                        List<String> tags) {
        private Note {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(styleDocument, "styleDocument");
            Objects.requireNonNull(badge, "badge");
            Objects.requireNonNull(palette, "palette");
            tags = List.copyOf(tags);
        }
    }

    /** A second custom document value with a locked font version. */
    private record Palette(UUID id, String name, UUID baseDocument, ResourceRef font) {
        private Palette {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(baseDocument, "baseDocument");
            Objects.requireNonNull(font, "font");
        }
    }

    /** The note codec: bounded binary schema 2, it still reads the fixed schema 1 sample. */
    private static final class NoteType implements DocumentType<Note> {

        /**
         * The bytes schema 2 spends before the variable fields: schema int 4, id 16, the title length
         * int 4, the style document 16, the caption length int 4, the weight int 4, the locked palette
         * 32 and the tag count int 4.
         */
        private static final long NOTE_FIXED_BYTES = 84L;

        @Override
        public Class<Note> valueType() {
            return Note.class;
        }

        @Override
        public EditorId codecId() {
            return NOTE_CODEC;
        }

        @Override
        public int schemaVersion() {
            return 2;
        }

        @Override
        public Set<Integer> readableSchemas() {
            return Set.of(1, 2);
        }

        @Override
        public Note create() {
            return new Note(id(7), "untitled", STYLE_DOCUMENT, new Badge("none", 0), STYLE_LOCKED, List.of());
        }

        @Override
        public Note snapshot(Note value) {
            return new Note(value.id(), value.title(), value.styleDocument(), value.badge(),
                    value.palette(), value.tags());
        }

        @Override
        public byte[] encode(Note value) {
            List<Problem> problems = validate(value);
            if (!problems.isEmpty()) {
                // Validation runs before the buffer exists, so an over-budget note is refused here
                // instead of being written into bytes that decode has to reject.
                throw new IllegalArgumentException("refusing to encode an invalid note: "
                        + problems.get(0).code());
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(buffer)) {
                out.writeInt(schemaVersion());
                writeUuid(out, value.id());
                writeString(out, value.title());
                writeUuid(out, value.styleDocument());
                writeString(out, value.badge().caption());
                out.writeInt(value.badge().weight());
                writeUuid(out, value.palette().resourceId());
                writeUuid(out, value.palette().versionId());
                writeTags(out, value.tags());
            } catch (IOException impossible) {
                throw new IllegalStateException("in-memory encoding failed", impossible);
            }
            return buffer.toByteArray();
        }

        @Override
        public List<Problem> validate(Note value) {
            List<Problem> problems = new ArrayList<>();
            if (value.title().isBlank()) {
                problems.add(new Problem(Problem.Severity.ERROR, EMPTY_TITLE, NOTE_ID.toString(),
                        new TextContent.Message("example.documents.empty_title",
                                List.of(new TextContent.LiteralArgument(value.id().toString())))));
            }
            if (value.title().length() > MAX_STRING_UNITS) {
                problems.add(tooLong(NOTE_ID, TITLE_TOO_LONG, "example.documents.title_too_long",
                        value.title().length(), MAX_STRING_UNITS));
            }
            if (value.badge().caption().length() > MAX_STRING_UNITS) {
                problems.add(tooLong(NOTE_ID, CAPTION_TOO_LONG, "example.documents.caption_too_long",
                        value.badge().caption().length(), MAX_STRING_UNITS));
            }
            if (value.tags().size() > MAX_TAGS) {
                problems.add(tooLong(NOTE_ID, TOO_MANY_TAGS, "example.documents.too_many_tags",
                        value.tags().size(), MAX_TAGS));
            }
            for (String tag : value.tags()) {
                if (tag.length() > MAX_STRING_UNITS) {
                    problems.add(tooLong(NOTE_ID, TAG_TOO_LONG, "example.documents.tag_too_long",
                            tag.length(), MAX_STRING_UNITS));
                }
            }
            long size = encodedSize(value);
            if (size > MAX_INPUT_BYTES) {
                // Legally sized items can still add up past what decode accepts; the example refuses
                // the total with the exact number so a caller can act on it.
                problems.add(tooLong(NOTE_ID, ENCODING_TOO_LARGE, "example.documents.encoding_too_large",
                        size, MAX_INPUT_BYTES));
            }
            return List.copyOf(problems);
        }

        /**
         * The exact size of this schema's encoding, computed in {@code long} before any buffer is
         * allocated: the fixed header plus two bytes per UTF-16 unit of the title and the caption plus
         * the four byte length prefix of every tag.
         */
        private static long encodedSize(Note value) {
            long size = NOTE_FIXED_BYTES + 2L * value.title().length()
                    + 2L * value.badge().caption().length();
            for (String tag : value.tags()) {
                size += 4L + 2L * tag.length();
            }
            return size;
        }

        @Override
        public Note decode(int sourceSchema, byte[] encoding) {
            Objects.requireNonNull(encoding, "encoding");
            if (!readableSchemas().contains(sourceSchema)) {
                // An unreadable version is reported, never "tried as the current schema".
                throw unreadable(NOTE_ID, sourceSchema);
            }
            if (encoding.length > MAX_INPUT_BYTES) {
                throw damaged(NOTE_ID, "input/too_large", encoding.length);
            }
            if (sourceSchema == 1) {
                // The one legacy schema keeps its fixed three-field sample; it is read in memory and
                // nothing is ever written back in that shape.
                String text = new String(encoding, StandardCharsets.UTF_8);
                String[] fields = text.split("\\|", -1);
                if (fields.length != 3 || !fields[0].equals("1")) {
                    throw damaged(NOTE_ID, "legacy/field_count", fields.length);
                }
                try {
                    return new Note(UUID.fromString(fields[1]), fields[2], STYLE_DOCUMENT,
                            new Badge("none", 0), STYLE_LOCKED, List.of());
                } catch (IllegalArgumentException failure) {
                    throw damaged(NOTE_ID, "legacy/uuid");
                }
            }
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoding))) {
                if (in.readInt() != sourceSchema) {
                    throw damaged(NOTE_ID, "schema_mismatch");
                }
                UUID noteId = readUuid(in);
                String title = readString(in, NOTE_ID, "title");
                UUID styleDocument = readUuid(in);
                String caption = readString(in, NOTE_ID, "badge/caption");
                int weight = in.readInt();
                ResourceRef palette = new ResourceRef(readUuid(in), readUuid(in));
                List<String> tags = readTags(in, NOTE_ID);
                expectEnd(in, NOTE_ID);
                return new Note(noteId, title, styleDocument, new Badge(caption, weight), palette, tags);
            } catch (IOException truncated) {
                throw damaged(NOTE_ID, "truncated");
            }
        }

        @Override
        public DocumentDependencies dependencies(Note snapshot) {
            return new DocumentDependencies(Set.of(snapshot.styleDocument()), Set.of(snapshot.palette()),
                    Set.of(EMBEDDED_RANK));
        }
    }

    /** The palette codec: bounded binary schema 1 only, with an explicitly empty contribution set. */
    private static final class PaletteType implements DocumentType<Palette> {

        @Override
        public Class<Palette> valueType() {
            return Palette.class;
        }

        @Override
        public EditorId codecId() {
            return PALETTE_CODEC;
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
        public Palette create() {
            return new Palette(id(5), "untitled", BASE_DOCUMENT, STYLE_OLDER);
        }

        @Override
        public Palette snapshot(Palette value) {
            return new Palette(value.id(), value.name(), value.baseDocument(), value.font());
        }

        @Override
        public byte[] encode(Palette value) {
            List<Problem> problems = validate(value);
            if (!problems.isEmpty()) {
                throw new IllegalArgumentException("refusing to encode an invalid palette: "
                        + problems.get(0).code());
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(buffer)) {
                out.writeInt(schemaVersion());
                writeUuid(out, value.id());
                writeString(out, value.name());
                writeUuid(out, value.baseDocument());
                writeUuid(out, value.font().resourceId());
                writeUuid(out, value.font().versionId());
            } catch (IOException impossible) {
                throw new IllegalStateException("in-memory encoding failed", impossible);
            }
            return buffer.toByteArray();
        }

        @Override
        public List<Problem> validate(Palette value) {
            List<Problem> problems = new ArrayList<>();
            if (value.name().isBlank()) {
                problems.add(new Problem(Problem.Severity.ERROR, EMPTY_TITLE, PALETTE_ID.toString(),
                        new TextContent.Message("example.documents.empty_name",
                                List.of(new TextContent.LiteralArgument(value.id().toString())))));
            }
            if (value.name().length() > MAX_STRING_UNITS) {
                problems.add(tooLong(PALETTE_ID, NAME_TOO_LONG, "example.documents.name_too_long",
                        value.name().length(), MAX_STRING_UNITS));
            }
            return List.copyOf(problems);
        }

        @Override
        public Palette decode(int sourceSchema, byte[] encoding) {
            Objects.requireNonNull(encoding, "encoding");
            if (!readableSchemas().contains(sourceSchema)) {
                throw unreadable(PALETTE_ID, sourceSchema);
            }
            if (encoding.length > MAX_INPUT_BYTES) {
                throw damaged(PALETTE_ID, "input/too_large", encoding.length);
            }
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoding))) {
                if (in.readInt() != sourceSchema) {
                    throw damaged(PALETTE_ID, "schema_mismatch");
                }
                UUID paletteId = readUuid(in);
                String name = readString(in, PALETTE_ID, "name");
                UUID baseDocument = readUuid(in);
                ResourceRef font = new ResourceRef(readUuid(in), readUuid(in));
                expectEnd(in, PALETTE_ID);
                return new Palette(paletteId, name, baseDocument, font);
            } catch (IOException truncated) {
                throw damaged(PALETTE_ID, "truncated");
            }
        }

        @Override
        public DocumentDependencies dependencies(Palette snapshot) {
            return new DocumentDependencies(Set.of(snapshot.baseDocument()), Set.of(snapshot.font()), Set.of());
        }
    }
}
