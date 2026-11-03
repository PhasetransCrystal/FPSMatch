package net.ptcrys.fpsmatch.editor.core.types;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;

/**
 * The codec contract of the sample fixture: an exact round trip for every legal input, a refusal for
 * every damaged one, and the domain budgets reported before anything is written.
 *
 * <p>The current schema is a bounded binary format, so a delimiter, a newline, Chinese, a surrogate
 * pair and an unpaired surrogate in a title or a tag survive exactly, an empty tag list stays
 * distinct from one empty tag, and a length, a count, a wrong embedded schema, truncation, trailing
 * data or an oversized input is refused with a translatable reason that never quotes the payload.</p>
 */
class SampleDocumentTypeCodecTest {

    private static final String TITLE = "标题|a,b\nsecond line 🎨\uD800";
    private static final List<String> TAGS = List.of("a,b", "", "x|y");

    private static SampleDocumentType.Note note(String title, List<String> tags) {
        return new SampleDocumentType.Note(SampleDocumentType.id(4), title, SampleDocumentType.BACKGROUND_OLD,
                tags);
    }

    @Test
    void theBoundedBinaryCodecRoundTripsEveryLegalInputExactly() {
        SampleDocumentType type = new SampleDocumentType();
        SampleDocumentType.Note value = note(TITLE, TAGS);

        byte[] encoding = type.encode(value);
        assertEquals(2, encoding[3] & 0xFF, "the payload starts with its schema int");
        assertFalse(new String(encoding, StandardCharsets.ISO_8859_1).startsWith("2|"),
                "the current schema is not the old delimiter text");

        SampleDocumentType.Note decoded = type.decode(2, encoding);
        assertEquals(value, decoded, "every field survives exactly, separators included");
        assertEquals(TITLE, decoded.title());
        assertEquals(TAGS, decoded.tags());
        assertArrayEquals(encoding, type.encode(decoded), "bytes -> value -> bytes is exact");
    }

    @Test
    void anEmptyTagListIsNotOneEmptyTag() {
        SampleDocumentType type = new SampleDocumentType();
        byte[] noTags = type.encode(note("tags", List.of()));
        byte[] oneEmptyTag = type.encode(note("tags", List.of("")));

        assertFalse(Arrays.equals(noTags, oneEmptyTag), "the two payloads differ");
        assertEquals(List.of(), type.decode(2, noTags).tags());
        assertEquals(List.of(""), type.decode(2, oneEmptyTag).tags());
        assertArrayEquals(noTags, type.encode(type.decode(2, noTags)));
        assertArrayEquals(oneEmptyTag, type.encode(type.decode(2, oneEmptyTag)));
    }

    @Test
    void theOlderSchemaIsReadInMemoryAndWrittenBackOnlyAsTheCurrentSchema() {
        SampleDocumentType type = new SampleDocumentType();
        byte[] legacy = ("1|" + SampleDocumentType.id(4) + "|legacy note").getBytes(StandardCharsets.UTF_8);
        byte[] before = legacy.clone();

        SampleDocumentType.Note migrated = type.decode(1, legacy);

        assertEquals("legacy note", migrated.title());
        assertEquals(SampleDocumentType.BACKGROUND, migrated.background());
        assertEquals(List.of(), migrated.tags());
        assertArrayEquals(before, legacy, "the caller's array is untouched");
        assertEquals(2, type.encode(migrated)[3] & 0xFF,
                "a migrated value is written back only as the current schema");
    }

