package net.ptcrys.fpsmatch.editor.common.model.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import net.ptcrys.fpsmatch.editor.common.model.DefinitionRef;
import net.ptcrys.fpsmatch.editor.common.model.Ids;
import net.ptcrys.fpsmatch.editor.common.model.geometry.BoxCorners;
import net.ptcrys.fpsmatch.editor.common.model.geometry.IntPosition;
import net.ptcrys.fpsmatch.editor.common.model.map.MapDefinition;
import net.ptcrys.fpsmatch.editor.common.model.map.WireExtras;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.CfgState;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.CommitReceipt;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.DefinitionEnvelope;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.FingerprintInputs;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.FingerprintTarget;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.IdKind;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.IdMapEntry;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.MigrationRecord;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.MissingCfgPolicy;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.OperationKind;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.SourceDocument;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.SourceHash;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.SourceHashes;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.SourceKind;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.SourceScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Formal contract tests for the R06.2a5 definition envelope candidate
 * {@link PersistenceValues.DefinitionEnvelope}.
 *
 * <p><strong>What this class is.</strong> These are the formal, focused JUnit tests of the single
 * envelope candidate. They cover exactly the vectors the task card fixes: the null and format rules
 * of every required component, the digest length and alphabet rules at the boundary and at every one
 * of the 64 character positions, the five non object member shapes, the complete revision range, the
 * reference discipline that keeps the caller's own objects, the defensive copy of the receipt list
 * with its one {@code size()} call, its bounded single traversal, its refusal of a dishonest size and
 * of a null element, the publication of an unmodifiable list that keeps order, duplicates and
 * element references, the standard record value semantics, and a thousand sample deterministic
 * oracle whose expectations come from a local model built before production is called.</p>
 *
 * <p><strong>What this class is not.</strong> A green run here is author side evidence only. It is
 * not the P06-ENVELOPE-VALUES gate, which only the primary agent can grant, and it proves nothing
 * about the complete envelope codec, about a storage container, about a transaction or about a
 * running product. No test here claims wire round-tripping, cross record authentication, fingerprint
 * verification, reserved key handling, metadata budgets or age. The relations this record
 * deliberately leaves unchecked - the identity of {@code target} against the identity inside
 * {@code payload}, the scope of a migration, the target, revision and digest relations of a receipt -
 * are exercised as <em>structurally accepted but inconsistent</em> candidates, and the class says so
 * instead of implying that such an envelope is committable.</p>
 *
 * <p><strong>Oracle independence.</strong> The thousand sample oracle generates every real field of a
 * candidate first - the target reference, the revision, the payload digest, the payload, the optional
 * migration, the member container and the receipt list - and only then computes its accept or reject
 * verdict with a local structural rule that reads those assembled fields: the revision must be one or
 * greater, the digest must be exactly 64 lower case hexadecimal characters, the frozen member shape
 * must be an object, and the receipt list must be a plain list within the budget without a null
 * element. The verdict is never a function of a combination number, of an injection flag, of any
 * production constant, or of what production answered; the constructor is only called afterwards and
 * the observed outcome is compared against the pre-computed expectation. No sample is selected,
 * filtered or discarded on the basis of what production answered, and the expected verdict is never
 * written as the negation of the observed one.</p>
 *
 * <p><strong>Assertion discipline.</strong> Every rejection asserts {@link IllegalArgumentException}
 * itself, so a {@code NullPointerException} can never be mistaken for a contract rejection, and a
 * message assertion checks only a stable fragment such as the member name. The hostile lists used to
 * pin the traversal order raise {@link AssertionError}, never an exception production could catch, so
 * a rejected over budget list proves that no element accessor ran at all. Every retained reference is
 * asserted with {@code assertSame}, and the published receipt list is asserted with
 * {@code assertNotSame} against the input, which is what makes the defence copy observable.</p>
 */
@DisplayName("R06.2a5 definition envelope contract")
class EnvelopeValuesContractTest {

    private static final int RECEIPT_BUDGET = 1024;
    private static final long RECORDED_AT = 1_700_000_000_000L;
    private static final String MODE = "ptcrys:defuse";
    private static final String DIMENSION = "minecraft:overworld";

    private static final Ids.WorldId WORLD_ID = new Ids.WorldId(uuidAt(1L));
    private static final Ids.DefinitionId DEFINITION_ID = new Ids.DefinitionId(uuidAt(3L));
    private static final Ids.DefinitionId OTHER_DEFINITION_ID = new Ids.DefinitionId(uuidAt(4L));

    private static final ExactJson.FrozenJson EMPTY_OBJECT = ExactJson.parse("{}");
    private static final WireExtras.MapExtras MAP_EXTRAS = new WireExtras.MapExtras(
            EMPTY_OBJECT, EMPTY_OBJECT, EMPTY_OBJECT, EMPTY_OBJECT);

    // ------------------------------------------------------------------
    // deterministic identities
    // ------------------------------------------------------------------

    /** A distinct, non null UUID for every tag; two different tags never share a UUID. */
    private static UUID uuidAt(long tag) {
        return new UUID(0x1000000000000000L + tag * 0x0001000100010001L,
                0x2000000000000000L + tag);
    }

