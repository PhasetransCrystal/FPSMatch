package net.ptcrys.fpsmatch.editor.core.types;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceRef;

/**
 * A sample document type used by the contract tests.
 *
 * <p>It obeys the rules {@link DocumentType} states: a non-null reference value type, a schema
 * version of 2 that is also listed in the readable schemas, an older readable schema 1 that is
 * migrated in memory, diagnostics as translatable problems, and a dependency set that names the
 * locked resource version and the embedded third-party contribution instead of only a document.</p>
 *
 * <p>Its current codec is a bounded binary format: a leading {@code int} schema, an UUID as two
 * {@code long}s, a string as an {@code int} UTF-16 length followed by one {@code writeChar} per code
 * unit and a tag list as an {@code int} count followed by that many strings, all in record field
 * order. A delimiter, a newline or Chinese in a title or a tag therefore round-trips exactly, and no
 * tag list can be confused with one empty tag. The length, the count and the remaining bytes are
 * checked before anything is allocated; a negative length, an oversized length or count, a truncated
 * stream, a wrong embedded schema and trailing data are refused. The per-item budgets cannot bound
 * the total, so {@code validate} also sums the exact encoded size in {@code long} and reports
 * {@code encoding_too_large} above one MiB; {@code encode} refuses on that report before it allocates
 * a buffer. A damaged payload is reported with
 * a short reason, never with the whole payload, and an unrepresentable value is refused by
 * {@code validate} and by {@code encode} instead of being written out.</p>
 */
final class SampleDocumentType implements DocumentType<SampleDocumentType.Note> {

    /** Contribution id of this sample document type. */
    static final EditorId NOTE_ID = EditorId.parse("sample:note");
    /** Codec identity of this sample codec. */
    static final EditorId CODEC_ID = EditorId.parse("sample:note_codec");
    /** An embedded third-party contribution the note payload depends on. */
    static final ContributionKey BADGE =
            new ContributionKey(EditorId.parse("sample:badge"), EditorId.parse("sample:rank"));
    /** The locked background resource version this sample locks. */
    static final ResourceRef BACKGROUND = new ResourceRef(id(1), id(11));
    /** A second, older locked version of the very same resource; both must survive as dependencies. */
    static final ResourceRef BACKGROUND_OLD = new ResourceRef(id(1), id(10));

    static final EditorId EMPTY_TITLE = EditorId.parse("sample:documents/empty_title");
    static final EditorId UNREADABLE_SCHEMA = EditorId.parse("sample:documents/unreadable_schema");
    static final EditorId DAMAGED_ENCODING = EditorId.parse("sample:documents/damaged_encoding");
    static final EditorId FOREIGN_CODEC = EditorId.parse("sample:consumer/foreign_codec");
    static final EditorId TITLE_TOO_LONG = EditorId.parse("sample:documents/title_too_long");
    static final EditorId TOO_MANY_TAGS = EditorId.parse("sample:documents/too_many_tags");
    static final EditorId TAG_TOO_LONG = EditorId.parse("sample:documents/tag_too_long");
    static final EditorId ENCODING_TOO_LARGE = EditorId.parse("sample:documents/encoding_too_large");

    /** Domain budgets of the bounded format, enforced by {@code validate} and by {@code encode}. */
    static final int MAX_INPUT_BYTES = 1 << 20;
    static final int MAX_STRING_UNITS = 4096;
    static final int MAX_TAGS = 256;
    /**
     * The bytes the current schema spends before the variable fields: schema int 4, id 16, the title
     * length int 4, the locked background 32 and the tag count int 4.
     */
    private static final long NOTE_FIXED_BYTES = 60L;

    /** One note; every component is either immutable or copied by {@link #snapshot(Note)}. */
    record Note(UUID id, String title, ResourceRef background, List<String> tags) {
        Note {
            tags = List.copyOf(tags);
        }
    }

    static UUID id(long value) {
        return new UUID(0L, value);
    }

    @Override
    public Class<Note> valueType() {
        return Note.class;
    }

    @Override
    public EditorId codecId() {
        return CODEC_ID;
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
        return new Note(id(99), "untitled", BACKGROUND, List.of());
    }

    @Override
    public Note snapshot(Note value) {
        return new Note(value.id(), value.title(), value.background(), List.copyOf(value.tags()));
    }