    @Test
    void theCodecRefusesBadLengthsTruncationTrailingDataAndAWrongSchema() {
        SampleDocumentType type = new SampleDocumentType();
        byte[] valid = type.encode(note(TITLE, TAGS));

        byte[] negativeLength = valid.clone();
        putInt(negativeLength, 20, -1);
        assertRefused(type, negativeLength, TITLE, "title/bad_length");

        byte[] oversizedLength = valid.clone();
        putInt(oversizedLength, 20, SampleDocumentType.MAX_STRING_UNITS + 1);
        assertRefused(type, oversizedLength, TITLE, "title/bad_length");

        byte[] negativeCount = valid.clone();
        putInt(negativeCount, tagCountOffset(TITLE), -1);
        assertRefused(type, negativeCount, TITLE, "tags/bad_count");

        byte[] oversizedCount = valid.clone();
        putInt(oversizedCount, tagCountOffset(TITLE), SampleDocumentType.MAX_TAGS + 4);
        assertRefused(type, oversizedCount, TITLE, "tags/bad_count");

        byte[] missingTags = valid.clone();
        putInt(missingTags, tagCountOffset(TITLE), 9);
        assertRefused(type, missingTags, TITLE, "tags/truncated");

        assertRefused(type, Arrays.copyOf(valid, valid.length - 1), TITLE, "tag/truncated");
        assertRefused(type, Arrays.copyOf(valid, 26), TITLE, "title/truncated");
        assertRefused(type, Arrays.copyOf(valid, 20), TITLE, "truncated");

        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        assertRefused(type, trailing, TITLE, "trailing_bytes");

        byte[] wrongSchema = valid.clone();
        putInt(wrongSchema, 0, 3);
        assertRefused(type, wrongSchema, TITLE, "schema_mismatch");

        assertRefused(type, new byte[SampleDocumentType.MAX_INPUT_BYTES + 1], TITLE, "input/too_large");

        DocumentCodecException unreadable =
                assertThrows(DocumentCodecException.class, () -> type.decode(7, valid));
        assertEquals(SampleDocumentType.UNREADABLE_SCHEMA, unreadable.problems().get(0).code());
        assertTrue(unreadable.problems().get(0).message() instanceof TextContent.Message);
    }

    @Test
    void validateReportsTheDomainBudgetsAndEncodeRefusesThem() {
        SampleDocumentType type = new SampleDocumentType();

        List<String> manyTags = new ArrayList<>();
        for (int i = 0; i <= SampleDocumentType.MAX_TAGS; i++) {
            manyTags.add("t" + i);
        }
        SampleDocumentType.Note tooManyTags = note("title", List.copyOf(manyTags));
        assertEquals(List.of(SampleDocumentType.TOO_MANY_TAGS),
                type.validate(tooManyTags).stream().map(Problem::code).toList());
        Problem problem = type.validate(tooManyTags).get(0);
        assertEquals(Problem.Severity.ERROR, problem.severity());
        assertEquals(SampleDocumentType.NOTE_ID.toString(), problem.target());
        assertTrue(problem.message() instanceof TextContent.Message message
                && message.key().equals("sample.documents.too_many_tags"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> type.encode(tooManyTags)).getMessage()
                .contains(SampleDocumentType.TOO_MANY_TAGS.toString()), "encode refuses the tag budget");

        SampleDocumentType.Note longTitle = note("t".repeat(SampleDocumentType.MAX_STRING_UNITS + 1), List.of());
        assertEquals(List.of(SampleDocumentType.TITLE_TOO_LONG),
                type.validate(longTitle).stream().map(Problem::code).toList());
        assertThrows(IllegalArgumentException.class, () -> type.encode(longTitle));

        SampleDocumentType.Note longTag = note("title",
                List.of("t".repeat(SampleDocumentType.MAX_STRING_UNITS + 1)));
        assertEquals(List.of(SampleDocumentType.TAG_TOO_LONG),
                type.validate(longTag).stream().map(Problem::code).toList());
        assertThrows(IllegalArgumentException.class, () -> type.encode(longTag));

        assertEquals(List.of(), type.validate(note("title", List.of("ok", ""))),
                "a legal value has no problems");
    }

    @Test
    void individuallyLegalItemsCanStillExceedTheCumulativeEncodingBudget() {
        SampleDocumentType type = new SampleDocumentType();
        // Title 5, 128 tags of the per-item maximum: every item respects its own budget.
        SampleDocumentType.Note value = note("title", budgetTags(SampleDocumentType.MAX_STRING_UNITS));

        assertEquals(128, value.tags().size(), "the tag count itself stays inside its budget");
        assertEquals(SampleDocumentType.MAX_STRING_UNITS, value.tags().get(127).length(),
                "every tag itself stays at its budget");
        // 60 fixed + 2 * 5 title + 128 * 4 tag length prefixes + 2 * 128 * 4096 tag units.
        assertOverTheEncodingBudget(type, value, SampleDocumentType.MAX_INPUT_BYTES + 582L);
    }