    /** A legal 64 character lower case hexadecimal digest derived from one tag. */
    private static String hexDigest(long tag) {
        String tail = String.format("%016x", tag);
        return tail + tail + tail + tail;
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static DefinitionRef definitionRef(Ids.DefinitionId definitionId) {
        return new DefinitionRef(WORLD_ID, definitionId);
    }

    /**
     * A minimal but complete validated map definition candidate.
     *
     * <p>It carries no team, spawn, region or resource, which the aggregate accepts, so a rejection
     * observed by these tests can never be blamed on the payload.</p>
     */
    private static MapDefinition payload(Ids.DefinitionId definitionId) {
        return new MapDefinition(definitionId, WORLD_ID, MODE, DIMENSION, "map", "desc",
                new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1)),
                List.of(), List.of(), List.of(), EMPTY_OBJECT, List.of(), EMPTY_OBJECT, MAP_EXTRAS);
    }

    /** A structurally complete committed receipt whose actor, request and payload agree. */
    private static CommitReceipt receipt(long tag) {
        UUID actor = uuidAt(5000L + tag);
        Ids.RequestId requestId = new Ids.RequestId(uuidAt(7000L + tag));
        String digest = hexDigest(9000L + tag);
        FingerprintInputs inputs = new FingerprintInputs(actor,
                new FingerprintTarget(definitionRef(DEFINITION_ID), EMPTY_OBJECT), requestId, 1L,
                digest, OperationKind.SAVE_MAP, Optional.empty(), EMPTY_OBJECT);
        return new CommitReceipt(actor, requestId, hexDigest(11_000L + tag), inputs, 2L, digest, true,
                RECORDED_AT + tag, EMPTY_OBJECT);
    }

    /** A structurally complete migration candidate in its simplest legal cfg combination. */
    private static MigrationRecord migration(long tag) {
        SourceScope scope = new SourceScope(new Ids.WorldId(uuidAt(13_000L + tag)), MODE,
                "legacy-key-" + tag, EMPTY_OBJECT);
        SourceHashes hashes = new SourceHashes(
                new SourceHash(SourceKind.FILE_BYTES, hexDigest(tag), hexDigest(tag + 1),
                        EMPTY_OBJECT),
                Optional.empty(), EMPTY_OBJECT);
        IdMapEntry definition = new IdMapEntry(SourceDocument.JSON, IdKind.DEFINITION, List.of(),
                uuidAt(15_000L + tag), EMPTY_OBJECT);
        return new MigrationRecord(scope, hashes, CfgState.ABSENT,
                Optional.of(MissingCfgPolicy.REJECT), List.of(definition), EMPTY_OBJECT,
                Optional.empty(), EMPTY_OBJECT);
    }

    /** The envelope under test with an empty migration and an empty receipt list. */
    private static DefinitionEnvelope envelope(long revision, String payloadDigest,
            ExactJson.FrozenJson members, List<CommitReceipt> receipts) {
        return new DefinitionEnvelope(definitionRef(DEFINITION_ID), revision, payloadDigest,
                payload(DEFINITION_ID), Optional.empty(), receipts, members);
    }

    /** The envelope under test with only the payload digest varied. */
    private static DefinitionEnvelope envelopeWithDigest(String payloadDigest) {
        return envelope(1L, payloadDigest, EMPTY_OBJECT, List.of());
    }

    /** Asserts a rejection and returns it, so the caller can inspect only a stable message fragment. */
    private static IllegalArgumentException rejection(Executable executable) {
        return assertThrows(IllegalArgumentException.class, executable);
    }

    // ------------------------------------------------------------------
    // retention and null rules
    // ------------------------------------------------------------------

    @Test
    @DisplayName("keeps every accepted component as the caller's own reference")
    void keepsEveryAcceptedComponentByReference() {
        DefinitionRef target = definitionRef(DEFINITION_ID);
        String payloadDigest = hexDigest(21L);
        MapDefinition payload = payload(DEFINITION_ID);
        Optional<MigrationRecord> migration = Optional.of(migration(21L));
        CommitReceipt receipt = receipt(21L);
        ExactJson.FrozenJson members = ExactJson.parse("{\"a\":1}");
        List<CommitReceipt> receipts = new ArrayList<>(List.of(receipt));

        DefinitionEnvelope envelope = new DefinitionEnvelope(target, 7L, payloadDigest, payload,
                migration, receipts, members);

        assertSame(target, envelope.target());
        assertEquals(7L, envelope.revision());
        assertSame(payloadDigest, envelope.payloadDigest());
        assertSame(payload, envelope.payload());
        assertSame(migration, envelope.migration());
        assertSame(migration.orElseThrow(), envelope.migration().orElseThrow());
        assertEquals(1, envelope.receipts().size());
        assertSame(receipt, envelope.receipts().get(0));
        assertSame(members, envelope.members());
        assertEquals(new DefinitionEnvelope(target, 7L, payloadDigest, payload, migration, receipts,
                members), envelope);
    }

    @Test
    @DisplayName("keeps the empty migration Optional itself, not a new empty Optional")
    void keepsEmptyOptionalInstanceByReference() {
        Optional<MigrationRecord> empty = Optional.empty();
        DefinitionEnvelope envelope = new DefinitionEnvelope(definitionRef(DEFINITION_ID), 1L,
                hexDigest(22L), payload(DEFINITION_ID), empty, List.of(), EMPTY_OBJECT);

        assertSame(empty, envelope.migration());
        assertTrue(envelope.migration().isEmpty());
    }

    @Test
    @DisplayName("refuses every null required reference with an argument error")
    void rejectsNullRequiredReferences() {
        DefinitionRef target = definitionRef(DEFINITION_ID);
        String payloadDigest = hexDigest(23L);
        MapDefinition payload = payload(DEFINITION_ID);
        Optional<MigrationRecord> migration = Optional.empty();
        List<CommitReceipt> receipts = List.of();
        ExactJson.FrozenJson members = EMPTY_OBJECT;

        assertTrue(rejection(() -> new DefinitionEnvelope(null, 1L, payloadDigest, payload, migration,
                receipts, members)).getMessage().contains("target"));
        assertTrue(rejection(() -> new DefinitionEnvelope(target, 1L, payloadDigest, null, migration,
                receipts, members)).getMessage().contains("payload"));
        assertTrue(rejection(() -> new DefinitionEnvelope(target, 1L, payloadDigest, payload, null,
                receipts, members)).getMessage().contains("migration"));
        assertTrue(rejection(() -> new DefinitionEnvelope(target, 1L, payloadDigest, payload, migration,
                null, members)).getMessage().contains("receipts"));
        assertTrue(rejection(() -> new DefinitionEnvelope(target, 1L, payloadDigest, payload, migration,
                receipts, null)).getMessage().contains("members"));
    }

    // ------------------------------------------------------------------
    // digest rules
    // ------------------------------------------------------------------

    @Test
    @DisplayName("refuses a null or wrong length payload digest")
    void rejectsDigestOfWrongLength() {
        assertTrue(rejection(() -> envelopeWithDigest(null)).getMessage().contains("payloadDigest"));
        for (int length : new int[] {0, 1, 16, 63, 65, 128}) {
            String candidate = "a".repeat(length);
            assertEquals(length, candidate.length());
            rejection(() -> envelopeWithDigest(candidate));
        }
        assertEquals(64, hexDigest(1L).length());
        assertEquals(hexDigest(1L), envelope(1L, hexDigest(1L), EMPTY_OBJECT, List.of())
                .payloadDigest());
    }

    @Test
    @DisplayName("refuses an upper case hexadecimal character at each of the 64 positions")
    void rejectsUppercaseHexAtEveryPosition() {
        assertBadCharacterAtEveryPosition('A', Character::toUpperCase);
    }

    @Test
    @DisplayName("refuses a non hexadecimal letter at each of the 64 positions")
    void rejectsOutOfAlphabetCharacterAtEveryPosition() {
        assertBadCharacterAtEveryPosition('g', c -> 'g');
    }

    @Test
    @DisplayName("refuses a non ASCII character at each of the 64 positions")
    void rejectsNonAsciiCharacterAtEveryPosition() {
        assertBadCharacterAtEveryPosition('\u00e9', c -> '\u00e9');
    }

    @Test
    @DisplayName("refuses a control character at each of the 64 positions")
    void rejectsControlCharacterAtEveryPosition() {
        assertBadCharacterAtEveryPosition('\u0001', c -> '\u0001');
    }

    /**
     * Replaces each of the 64 digest positions with an illegal character and requires a rejection.
     *
     * @param fallback the replacement used when the mapping leaves a digit unchanged
     * @param mapping the per character replacement rule
     */
    private static void assertBadCharacterAtEveryPosition(char fallback,
            java.util.function.IntUnaryOperator mapping) {
        String legal = hexDigest(31L);
        for (int position = 0; position < 64; position++) {
            char replacement = (char) mapping.applyAsInt(legal.charAt(position));
            if (replacement == legal.charAt(position)) {
                replacement = fallback;
            }
            String mutated = legal.substring(0, position) + replacement + legal.substring(position + 1);
            assertEquals(64, mutated.length());
            assertNotEquals(legal, mutated);
            final String candidate = mutated;
            assertTrue(rejection(() -> envelopeWithDigest(candidate)).getMessage()
                    .contains("payloadDigest"));
        }
    }

    // ------------------------------------------------------------------
    // member container shapes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("refuses a null member container or any non object frozen shape")
    void rejectsNonObjectMemberShapes() {
        assertTrue(rejection(() -> envelope(1L, hexDigest(41L), null, List.of())).getMessage()
                .contains("members"));
        for (String shape : new String[] {"[]", "7", "\"x\"", "true", "null", "[1,2,3]"}) {
            ExactJson.FrozenJson frozen = ExactJson.parse(shape);
            rejection(() -> envelope(1L, hexDigest(41L), frozen, List.of()));
        }
    }

    @Test
    @DisplayName("keeps every legal object member container by reference, including extreme text")
    void keepsLegalMemberContainersByReference() {
        String[] legalShapes = {
            "{}",
            "{\"\":1}",
            "{\"\u672a\u6765\":[1,{\"a\":null}]}",
            "{\"key\":\"\uD83D\uDE00\uD83C\uDF0D\"}",
            "{\"n\":12345678901234567890123456789012345678901234567890}",
            "{\"nested\":{\"deep\":[[[]]]}}",
        };
        for (String shape : legalShapes) {
            ExactJson.FrozenJson frozen = ExactJson.parse(shape);
            DefinitionEnvelope envelope = envelope(1L, hexDigest(42L), frozen, List.of());
            assertSame(frozen, envelope.members());
            assertEquals(frozen, envelope.members());
        }
    }

    // ------------------------------------------------------------------
    // revision range
    // ------------------------------------------------------------------

    @Test
    @DisplayName("accepts revision one and Long.MAX_VALUE and refuses every lower value")
    void acceptsLegalRevisionRangeAndRefusesBelowOne() {
        assertEquals(1L, envelope(1L, hexDigest(51L), EMPTY_OBJECT, List.of()).revision());
        assertEquals(Long.MAX_VALUE,
                envelope(Long.MAX_VALUE, hexDigest(52L), EMPTY_OBJECT, List.of()).revision());
        for (long revision : new long[] {-1L, Long.MIN_VALUE, -1024L, 0L}) {
            final long candidate = revision;
            assertTrue(rejection(() -> envelope(candidate, hexDigest(53L), EMPTY_OBJECT, List.of()))
                    .getMessage().contains("revision"));
        }
    }

    // ------------------------------------------------------------------
    // receipt list: copy, order and publication
    // ------------------------------------------------------------------

    @Test
    @DisplayName("copies the receipt list so a later source mutation cannot change the envelope")
    void copiesReceiptsDefensivelyAgainstLaterSourceMutation() {
        CommitReceipt first = receipt(61L);
        CommitReceipt second = receipt(62L);
        List<CommitReceipt> source = new ArrayList<>(List.of(first, second));

        DefinitionEnvelope envelope = envelope(1L, hexDigest(61L), EMPTY_OBJECT, source);

        assertNotSame(source, envelope.receipts());
        assertEquals(List.of(first, second), envelope.receipts());
        source.clear();
        assertEquals(List.of(first, second), envelope.receipts());
        source.add(receipt(63L));
        assertEquals(2, envelope.receipts().size());
        source.set(0, receipt(64L));
        assertSame(first, envelope.receipts().get(0));
    }

    @Test
    @DisplayName("publishes an unmodifiable receipt list")
    void publishesUnmodifiableReceiptList() {
        DefinitionEnvelope envelope = envelope(1L, hexDigest(62L), EMPTY_OBJECT,
                List.of(receipt(65L), receipt(66L)));

        assertThrows(UnsupportedOperationException.class, () -> envelope.receipts().add(receipt(67L)));
        assertThrows(UnsupportedOperationException.class, () -> envelope.receipts().remove(0));
        assertThrows(UnsupportedOperationException.class,
                () -> envelope.receipts().set(0, receipt(68L)));
        assertThrows(UnsupportedOperationException.class, () -> envelope.receipts().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> envelope.receipts().addAll(List.of(receipt(69L))));
        assertEquals(2, envelope.receipts().size());
    }

    @Test
    @DisplayName("keeps receipt order, duplicates and every element reference")
    void keepsReceiptOrderDuplicatesAndElementReferences() {
        CommitReceipt first = receipt(71L);
        CommitReceipt second = receipt(72L);
        CommitReceipt equalCopy = receipt(71L);

        DefinitionEnvelope envelope = envelope(1L, hexDigest(71L), EMPTY_OBJECT,
                List.of(first, first, second, equalCopy));

        assertEquals(4, envelope.receipts().size());
        assertSame(first, envelope.receipts().get(0));
        assertSame(first, envelope.receipts().get(1));
        assertSame(second, envelope.receipts().get(2));
        assertSame(equalCopy, envelope.receipts().get(3));
        assertNotSame(first, equalCopy);
        assertEquals(first, equalCopy);
        assertEquals(List.of(first, first, second, equalCopy), envelope.receipts());
    }

    @Test
    @DisplayName("accepts an empty list, a single receipt and the full budget of 1024 receipts")
    void acceptsZeroOneAndFullReceiptLists() {
        CommitReceipt shared = receipt(81L);
        List<CommitReceipt> full = Collections.nCopies(RECEIPT_BUDGET, shared);

        assertEquals(0, envelope(1L, hexDigest(81L), EMPTY_OBJECT, List.of()).receipts().size());
        assertEquals(1, envelope(1L, hexDigest(82L), EMPTY_OBJECT, List.of(shared)).receipts().size());

        DefinitionEnvelope envelope = envelope(1L, hexDigest(83L), EMPTY_OBJECT, full);
        assertEquals(RECEIPT_BUDGET, envelope.receipts().size());
        assertSame(shared, envelope.receipts().get(0));
        assertSame(shared, envelope.receipts().get(RECEIPT_BUDGET - 1));
        assertNotSame(full, envelope.receipts());
    }

    // ------------------------------------------------------------------
    // receipt list: hostile implementations
    // ------------------------------------------------------------------

    @Test
    @DisplayName("refuses a negative or over budget declared size without touching one element")
    void rejectsNegativeAndOversizedDeclaredSizeWithoutTouchingElements() {
        for (int declared : new int[] {-1, Integer.MIN_VALUE, RECEIPT_BUDGET + 1, 4096,
            Integer.MAX_VALUE}) {
            final int size = declared;
            TripwireList<CommitReceipt> hostile = new TripwireList<>(size, List.of(receipt(91L)));
            IllegalArgumentException failure = rejection(
                    () -> envelope(1L, hexDigest(91L), EMPTY_OBJECT, hostile));
            assertTrue(failure.getMessage().contains("receipts"), failure.getMessage());
            assertEquals(1, hostile.sizeCalls(), "size must be read exactly once");
            assertEquals(0, hostile.iteratorCalls(), "no iterator may be created");
            assertEquals(0, hostile.elementAccesses(), "no element accessor may run");
        }
    }

    @Test
    @DisplayName("refuses a list that yields fewer elements than it declared")
    void rejectsShorterTraversalThanDeclared() {
        TripwireList<CommitReceipt> hostile =
                new TripwireList<>(3, List.of(receipt(92L), receipt(93L)));
        IllegalArgumentException failure =
                rejection(() -> envelope(1L, hexDigest(92L), EMPTY_OBJECT, hostile));
        assertTrue(failure.getMessage().contains("receipts"), failure.getMessage());
        assertEquals(1, hostile.sizeCalls());
        assertEquals(1, hostile.iteratorCalls());
        assertEquals(0, hostile.elementAccesses());
    }

    @Test
    @DisplayName("refuses a list that still yields an element after the declared count")
    void rejectsLongerTraversalThanDeclared() {
        TripwireList<CommitReceipt> hostile = new TripwireList<>(2,
                List.of(receipt(94L), receipt(95L), receipt(96L)));
        IllegalArgumentException failure =
                rejection(() -> envelope(1L, hexDigest(94L), EMPTY_OBJECT, hostile));
        assertTrue(failure.getMessage().contains("receipts"), failure.getMessage());
        assertEquals(1, hostile.sizeCalls());
        assertEquals(1, hostile.iteratorCalls());
    }

    @Test
    @DisplayName("refuses a null receipt element with an argument error rather than a null pointer")
    void rejectsNullReceiptElement() {
        List<CommitReceipt> withNull = new ArrayList<>();
        withNull.add(receipt(97L));
        withNull.add(null);
        IllegalArgumentException failure =
                rejection(() -> envelope(1L, hexDigest(97L), EMPTY_OBJECT, withNull));
        assertTrue(failure.getMessage().contains("receipts"), failure.getMessage());
        rejection(() -> envelope(1L, hexDigest(98L), EMPTY_OBJECT, Collections.singletonList(null)));
    }

    @Test
    @DisplayName("reads the declared size exactly once and ignores any later change of it")
    void readsDeclaredSizeExactlyOnce() {
        CommitReceipt single = receipt(101L);
        ChangingSizeList<CommitReceipt> hostile =
                new ChangingSizeList<>(1, 999, List.of(single));

        DefinitionEnvelope envelope = envelope(1L, hexDigest(101L), EMPTY_OBJECT, hostile);

        assertEquals(1, hostile.sizeCalls(), "a second size() call is forbidden");
        assertEquals(1, envelope.receipts().size());
        assertSame(single, envelope.receipts().get(0));
    }

    @Test
    @DisplayName("bounds the traversal to the declared size even against an endless iterator")
    void boundsTraversalToDeclaredSizeEvenWithEndlessIterator() {
        EndlessList<CommitReceipt> hostile = new EndlessList<>(2, receipt(111L));
        IllegalArgumentException failure =
                rejection(() -> envelope(1L, hexDigest(111L), EMPTY_OBJECT, hostile));
        assertTrue(failure.getMessage().contains("receipts"), failure.getMessage());
        assertEquals(2, hostile.nextCalls(), "at most declared + 1 elements may be read");
        assertTrue(hostile.nextCalls() <= 2 + 1);
    }

    @Test
    @DisplayName("never asks the source list for an array or for an indexed element")
    void neverRequestsArrayOrIndexedElementFromSourceList() {
        TripwireList<CommitReceipt> hostile =
                new TripwireList<>(2, List.of(receipt(121L), receipt(122L)));
        DefinitionEnvelope envelope = envelope(1L, hexDigest(121L), EMPTY_OBJECT, hostile);

        assertEquals(2, envelope.receipts().size());
        assertEquals(0, hostile.elementAccesses(), "toArray and get must stay untouched");
        assertEquals(1, hostile.sizeCalls());
        assertEquals(1, hostile.iteratorCalls());
    }

    // ------------------------------------------------------------------
    // hostile list implementations
    // ------------------------------------------------------------------

    /**
     * A list whose size is a lie or whose element accessors are tripwires.
     *
     * <p>{@code get} and every {@code toArray} raise {@link AssertionError}, never an exception a
     * production guard could swallow, and the counters record how often {@code size()} and
     * {@code iterator()} were called, so a test can prove that a rejected list was never traversed.</p>
     */
    private static final class TripwireList<T> extends AbstractList<T> {

        private final int declaredSize;
        private final List<T> elements;
        private final int[] sizeCalls = new int[1];
        private final int[] iteratorCalls = new int[1];
        private final int[] elementAccesses = new int[1];

        private TripwireList(int declaredSize, List<T> elements) {
            this.declaredSize = declaredSize;
            this.elements = List.copyOf(elements);
        }

        @Override
        public int size() {
            sizeCalls[0]++;
            return declaredSize;
        }

        @Override
        public T get(int index) {
            elementAccesses[0]++;
            throw new AssertionError("get must not be called");
        }

        @Override
        public Object[] toArray() {
            elementAccesses[0]++;
            throw new AssertionError("toArray must not be called");
        }

        @Override
        public <E> E[] toArray(E[] target) {
            elementAccesses[0]++;
            throw new AssertionError("toArray must not be called");
        }

        @Override
        public Iterator<T> iterator() {
            iteratorCalls[0]++;
            return elements.iterator();
        }

        private int sizeCalls() {
            return sizeCalls[0];
        }

        private int iteratorCalls() {
            return iteratorCalls[0];
        }

        private int elementAccesses() {
            return elementAccesses[0];
        }
    }

    /** A list that reports one size on the first call and a much larger one afterwards. */
    private static final class ChangingSizeList<T> extends AbstractList<T> {

        private final int firstSize;
        private final int laterSize;
        private final List<T> elements;
        private final int[] sizeCalls = new int[1];

        private ChangingSizeList(int firstSize, int laterSize, List<T> elements) {
            this.firstSize = firstSize;
            this.laterSize = laterSize;
            this.elements = List.copyOf(elements);
        }

        @Override
        public int size() {
            sizeCalls[0]++;
            return sizeCalls[0] == 1 ? firstSize : laterSize;
        }

        @Override
        public T get(int index) {
            throw new AssertionError("get must not be called");
        }

        @Override
        public Iterator<T> iterator() {
            return elements.iterator();
        }

        private int sizeCalls() {
            return sizeCalls[0];
        }
    }

    /** A list with a finite declared size but an iterator that never reports the end. */
    private static final class EndlessList<T> extends AbstractList<T> {

        private final int declaredSize;
        private final T element;
        private final int[] nextCalls = new int[1];

        private EndlessList(int declaredSize, T element) {
            this.declaredSize = declaredSize;
            this.element = element;
        }

        @Override
        public int size() {
            return declaredSize;
        }

        @Override
        public T get(int index) {
            throw new AssertionError("get must not be called");
        }

        @Override
        public Iterator<T> iterator() {
            return new Iterator<T>() {

                @Override
                public boolean hasNext() {
                    return true;
                }

                @Override
                public T next() {
                    nextCalls[0]++;
                    if (nextCalls[0] > declaredSize + 4) {
                        throw new NoSuchElementException("the traversal was not bounded");
                    }
                    return element;
                }
            };
        }

        private int nextCalls() {
            return nextCalls[0];
        }
    }

    // ------------------------------------------------------------------
    // standard value semantics
    // ------------------------------------------------------------------

    /** A fully populated envelope used by the value semantics tests. */
    private static DefinitionEnvelope fullEnvelope(long tag) {
        return new DefinitionEnvelope(definitionRef(DEFINITION_ID), tag, hexDigest(20_000L + tag),
                payload(DEFINITION_ID), Optional.of(migration(tag)), List.of(receipt(tag)),
                ExactJson.parse("{\"tag\":" + tag + "}"));
    }

    /** A structurally complete receipt whose fingerprint target is one chosen definition. */
    private static CommitReceipt receiptFor(Ids.DefinitionId fingerprintDefinition, long tag) {
        UUID actor = uuidAt(5000L + tag);
        Ids.RequestId requestId = new Ids.RequestId(uuidAt(7000L + tag));
        String digest = hexDigest(9000L + tag);
        FingerprintInputs inputs = new FingerprintInputs(actor,
                new FingerprintTarget(definitionRef(fingerprintDefinition), EMPTY_OBJECT), requestId,
                1L, digest, OperationKind.SAVE_MAP, Optional.empty(), EMPTY_OBJECT);
        return new CommitReceipt(actor, requestId, hexDigest(11_000L + tag), inputs, 2L, digest, true,
                RECORDED_AT + tag, EMPTY_OBJECT);
    }

    @Test
    @DisplayName("follows the standard record value semantics for an equal independent copy")
    void followsStandardValueSemanticsForEqualCopy() {
        DefinitionEnvelope base = fullEnvelope(1L);
        DefinitionEnvelope copy = fullEnvelope(1L);

        assertNotSame(base, copy);
        assertEquals(base, copy);
        assertEquals(base.hashCode(), copy.hashCode());
        assertEquals(base, base);
        assertEquals(base.toString(), copy.toString());
        assertNotEquals(base, fullEnvelope(2L));
    }

    @Test
    @DisplayName("distinguishes every component through a legal substitution")
    void distinguishesEveryComponentByLegalSubstitution() {
        DefinitionEnvelope base = fullEnvelope(1L);

        DefinitionEnvelope otherTarget = new DefinitionEnvelope(definitionRef(OTHER_DEFINITION_ID),
                base.revision(), base.payloadDigest(), base.payload(), base.migration(),
                base.receipts(), base.members());
        assertNotEquals(base, otherTarget);

        DefinitionEnvelope otherRevision = new DefinitionEnvelope(base.target(),
                base.revision() + 1L, base.payloadDigest(), base.payload(), base.migration(),
                base.receipts(), base.members());
        assertNotEquals(base, otherRevision);

        DefinitionEnvelope otherDigest = new DefinitionEnvelope(base.target(), base.revision(),
                hexDigest(20_999L), base.payload(), base.migration(), base.receipts(), base.members());
        assertNotEquals(base, otherDigest);

        DefinitionEnvelope otherPayload = new DefinitionEnvelope(base.target(), base.revision(),
                base.payloadDigest(), payload(OTHER_DEFINITION_ID), base.migration(), base.receipts(),
                base.members());
        assertNotEquals(base, otherPayload);

        DefinitionEnvelope absentMigration = new DefinitionEnvelope(base.target(), base.revision(),
                base.payloadDigest(), base.payload(), Optional.empty(), base.receipts(),
                base.members());
        assertNotEquals(base, absentMigration);

        DefinitionEnvelope otherReceipts = new DefinitionEnvelope(base.target(), base.revision(),
                base.payloadDigest(), base.payload(), base.migration(), List.of(receipt(2L)),
                base.members());
        assertNotEquals(base, otherReceipts);

        DefinitionEnvelope otherMembers = new DefinitionEnvelope(base.target(), base.revision(),
                base.payloadDigest(), base.payload(), base.migration(), base.receipts(),
                ExactJson.parse("{\"tag\":2}"));
        assertNotEquals(base, otherMembers);

        DefinitionEnvelope sameAgain = new DefinitionEnvelope(base.target(), base.revision(),
                base.payloadDigest(), base.payload(), base.migration(), base.receipts(),
                base.members());
        assertEquals(base, sameAgain);
    }

    // ------------------------------------------------------------------
    // relations this candidate deliberately does not check
    // ------------------------------------------------------------------

    @Test
    @DisplayName("structurally accepts the cross relation inconsistencies the later codec must reject")
    void structurallyAcceptsCrossRelationInconsistencies() {
        DefinitionRef otherTarget = definitionRef(OTHER_DEFINITION_ID);
        MapDefinition payload = payload(DEFINITION_ID);
        CommitReceipt foreignTarget = receiptFor(OTHER_DEFINITION_ID, 41L);
        CommitReceipt future = receipt(42L);
        CommitReceipt duplicateActor = receipt(41L);

        DefinitionEnvelope envelope = new DefinitionEnvelope(otherTarget, 1L, hexDigest(33_000L),
                payload, Optional.of(migration(41L)), List.of(foreignTarget, future, duplicateActor),
                EMPTY_OBJECT);

        // the envelope target names a different definition than the identity inside the payload
        assertEquals(OTHER_DEFINITION_ID, envelope.target().definitionId());
        assertNotEquals(DEFINITION_ID, envelope.target().definitionId());

        // the migration was read from a different world than the envelope target claims
        assertNotEquals(envelope.target().worldId(),
                envelope.migration().orElseThrow().sourceScope().worldId());

        // a receipt that committed a revision the envelope does not claim and a foreign target
        assertEquals(2L, future.committedRevision());
        assertEquals(1L, envelope.revision());
        assertEquals(OTHER_DEFINITION_ID,
                foreignTarget.fingerprintInputs().target().target().definitionId());

        // two receipts that share one actor and one request identity
        assertEquals(foreignTarget.actorUuid(), duplicateActor.actorUuid());
        assertEquals(foreignTarget.requestId(), duplicateActor.requestId());

        // the envelope digest, the receipt digest and the inputs digest are unrelated here
        assertNotEquals(envelope.payloadDigest(), future.payloadDigest());
        assertSame(foreignTarget, envelope.receipts().get(0));
        assertSame(future, envelope.receipts().get(1));
        assertEquals(3, envelope.receipts().size());
    }

    // ------------------------------------------------------------------
    // the thousand sample local oracle
    // ------------------------------------------------------------------

    private static final int CANDIDATE_COUNT = 1000;

    /** How many field combinations the sample cycles through; every combination gets 100 samples. */
    private static final int COMBINATIONS = 10;

    /**
     * One generated candidate: only the real fields production is asked to bind, and no verdict.
     *
     * <p>The verdict is computed from these fields afterwards by {@link #expectedAccepted}, never
     * stored here, never derived from a combination number and never read back from production.</p>
     */
    private record Candidate(int index, DefinitionRef target, long revision, String payloadDigest,
            MapDefinition payload, Optional<MigrationRecord> migration, ExactJson.FrozenJson members,
            List<CommitReceipt> receipts) {
    }

    /**
     * Builds the fixed sample set by assembling every real field first, and nothing else.
     *
     * <p>No branch here decides acceptance: a combination only chooses field values, and the values
     * are chosen so that the sample covers the legal revision {@code 1} and {@code Long.MAX_VALUE}
     * beside the illegal {@code 0} and {@code Long.MIN_VALUE}, the legal lower case, upper case free
     * digest beside a shortened, an out of alphabet and a null digest, an object member container
     * beside an array and a scalar one, receipt lists of {@code 0}, {@code 1}, an ordinary length,
     * the full budget and one element over the budget, plus a list that carries a null element. The
     * optional migration is genuinely present for half of the sample and genuinely empty for the
     * other half, including among the accepted candidates.</p>
     *
     * <p>Whether such a field set is bindable is answered afterwards by the local structural rule
     * {@link #expectedAccepted}, which reads only the assembled fields. The hostile list
     * implementations stay inside their own focused tests and are deliberately absent from this
     * plain, mutable, null tolerant list model.</p>
     */
    private static List<Candidate> independentCandidates() {
        List<Candidate> candidates = new ArrayList<>(CANDIDATE_COUNT);
        for (int index = 0; index < CANDIDATE_COUNT; index++) {
            int combination = index % COMBINATIONS;
            DefinitionRef target = new DefinitionRef(new Ids.WorldId(uuidAt(20_000L + index)),
                    new Ids.DefinitionId(uuidAt(30_000L + index)));
            MapDefinition payload = payload(new Ids.DefinitionId(uuidAt(40_000L + index)));
            long revision = 1L + index;
            String payloadDigest = hexDigest(50_000L + index);
            ExactJson.FrozenJson members = ExactJson.parse("{\"seed\":" + index + "}");
            Optional<MigrationRecord> migration = combination % 2 == 0 ? Optional.empty()
                    : Optional.of(migration(index));
            // a plain, mutable and null tolerant list: the hostile lists stay in their own tests
            List<CommitReceipt> receipts = new ArrayList<>();
            receipts.add(receipt(index));

            switch (combination) {
                case 0 -> {
                    // one receipt and an in range revision: the smallest accepted shape
                }
                case 1 -> {
                    revision = Long.MAX_VALUE;
                    receipts = new ArrayList<>();
                }
                case 2 -> receipts = new ArrayList<>(
                        List.of(receipt(index), receipt(index), receipt(index)));
                case 3 -> receipts = new ArrayList<>(
                        Collections.nCopies(RECEIPT_BUDGET, receipt(index)));
                case 4 -> revision = 0L;
                case 5 -> revision = Long.MIN_VALUE;
                case 6 -> payloadDigest = payloadDigest.substring(0, 63);
                case 7 -> payloadDigest = illegalDigest(payloadDigest, index);
                case 8 -> members = ExactJson.parse(
                        (index / COMBINATIONS) % 2 == 0 ? "[" + index + "]" : "\"" + index + "\"");
                case 9 -> receipts = receiptListAtTheNullBoundary(index);
                default -> throw new AssertionError("unreachable combination " + combination);
            }
            candidates.add(new Candidate(index, target, revision, payloadDigest, payload, migration,
                    members, receipts));
        }
        return List.copyOf(candidates);
    }

    /** A digest that is illegal in exactly one of three ways, chosen by the sample position. */
    private static String illegalDigest(String legal, int index) {
        int variant = (index / COMBINATIONS) % 3;
        if (variant == 0) {
            char last = legal.charAt(63);
            char upper = Character.toUpperCase(last);
            return legal.substring(0, 63) + (upper == last ? 'A' : upper);
        }
        if (variant == 1) {
            return legal.substring(0, 63) + 'z';
        }
        return null;
    }

    /**
     * Either one element over the budget, or exactly the budget with one null element.
     *
     * <p>The vector is chosen by the sample block ({@code index / COMBINATIONS}) and not by the
     * sample parity: this helper is only reached for {@code index % COMBINATIONS == 9}, so a parity
     * test would leave one of the two vectors unreachable. The block test keeps both reachable and
     * gives each of them exactly fifty samples, while the generation order stays {@code 0..999}.</p>
     */
    private static List<CommitReceipt> receiptListAtTheNullBoundary(int index) {
        if ((index / COMBINATIONS) % 2 == 0) {
            return new ArrayList<>(Collections.nCopies(RECEIPT_BUDGET + 1, receipt(index)));
        }
        List<CommitReceipt> withNull =
                new ArrayList<>(Collections.nCopies(RECEIPT_BUDGET - 1, receipt(index)));
        withNull.add(null);
        return withNull;
    }

    // ------------------------------------------------------------------
    // the local structural rule and its helpers
    // ------------------------------------------------------------------

    /**
     * The local structural rule: it reads only the assembled candidate fields.
     *
     * <p>It never looks at the combination number, at an injection flag, at production code, at any
     * production constant or at what production answered. The member shape is read through the public
     * canonical form of the frozen value, so the production object rule is not reimplemented here
     * from any private knowledge.</p>
     */
    private static boolean expectedAccepted(Candidate candidate) {
        if (candidate.target() == null || candidate.revision() < 1L || candidate.payload() == null
                || candidate.migration() == null || candidate.members() == null
                || candidate.receipts() == null) {
            return false;
        }
        return digestIsLegal(candidate.payloadDigest())
                && memberShapeIsObject(candidate.members())
                && receiptsAreBindable(candidate.receipts());
    }

    /** Exactly 64 lower case ASCII hexadecimal characters, and nothing else. */
    private static boolean digestIsLegal(String digest) {
        if (digest == null || digest.length() != 64) {
            return false;
        }
        for (int position = 0; position < 64; position++) {
            char character = digest.charAt(position);
            boolean digit = character >= '0' && character <= '9';
            boolean lowerCaseHex = character >= 'a' && character <= 'f';
            if (!digit && !lowerCaseHex) {
                return false;
            }
        }
        return true;
    }

    /** The public canonical shape of a frozen value: an object is the only allowed member shape. */
    private static boolean memberShapeIsObject(ExactJson.FrozenJson members) {
        String canonical = members.canonical();
        return canonical.startsWith("{") && canonical.endsWith("}");
    }

    /** A plain list the candidate may hold: within the budget and without a null element. */
    private static boolean receiptsAreBindable(List<CommitReceipt> receipts) {
        if (receipts.size() > RECEIPT_BUDGET) {
            return false;
        }
        for (CommitReceipt receipt : receipts) {
            if (receipt == null) {
                return false;
            }
        }
        return true;
    }

    /** Binds one candidate through the production constructor: the only production call here. */
    private static DefinitionEnvelope construct(Candidate candidate) {
        return new DefinitionEnvelope(candidate.target(), candidate.revision(),
                candidate.payloadDigest(), candidate.payload(), candidate.migration(),
                candidate.receipts(), candidate.members());
    }

    /**
     * Binds one accepted candidate from an independent mutable copy and proves that the envelope is
     * faithful: every component is the caller's own reference, the list keeps its order, its
     * duplicates and every element instance, a later mutation of the caller's list cannot reach the
     * envelope, the published list really refuses every mutation, and an equal independent copy is
     * equal by value with the same hash and text.
     */
    private static void assertAcceptedCandidateIsFaithful(Candidate candidate) {
        List<CommitReceipt> source = new ArrayList<>(candidate.receipts());
        DefinitionEnvelope envelope = new DefinitionEnvelope(candidate.target(), candidate.revision(),
                candidate.payloadDigest(), candidate.payload(), candidate.migration(), source,
                candidate.members());

        assertSame(candidate.target(), envelope.target());
        assertEquals(candidate.revision(), envelope.revision());
        assertSame(candidate.payloadDigest(), envelope.payloadDigest());
        assertSame(candidate.payload(), envelope.payload());
        assertSame(candidate.migration(), envelope.migration());
        assertEquals(candidate.migration().isPresent(), envelope.migration().isPresent());
        if (candidate.migration().isPresent()) {
            assertSame(candidate.migration().orElseThrow(), envelope.migration().orElseThrow());
        }
        assertSame(candidate.members(), envelope.members());

        List<CommitReceipt> published = envelope.receipts();
        assertNotSame(source, published);
        assertEquals(candidate.receipts().size(), published.size());
        for (int position = 0; position < candidate.receipts().size(); position++) {
            assertSame(candidate.receipts().get(position), published.get(position),
                    "element " + position + " must keep the caller's own instance");
        }

        List<CommitReceipt> before = new ArrayList<>(published);
        if (!source.isEmpty()) {
            source.set(0, receipt(candidate.index() + 800_000L));
        }
        source.clear();
        // the caller's list is emptied and then filled again: an implementation that merely wraps or
        // still shares the caller's list has to fail here even for the empty candidate
        CommitReceipt lateArrival = receipt(candidate.index() + 700_000L);
        source.add(lateArrival);
        assertEquals(1, source.size(), "the caller's list must really carry the late arrival");
        assertEquals(before, published, "a later mutation of the caller's list must not be visible");
        assertEquals(before.size(), published.size(),
                "the published size must not follow the caller's list");
        for (int position = 0; position < before.size(); position++) {
            assertSame(before.get(position), published.get(position),
                    "element " + position + " must still be the instance published first");
        }
        assertFalse(published.contains(lateArrival),
                "the late arrival must not become visible through the envelope");

        CommitReceipt extra = receipt(candidate.index() + 900_000L);
        assertThrows(UnsupportedOperationException.class, () -> published.add(extra));
        assertThrows(UnsupportedOperationException.class, () -> published.clear());
        assertThrows(UnsupportedOperationException.class, () -> published.remove(extra));
        if (!published.isEmpty()) {
            // an empty list cannot prove immutability through an out of range index
            assertThrows(UnsupportedOperationException.class, () -> published.set(0, extra));
            assertThrows(UnsupportedOperationException.class, () -> published.remove(0));
        }

        List<CommitReceipt> equalCopy = new ArrayList<>(candidate.receipts());
        DefinitionEnvelope twin = new DefinitionEnvelope(candidate.target(), candidate.revision(),
                candidate.payloadDigest(), candidate.payload(), candidate.migration(), equalCopy,
                candidate.members());
        assertNotSame(envelope, twin);
        assertEquals(envelope, twin, "an equal independent copy must be equal by value");
        assertEquals(envelope.hashCode(), twin.hashCode());
        if (published.size() <= 8) {
            assertEquals(envelope.toString(), twin.toString());
        }
    }

    @Test
    @DisplayName("a thousand independent candidates follow the local model with both verdicts")
    void thousandIndependentCandidatesFollowTheLocalModel() {
        List<Candidate> candidates = independentCandidates();

        // every expectation is fixed, from the assembled fields only, before production is called
        boolean[] expectedVerdicts = new boolean[candidates.size()];
        int predictedAccepted = 0;
        for (int position = 0; position < candidates.size(); position++) {
            expectedVerdicts[position] = expectedAccepted(candidates.get(position));
            if (expectedVerdicts[position]) {
                predictedAccepted++;
            }
        }
        int predictedRejected = CANDIDATE_COUNT - predictedAccepted;
        assertTrue(predictedAccepted >= 100, "predictedAccepted=" + predictedAccepted);
        assertTrue(predictedRejected >= 100, "predictedRejected=" + predictedRejected);
        // the generator derives these two constants from its own field combinations, not from a run
        assertEquals(400, predictedAccepted, "generator derived accept count");
        assertEquals(600, predictedRejected, "generator derived reject count");

        // the real field categories are counted from the assembled fields themselves and not from a
        // generator mode, so a dead vector or a collapsed category cannot stay hidden
        int revisionOne = 0;
        int revisionMax = 0;
        int revisionZero = 0;
        int revisionMin = 0;
        int listEmpty = 0;
        int listSingle = 0;
        int listOrdinary = 0;
        int listFull = 0;
        int listOverBudget = 0;
        int listWithNull = 0;
        int migrationEmpty = 0;
        int migrationPresent = 0;
        int acceptedWithEmptyMigration = 0;
        int acceptedWithPresentMigration = 0;
        for (int position = 0; position < candidates.size(); position++) {
            Candidate candidate = candidates.get(position);
            if (candidate.revision() == 1L) {
                revisionOne++;
            }
            if (candidate.revision() == Long.MAX_VALUE) {
                revisionMax++;
            }
            if (candidate.revision() == 0L) {
                revisionZero++;
            }
            if (candidate.revision() == Long.MIN_VALUE) {
                revisionMin++;
            }
            int size = candidate.receipts().size();
            boolean carriesNull = candidate.receipts().contains(null);
            if (size == 0) {
                listEmpty++;
            } else if (size == 1) {
                listSingle++;
            } else if (size == RECEIPT_BUDGET && carriesNull) {
                listWithNull++;
            } else if (size == RECEIPT_BUDGET) {
                listFull++;
            } else if (size == RECEIPT_BUDGET + 1) {
                listOverBudget++;
            } else {
                listOrdinary++;
            }
            if (candidate.migration().isPresent()) {
                migrationPresent++;
                if (expectedVerdicts[position]) {
                    acceptedWithPresentMigration++;
                }
            } else {
                migrationEmpty++;
                if (expectedVerdicts[position]) {
                    acceptedWithEmptyMigration++;
                }
            }
        }
        assertEquals(1, revisionOne, "revision exactly 1");
        assertEquals(100, revisionMax, "revision Long.MAX_VALUE");
        assertEquals(100, revisionZero, "revision 0");
        assertEquals(100, revisionMin, "revision Long.MIN_VALUE");
        assertEquals(100, listEmpty, "empty receipt list");
        assertEquals(600, listSingle, "single receipt list");
        assertEquals(100, listOrdinary, "ordinary three element list");
        assertEquals(100, listFull, "full budget list without a null element");
        assertEquals(50, listOverBudget, "one element over the budget");
        assertEquals(50, listWithNull, "budget list with exactly one null element");
        assertEquals(500, migrationEmpty, "empty optional");
        assertEquals(500, migrationPresent, "present optional");
        assertEquals(200, acceptedWithEmptyMigration, "accepted with an empty optional");
        assertEquals(200, acceptedWithPresentMigration, "accepted with a present optional");

        int accepted = 0;
        int rejected = 0;
        for (int position = 0; position < candidates.size(); position++) {
            Candidate candidate = candidates.get(position);
            if (expectedVerdicts[position]) {
                assertAcceptedCandidateIsFaithful(candidate);
                if (candidate.revision() == 1L + candidate.index()) {
                    assertEquals(1L + candidate.index(), construct(candidate).revision());
                }
                accepted++;
            } else {
                rejection(() -> construct(candidate));
                rejected++;
            }
        }
        assertEquals(CANDIDATE_COUNT, candidates.size());
        assertEquals(CANDIDATE_COUNT, accepted + rejected);
        assertEquals(predictedAccepted, accepted, "production must agree with the local model");
        assertEquals(predictedRejected, rejected, "production must agree with the local model");
        assertTrue(accepted >= 100, "accepted=" + accepted);
        assertTrue(rejected >= 100, "rejected=" + rejected);
        assertEquals(400, accepted);
        assertEquals(600, rejected);
    }

    @Test
    @DisplayName("generates a thousand candidates whose identities, digests and members are distinct")
    void candidatesAreGenuinelyDistinct() {
        List<Candidate> candidates = independentCandidates();
        java.util.Set<Integer> indexes = new java.util.HashSet<>();
        java.util.Set<String> hosts = new java.util.HashSet<>();
        java.util.Set<String> definitions = new java.util.HashSet<>();
        java.util.Set<String> payloadDefinitions = new java.util.HashSet<>();
        java.util.Set<String> digests = new java.util.HashSet<>();
        java.util.Set<String> memberShapes = new java.util.HashSet<>();
        java.util.Set<String> requests = new java.util.HashSet<>();
        int withoutDigest = 0;
        int withoutReceipt = 0;

        for (Candidate candidate : candidates) {
            indexes.add(candidate.index());
            hosts.add(candidate.target().worldId().toString());
            definitions.add(candidate.target().definitionId().toString());
            payloadDefinitions.add(candidate.payload().id().toString());
            memberShapes.add(candidate.members().canonical());
            if (candidate.payloadDigest() == null) {
                withoutDigest++;
            } else {
                digests.add(candidate.payloadDigest());
            }
            if (candidate.receipts().isEmpty()) {
                withoutReceipt++;
            }
            for (CommitReceipt receipt : candidate.receipts()) {
                if (receipt != null) {
                    requests.add(receipt.requestId().toString());
                }
            }
        }
        assertEquals(CANDIDATE_COUNT, candidates.size());
        assertEquals(CANDIDATE_COUNT, indexes.size());
        assertEquals(CANDIDATE_COUNT, hosts.size());
        assertEquals(CANDIDATE_COUNT, definitions.size());
        assertEquals(CANDIDATE_COUNT, payloadDefinitions.size());
        assertEquals(CANDIDATE_COUNT, memberShapes.size());
        assertEquals(CANDIDATE_COUNT - withoutDigest, digests.size());
        assertEquals(CANDIDATE_COUNT - withoutReceipt, requests.size());
        assertTrue(withoutDigest > 0, "the sample must contain the null digest vector");
        assertTrue(withoutReceipt > 0, "the sample must contain the empty receipt list vector");
    }

    // ------------------------------------------------------------------
    // real regressions: repairing one real field must flip the verdict
    // ------------------------------------------------------------------

    @Test
    @DisplayName("flips from rejected to accepted when the illegal revision is repaired to one")
    void flipsFromRejectedToAcceptedWhenTheIllegalRevisionIsRepaired() {
        Candidate zeroRevision = independentCandidates().get(4);
        assertEquals(0L, zeroRevision.revision());
        assertFalse(expectedAccepted(zeroRevision));
        rejection(() -> construct(zeroRevision));

        Candidate repaired = new Candidate(zeroRevision.index(), zeroRevision.target(), 1L,
                zeroRevision.payloadDigest(), zeroRevision.payload(), zeroRevision.migration(),
                zeroRevision.members(), zeroRevision.receipts());
        assertSame(zeroRevision.target(), repaired.target());
        assertSame(zeroRevision.payloadDigest(), repaired.payloadDigest());
        assertSame(zeroRevision.payload(), repaired.payload());
        assertSame(zeroRevision.migration(), repaired.migration());
        assertSame(zeroRevision.members(), repaired.members());
        assertSame(zeroRevision.receipts(), repaired.receipts());
        assertTrue(expectedAccepted(repaired), "the verdict must follow the repaired field");
        assertAcceptedCandidateIsFaithful(repaired);
    }

    @Test
    @DisplayName("flips from rejected to accepted when the null receipt element is repaired")
    void flipsFromRejectedToAcceptedWhenTheNullReceiptIsRepaired() {
        Candidate withNull = independentCandidates().get(19);
        assertEquals(RECEIPT_BUDGET, withNull.receipts().size());
        int nullPosition = -1;
        int nullElements = 0;
        for (int position = 0; position < withNull.receipts().size(); position++) {
            if (withNull.receipts().get(position) == null) {
                nullPosition = position;
                nullElements++;
            }
        }
        assertEquals(1, nullElements, "the vector must carry exactly one null element");
        assertTrue(nullPosition >= 0, "the vector must carry the null element");
        assertFalse(expectedAccepted(withNull));
        rejection(() -> construct(withNull));

        // repair that single element inside an independent mutable copy: the size, the order and
        // every other element instance survive, so shrinking the whole list cannot stand in for it
        List<CommitReceipt> repairedReceipts = new ArrayList<>(withNull.receipts());
        CommitReceipt replacement = receipt(313L);
        repairedReceipts.set(nullPosition, replacement);
        assertEquals(RECEIPT_BUDGET, repairedReceipts.size());
        Candidate repaired = new Candidate(withNull.index(), withNull.target(), withNull.revision(),
                withNull.payloadDigest(), withNull.payload(), withNull.migration(),
                withNull.members(), repairedReceipts);
        assertSame(withNull.target(), repaired.target());
        assertSame(withNull.payloadDigest(), repaired.payloadDigest());
        assertSame(withNull.payload(), repaired.payload());
        assertSame(withNull.migration(), repaired.migration());
        assertSame(withNull.members(), repaired.members());
        assertTrue(expectedAccepted(repaired), "the verdict must follow the repaired element");
        DefinitionEnvelope accepted = construct(repaired);
        assertEquals(RECEIPT_BUDGET, accepted.receipts().size());
        for (int position = 0; position < RECEIPT_BUDGET; position++) {
            if (position == nullPosition) {
                assertSame(replacement, accepted.receipts().get(position));
            } else {
                assertSame(withNull.receipts().get(position), accepted.receipts().get(position),
                        "element " + position + " must keep its original instance");
            }
        }
    }

    @Test
    @DisplayName("refuses every mutation of the published list at both list boundaries")
    void refusesEveryMutationOfThePublishedReceiptListAtBothBoundaries() {
        // an empty published list: no out of range index may stand in for an immutability proof
        DefinitionEnvelope empty = envelope(1L, hexDigest(151L), EMPTY_OBJECT, new ArrayList<>());
        List<CommitReceipt> emptyPublished = empty.receipts();
        assertTrue(emptyPublished.isEmpty());
        CommitReceipt extra = receipt(151L);
        assertThrows(UnsupportedOperationException.class, () -> emptyPublished.add(extra));
        assertThrows(UnsupportedOperationException.class,
                () -> emptyPublished.addAll(List.of(extra)));
        assertThrows(UnsupportedOperationException.class, () -> emptyPublished.remove(extra));
        assertThrows(UnsupportedOperationException.class, () -> emptyPublished.clear());

        // the full budget list, built from an independent mutable source
        CommitReceipt shared = receipt(152L);
        List<CommitReceipt> source = new ArrayList<>(Collections.nCopies(RECEIPT_BUDGET, shared));
        DefinitionEnvelope full = envelope(1L, hexDigest(152L), EMPTY_OBJECT, source);
        List<CommitReceipt> published = full.receipts();
        assertEquals(RECEIPT_BUDGET, published.size());
        source.clear();
        assertEquals(RECEIPT_BUDGET, published.size());
        assertSame(shared, published.get(0));
        assertSame(shared, published.get(RECEIPT_BUDGET - 1));
        assertThrows(UnsupportedOperationException.class, () -> published.add(extra));
        assertThrows(UnsupportedOperationException.class, () -> published.set(0, extra));
        assertThrows(UnsupportedOperationException.class, () -> published.remove(0));
        assertThrows(UnsupportedOperationException.class, () -> published.remove(extra));
        assertThrows(UnsupportedOperationException.class, () -> published.clear());
        assertThrows(UnsupportedOperationException.class, () -> published.removeIf(element -> true));
    }

    @Test
    @DisplayName("refuses a dishonest traversal at the budget boundary")
    void refusesDishonestTraversalAtTheBudgetBoundary() {
        CommitReceipt shared = receipt(131L);

        TripwireList<CommitReceipt> shortByOne = new TripwireList<>(RECEIPT_BUDGET,
                Collections.nCopies(RECEIPT_BUDGET - 1, shared));
        assertTrue(rejection(() -> envelope(1L, hexDigest(131L), EMPTY_OBJECT, shortByOne)).getMessage()
                .contains("receipts"));
        assertEquals(1, shortByOne.sizeCalls());
        assertEquals(1, shortByOne.iteratorCalls());
        assertEquals(0, shortByOne.elementAccesses());

        TripwireList<CommitReceipt> oneTooMany = new TripwireList<>(RECEIPT_BUDGET,
                Collections.nCopies(RECEIPT_BUDGET + 1, shared));
        assertTrue(rejection(() -> envelope(1L, hexDigest(132L), EMPTY_OBJECT, oneTooMany)).getMessage()
                .contains("receipts"));
        assertEquals(1, oneTooMany.sizeCalls());
        assertEquals(1, oneTooMany.iteratorCalls());
    }
}