    @Override
    public byte[] encode(Note value) {
        List<Problem> problems = validate(value);
        if (!problems.isEmpty()) {
            // The bounded format cannot represent this value: refusing here, before the byte buffer
            // exists, beats writing bytes that the reader has to reject.
            throw new IllegalArgumentException("refusing to encode an invalid note: "
                    + problems.get(0).code());
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(schemaVersion());
            writeUuid(out, value.id());
            writeString(out, value.title());
            writeUuid(out, value.background().resourceId());
            writeUuid(out, value.background().versionId());
            out.writeInt(value.tags().size());
            for (String tag : value.tags()) {
                writeString(out, tag);
            }
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
                    new TextContent.Message("sample.documents.empty_title",
                            List.of(new TextContent.LiteralArgument(value.id().toString())))));
        }
        if (value.title().length() > MAX_STRING_UNITS) {
            problems.add(tooLong(TITLE_TOO_LONG, "sample.documents.title_too_long",
                    value.title().length(), MAX_STRING_UNITS));
        }
        if (value.tags().size() > MAX_TAGS) {
            problems.add(tooLong(TOO_MANY_TAGS, "sample.documents.too_many_tags",
                    value.tags().size(), MAX_TAGS));
        }
        for (String tag : value.tags()) {
            if (tag.length() > MAX_STRING_UNITS) {
                problems.add(tooLong(TAG_TOO_LONG, "sample.documents.tag_too_long",
                        tag.length(), MAX_STRING_UNITS));
            }
        }
        long size = encodedSize(value);
        if (size > MAX_INPUT_BYTES) {
            // Every single item may respect its own budget and the sum still be unreadable: the
            // decoder refuses more than MAX_INPUT_BYTES, so the writer must refuse it first.
            problems.add(tooLong(ENCODING_TOO_LARGE, "sample.documents.encoding_too_large",
                    size, MAX_INPUT_BYTES));
        }
        return List.copyOf(problems);
    }

    /**
     * The exact size of the current schema encoding, computed in {@code long} before a single byte is
     * allocated: the fixed header plus two bytes per UTF-16 unit of every string plus the four byte
     * length prefix of every tag.
     */
    private static long encodedSize(Note value) {
        long size = NOTE_FIXED_BYTES + 2L * value.title().length();
        for (String tag : value.tags()) {
            size += 4L + 2L * tag.length();
        }
        return size;
    }

    @Override
    public Note decode(int sourceSchema, byte[] encoding) {
        Objects.requireNonNull(encoding, "encoding");
        if (!readableSchemas().contains(sourceSchema)) {
            // Never "try the current schema first": an unreadable version is reported, not guessed.
            throw new DocumentCodecException(List.of(new Problem(Problem.Severity.ERROR, UNREADABLE_SCHEMA,
                    NOTE_ID.toString(), new TextContent.Message("sample.documents.unreadable_schema",
                            List.of(new TextContent.NumberArgument(BigDecimal.valueOf(sourceSchema)))))));
        }
        if (encoding.length > MAX_INPUT_BYTES) {
            throw damaged("input/too_large", encoding.length);
        }
        if (sourceSchema == 1) {
            // Read-only and in memory: the fixed three-field sample of schema 1 is read and nothing is
            // ever written back in that shape.
            String text = new String(encoding, StandardCharsets.UTF_8);
            String[] fields = text.split("\\|", -1);
            if (fields.length != 3 || !fields[0].equals("1")) {
                throw damaged("legacy/field_count", fields.length);
            }
            try {
                return new Note(UUID.fromString(fields[1]), fields[2], BACKGROUND, List.of());
            } catch (IllegalArgumentException failure) {
                throw damaged("legacy/uuid", 0);
            }
        }
        // Read-only: the caller keeps this array, so it is never written here.
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoding))) {
            if (in.readInt() != sourceSchema) {
                throw damaged("schema_mismatch", 0);
            }
            UUID noteId = readUuid(in);
            String title = readString(in, "title");
            ResourceRef background = new ResourceRef(readUuid(in), readUuid(in));
            int count = in.readInt();
            if (count < 0 || count > MAX_TAGS) {
                throw damaged("tags/bad_count", count);
            }
            if (in.available() < 4L * count) {
                throw damaged("tags/truncated", in.available());
            }
            List<String> tags = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                tags.add(readString(in, "tag"));
            }
            if (in.available() != 0) {
                throw damaged("trailing_bytes", in.available());
            }
            return new Note(noteId, title, background, List.copyOf(tags));
        } catch (IOException truncated) {
            throw damaged("truncated", encoding.length);
        }
    }

    @Override
    public DocumentDependencies dependencies(Note snapshot) {
        // A direct set that is not just a document owner: the locked resource plus the embedded
        // third-party contribution are named explicitly.
        return new DocumentDependencies(Set.of(snapshot.id()), Set.of(snapshot.background()), Set.of(BADGE));
    }

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

    private static String readString(DataInputStream in, String field) throws IOException {
        int units = in.readInt();
        if (units < 0 || units > MAX_STRING_UNITS) {
            // The length is checked before a single character array is allocated.
            throw damaged(field + "/bad_length", units);
        }
        if (in.available() < 2L * units) {
            throw damaged(field + "/truncated", in.available());
        }
        char[] chars = new char[units];
        for (int i = 0; i < units; i++) {
            chars[i] = in.readChar();
        }
        return new String(chars);
    }

    private static Problem tooLong(EditorId code, String key, long actual, long limit) {
        return new Problem(Problem.Severity.ERROR, code, NOTE_ID.toString(),
                new TextContent.Message(key, List.of(
                        new TextContent.NumberArgument(BigDecimal.valueOf(actual)),
                        new TextContent.NumberArgument(BigDecimal.valueOf(limit)))));
    }

    /** A damaged payload is described by a short reason and a number, never by the whole payload. */
    private static DocumentCodecException damaged(String reason, long detail) {
        return new DocumentCodecException(List.of(new Problem(Problem.Severity.ERROR, DAMAGED_ENCODING,
                NOTE_ID.toString(), new TextContent.Message("sample.documents.damaged_encoding",
                        List.of(new TextContent.LiteralArgument(reason),
                                new TextContent.NumberArgument(BigDecimal.valueOf(detail)))))));
    }
}