    @Test
    void exactlyOneMebibyteRoundTripsAndTwoBytesMoreIsRefused() {
        SampleDocumentType type = new SampleDocumentType();
        // 127 maximum-length tags plus 3805 units land on the limit exactly, so two more do not fit.
        SampleDocumentType.Note exact = note("title", budgetTags(3805));
        assertEquals(List.of(), type.validate(exact), "one MiB exactly is still representable");

        byte[] encoding = type.encode(exact);
        assertEquals(SampleDocumentType.MAX_INPUT_BYTES, encoding.length, "the payload is exactly 1 MiB");
        assertEquals(exact, type.decode(2, encoding), "exactly 1 MiB round trips exactly");
        assertArrayEquals(encoding, type.encode(type.decode(2, encoding)), "bytes -> value -> bytes is exact");

        assertOverTheEncodingBudget(type, note("title", budgetTags(3806)),
                SampleDocumentType.MAX_INPUT_BYTES + 2L);
        assertRefused(type, Arrays.copyOf(encoding, SampleDocumentType.MAX_INPUT_BYTES + 1), "title",
                "input/too_large");
    }

    /**
     * The counterexample's tag list: {@code MAX_TAGS / 2} tags, the first {@code MAX_TAGS / 2 - 1} at
     * the per-item maximum, the last {@code lastLength} units long. Half the tag budget of maximum
     * length is already a mebibyte of UTF-16 units.
     */
    private static List<String> budgetTags(int lastLength) {
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < SampleDocumentType.MAX_TAGS / 2 - 1; i++) {
            tags.add("t".repeat(SampleDocumentType.MAX_STRING_UNITS));
        }
        tags.add("t".repeat(lastLength));
        return List.copyOf(tags);
    }

    /** Asserts the cumulative budget is the only problem and that encode refuses before writing. */
    private static void assertOverTheEncodingBudget(SampleDocumentType type, SampleDocumentType.Note value,
            long expectedSize) {
        List<Problem> problems = type.validate(value);
        assertEquals(List.of(SampleDocumentType.ENCODING_TOO_LARGE),
                problems.stream().map(Problem::code).toList(),
                "every single item is legal, only the sum is not");
        Problem problem = problems.get(0);
        assertEquals(Problem.Severity.ERROR, problem.severity());
        assertEquals(SampleDocumentType.NOTE_ID.toString(), problem.target());
        assertEquals(new TextContent.Message("sample.documents.encoding_too_large", List.of(
                        new TextContent.NumberArgument(BigDecimal.valueOf(expectedSize)),
                        new TextContent.NumberArgument(BigDecimal.valueOf(SampleDocumentType.MAX_INPUT_BYTES)))),
                problem.message(), "the report names the exact size and the limit");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> type.encode(value)).getMessage()
                        .contains(SampleDocumentType.ENCODING_TOO_LARGE.toString()),
                "encode refuses the cumulative overrun");
    }

    /** Asserts one damaged payload is refused by reason, without the array being written. */
    private static void assertRefused(SampleDocumentType type, byte[] encoding, String title, String reason) {
        byte[] before = encoding.clone();

        DocumentCodecException failure =
                assertThrows(DocumentCodecException.class, () -> type.decode(2, encoding));

        assertEquals(SampleDocumentType.DAMAGED_ENCODING, failure.problems().get(0).code());
        TextContent.Message message = (TextContent.Message) failure.problems().get(0).message();
        assertEquals("sample.documents.damaged_encoding", message.key());
        assertTrue(message.arguments().stream().anyMatch(argument ->
                        argument instanceof TextContent.LiteralArgument literal && literal.value().equals(reason)),
                "the refusal names the reason " + reason);
        assertFalse(renders(failure).contains(title), "the payload is never quoted back");
        assertArrayEquals(before, encoding, "decode must not write the caller's array");
    }

    /** Renders every code, key and argument of a failure so a test can look for a leaked payload. */
    private static String renders(DocumentCodecException failure) {
        StringBuilder text = new StringBuilder();
        for (Problem problem : failure.problems()) {
            text.append(problem.code()).append(' ').append(problem.message()).append(' ');
        }
        return text.toString();
    }

    /** Offset of the tag count in the sample payload: schema, id, title, background. */
    private static int tagCountOffset(String title) {
        return 4 + 16 + 4 + 2 * title.length() + 16 + 16;
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }
}
