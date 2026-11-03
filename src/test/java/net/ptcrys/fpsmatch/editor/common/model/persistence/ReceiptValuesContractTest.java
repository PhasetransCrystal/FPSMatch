package net.ptcrys.fpsmatch.editor.common.model.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import net.ptcrys.fpsmatch.editor.common.model.DefinitionRef;
import net.ptcrys.fpsmatch.editor.common.model.Ids;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.CommitReceipt;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.FingerprintInputs;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.FingerprintTarget;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.OperationKind;
import org.junit.jupiter.api.Test;

/**
 * Formal contract test for {@link PersistenceValues.FingerprintTarget},
 * {@link PersistenceValues.FingerprintInputs} and {@link PersistenceValues.CommitReceipt} (task
 * R06.2a4).
 *
 * <p>Every case builds real values through the real records, checks the accepted reference identity
 * and the exact rejection of a malformed candidate, and never asserts on an invented message or on a
 * code the contract does not publish. The expectations of the thousand candidate model are computed
 * from the generated components by a local model <em>before</em> the production records are called,
 * so no expectation is ever read back from the type under test. That model uses {@link BigInteger}
 * for {@code expectedRevision + 1}, so the overflow boundary is decided by the model and not by the
 * same {@code long} arithmetic the production code happens to use.</p>
 *
 * <p>The three records publish no fingerprint algorithm, no canonicalisation and no certification:
 * this suite therefore never expects a fingerprint to match its inputs, and it never expects the
 * payload digest of a receipt to have been recomputed from the payload.</p>
 */
class ReceiptValuesContractTest {

    private static final String HEX = "0123456789abcdef";
    private static final String DIGEST_A = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String DIGEST_B = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
    private static final String DIGEST_ZERO = "0".repeat(64);

    private static final UUID ACTOR = new UUID(0x1111L, 0x2222L);
    private static final UUID OTHER_ACTOR = new UUID(0x3333L, 0x4444L);
    private static final Ids.RequestId REQUEST = new Ids.RequestId(new UUID(0x5555L, 0x6666L));
    private static final Ids.RequestId OTHER_REQUEST = new Ids.RequestId(new UUID(0x7777L, 0x8888L));
    private static final DefinitionRef DEFINITION = new DefinitionRef(
            new Ids.WorldId(new UUID(0x9999L, 0xAAAAL)), new Ids.DefinitionId(new UUID(0xBBBBL, 0xCCCCL)));
    private static final DefinitionRef OTHER_DEFINITION = new DefinitionRef(
            new Ids.WorldId(new UUID(0xDDDDL, 0xEEEEL)), new Ids.DefinitionId(new UUID(0x1234L, 0x5678L)));

    /** The five non object JSON shapes: every one of them must be refused as a member container. */
    private static final List<String> NON_OBJECT_SHAPES = List.of("[]", "7", "\"x\"", "true", "null");

    /** Legal member containers: an object only, with unknown, empty, unicode and exact members. */
    private static final List<String> LEGAL_OBJECT_JSON = List.of(
            "{}",
            "{\"unknown\":{\"future\":[1,2,3]}}",
            "{\"\":1}",
            "{\"\u672a\u6765\":\"\u503c\"}",
            "{\"n\":12345678901234567890123456789012345678901234567890}",
            "{\"a\":null}");

    /** The four digest carrying positions of this card, each one probed with the same alphabet. */
    private enum DigestColumn {
        INPUTS_PAYLOAD,
        INPUTS_MIGRATION,
        RECEIPT_FINGERPRINT,
        RECEIPT_PAYLOAD
    }

    private static ExactJson.FrozenJson members(String strictJson) {
        return ExactJson.parse(strictJson);
    }

    private static ExactJson.FrozenJson emptyMembers() {
        return members("{}");
    }

    private static String legalDigest(Random random) {
        final StringBuilder builder = new StringBuilder(64);
        for (int index = 0; index < 64; index++) {
            builder.append(HEX.charAt(random.nextInt(HEX.length())));
        }
        return builder.toString();
    }

    /** Every text below must be refused by the digest rule of one field. */
    private static List<String> malformedDigests() {
        final List<String> values = new ArrayList<>();
        values.add("0".repeat(63));
        values.add(DIGEST_ZERO + "0");
        values.add(DIGEST_A.substring(0, 63) + "A");
        values.add("G" + DIGEST_A.substring(1));
        values.add(DIGEST_A.substring(0, 63) + "g");
        values.add("g" + DIGEST_A.substring(1));
        values.add(DIGEST_A.substring(0, 63) + "\u00e9");
        values.add(DIGEST_A.substring(0, 63) + "\u0000");
        values.add(DIGEST_A.substring(0, 63) + "\ud800");
        values.add("\udc00" + DIGEST_A.substring(1));
        values.add("");
        values.add(" ".repeat(64));
        return List.copyOf(values);
    }

    private static FingerprintInputs inputs(long expectedRevision, String payloadDigest,
            OperationKind operationKind, Optional<String> migrationDigest,
            ExactJson.FrozenJson members) {
        return new FingerprintInputs(ACTOR, new FingerprintTarget(DEFINITION, emptyMembers()), REQUEST,
                expectedRevision, payloadDigest, operationKind, migrationDigest, members);
    }

    /** Legal inputs: revision five, legal payload digest, no migration digest, empty members. */
    private static FingerprintInputs legalInputs() {
        return inputs(5L, DIGEST_A, OperationKind.SAVE_MAP, Optional.empty(), emptyMembers());
    }

    /** Legal receipt: a content changing commit of five plus one. */
    private static CommitReceipt legalReceipt() {
        return new CommitReceipt(ACTOR, REQUEST, DIGEST_B, legalInputs(), 6L, DIGEST_A, true,
                1700000000000L, emptyMembers());
    }

    private static FingerprintInputs withTarget(DefinitionRef definition, ExactJson.FrozenJson targetMembers,
            ExactJson.FrozenJson members) {
        return new FingerprintInputs(ACTOR, new FingerprintTarget(definition, targetMembers), REQUEST, 5L,
                DIGEST_A, OperationKind.SAVE_MAP, Optional.empty(), members);
    }

    /**
     * Builds one value whose only suspicious column is the digest of {@code column}; every other
     * component is the legal fixture, so a rejection can only come from that column.
     */
    private static Object buildWithDigest(DigestColumn column, String text) {
        switch (column) {
            case INPUTS_PAYLOAD:
                return inputs(5L, text, OperationKind.SAVE_MAP, Optional.empty(), emptyMembers());
            case INPUTS_MIGRATION:
                return inputs(5L, DIGEST_A, OperationKind.MIGRATE_MAP, Optional.of(text), emptyMembers());
            case RECEIPT_FINGERPRINT:
                return new CommitReceipt(ACTOR, REQUEST, text, legalInputs(), 6L, DIGEST_A, true,
                        1700000000000L, emptyMembers());
            case RECEIPT_PAYLOAD:
                return new CommitReceipt(ACTOR, REQUEST, DIGEST_B, legalInputs(), 6L, text, true,
                        1700000000000L, emptyMembers());
            default:
                throw new IllegalStateException("unreachable digest column " + column);
        }
    }

    private static void assertDigestColumnRejectsEveryMalformedText(DigestColumn column) {
        for (String text : malformedDigests()) {
            assertThrows(IllegalArgumentException.class, () -> buildWithDigest(column, text),
                    column + " must reject " + text.length() + " characters");
        }
    }

    /**
     * A digest text that the record carries directly can also be null and must be refused. The
     * migration digest is the exception by construction: a present value lives inside an
     * {@link Optional} that refuses null in the language itself, so the only null route of that
     * column is the {@code Optional} reference.
     */
    private static void assertDigestColumnRejectsNullText(DigestColumn column) {
        assertThrows(IllegalArgumentException.class, () -> buildWithDigest(column, null),
                column + " must reject null");
    }

    private static void assertDigestColumnAcceptsAndKeepsTheReference(DigestColumn column) {
        // the receipt payload column must stay equal to the fingerprint inputs payload digest
        final String text = column == DigestColumn.RECEIPT_PAYLOAD ? DIGEST_A : DIGEST_B;
        final Object value = buildWithDigest(column, text);
        final Object kept = switch (column) {
            case INPUTS_PAYLOAD -> ((FingerprintInputs) value).payloadDigest();
            case INPUTS_MIGRATION -> ((FingerprintInputs) value).migrationDigest().get();
            case RECEIPT_FINGERPRINT -> ((CommitReceipt) value).fingerprint();
            case RECEIPT_PAYLOAD -> ((CommitReceipt) value).payloadDigest();
        };
        assertSame(text, kept, column + " must keep the same String reference");
    }

    // ---------------------------------------------------------------------------------------------
    // FingerprintTarget
    // ---------------------------------------------------------------------------------------------

    @Test
    void targetRejectsANullDefinitionRefAndNullMembers() {
        assertThrows(IllegalArgumentException.class, () -> new FingerprintTarget(null, emptyMembers()));
        assertThrows(IllegalArgumentException.class, () -> new FingerprintTarget(DEFINITION, null));
    }

    @Test
    void targetKeepsTheSameReferencesOfALegalValue() {
        final ExactJson.FrozenJson frozen = members("{\"a\":1}");
        final FingerprintTarget target = new FingerprintTarget(DEFINITION, frozen);
        assertSame(DEFINITION, target.target());
        assertSame(frozen, target.members());
        // the nested definition reference itself is never rebuilt either
        assertSame(DEFINITION.worldId(), target.target().worldId());
        assertSame(DEFINITION.definitionId(), target.target().definitionId());
    }

    @Test
    void targetRejectsEveryNonObjectMemberShape() {
        for (String shape : NON_OBJECT_SHAPES) {
            final ExactJson.FrozenJson frozen = members(shape);
            assertThrows(IllegalArgumentException.class, () -> new FingerprintTarget(DEFINITION, frozen),
                    "shape " + shape + " must not be a member container");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // FingerprintInputs
    // ---------------------------------------------------------------------------------------------

    @Test
    void inputsRejectEveryNullRequiredReference() {
        final FingerprintTarget target = new FingerprintTarget(DEFINITION, emptyMembers());
        assertThrows(IllegalArgumentException.class, () -> new FingerprintInputs(null, target, REQUEST, 0L,
                DIGEST_A, OperationKind.SAVE_MAP, Optional.empty(), emptyMembers()));
        assertThrows(IllegalArgumentException.class, () -> new FingerprintInputs(ACTOR, null, REQUEST, 0L,
                DIGEST_A, OperationKind.SAVE_MAP, Optional.empty(), emptyMembers()));
        assertThrows(IllegalArgumentException.class, () -> new FingerprintInputs(ACTOR, target, null, 0L,
                DIGEST_A, OperationKind.SAVE_MAP, Optional.empty(), emptyMembers()));
        assertThrows(IllegalArgumentException.class, () -> new FingerprintInputs(ACTOR, target, REQUEST, 0L,
                null, OperationKind.SAVE_MAP, Optional.empty(), emptyMembers()));
        assertThrows(IllegalArgumentException.class, () -> new FingerprintInputs(ACTOR, target, REQUEST, 0L,
                DIGEST_A, null, Optional.empty(), emptyMembers()));
        assertThrows(IllegalArgumentException.class, () -> new FingerprintInputs(ACTOR, target, REQUEST, 0L,
                DIGEST_A, OperationKind.SAVE_MAP, null, emptyMembers()));
        assertThrows(IllegalArgumentException.class, () -> new FingerprintInputs(ACTOR, target, REQUEST, 0L,
                DIGEST_A, OperationKind.SAVE_MAP, Optional.empty(), null));
    }

    @Test
    void inputsPayloadDigestColumnRejectsEveryMalformedText() {
        assertDigestColumnRejectsEveryMalformedText(DigestColumn.INPUTS_PAYLOAD);
        assertDigestColumnRejectsNullText(DigestColumn.INPUTS_PAYLOAD);
    }

    @Test
    void inputsPayloadDigestColumnAccepts64LowerHexAndKeepsTheReference() {
        assertDigestColumnAcceptsAndKeepsTheReference(DigestColumn.INPUTS_PAYLOAD);
        // an all zero digest is legal text: this record records digests, it does not judge them
        final FingerprintInputs zeros = inputs(0L, DIGEST_ZERO, OperationKind.SAVE_MAP, Optional.empty(),
                emptyMembers());
        assertSame(DIGEST_ZERO, zeros.payloadDigest());
        assertEquals(64, zeros.payloadDigest().length());
    }

    @Test
    void inputsMigrationDigestPresentValueUsesTheSameDigestRule() {
        assertDigestColumnRejectsEveryMalformedText(DigestColumn.INPUTS_MIGRATION);
        assertDigestColumnAcceptsAndKeepsTheReference(DigestColumn.INPUTS_MIGRATION);
        // a present but null migration digest cannot be expressed at all: Optional.of refuses null in
        // the language itself, so the only null route of this column is the Optional reference, which
        // the missing-reference case of the reference test above already refuses with an IAE.
        assertThrows(IllegalArgumentException.class, () -> new FingerprintInputs(ACTOR,
                new FingerprintTarget(DEFINITION, emptyMembers()), REQUEST, 0L, DIGEST_A,
                OperationKind.MIGRATE_MAP, null, emptyMembers()));
    }

    @Test
    void inputsRejectANonObjectMemberContainerShape() {
        for (String shape : NON_OBJECT_SHAPES) {
            final ExactJson.FrozenJson frozen = members(shape);
            assertThrows(IllegalArgumentException.class,
                    () -> inputs(0L, DIGEST_A, OperationKind.SAVE_MAP, Optional.empty(), frozen),
                    "shape " + shape + " must not be an inputs member container");
        }
    }

    @Test
    void inputsExpectedRevisionBoundariesAreExact() {
        final long[] refused = {-1L, Long.MIN_VALUE};
        for (long revision : refused) {
            assertThrows(IllegalArgumentException.class,
                    () -> inputs(revision, DIGEST_A, OperationKind.SAVE_MAP, Optional.empty(), emptyMembers()),
                    "expectedRevision " + revision + " must be refused");
        }
        final long[] accepted = {0L, 1L, 2L, Long.MAX_VALUE - 1L, Long.MAX_VALUE};
        for (long revision : accepted) {
            final FingerprintInputs inputs = inputs(revision, DIGEST_A, OperationKind.SAVE_MAP,
                    Optional.empty(), emptyMembers());
            assertEquals(revision, inputs.expectedRevision());
        }
    }

    @Test
    void inputsAcceptBothOperationKindsAndBothMigrationShapes() {
        for (OperationKind kind : OperationKind.values()) {
            for (Optional<String> migration : List.<Optional<String>>of(Optional.empty(),
                    Optional.of(DIGEST_B))) {
                final FingerprintInputs inputs = inputs(0L, DIGEST_A, kind, migration, emptyMembers());
                assertSame(kind, inputs.operationKind());
                assertSame(migration, inputs.migrationDigest());
            }
        }
        // a MIGRATE_MAP request without a digest is structurally legal here: that business rule is
        // not a rule of this value
        assertSame(Optional.empty(),
                inputs(0L, DIGEST_A, OperationKind.MIGRATE_MAP, Optional.empty(), emptyMembers())
                        .migrationDigest());
    }

    @Test
    void inputsKeepEveryAcceptedReferenceIncludingTheOptionalItself() {
        final ExactJson.FrozenJson targetMembers = members("{\"target\":1}");
        final ExactJson.FrozenJson frozen = members("{\"a\":[1,2,3]}");
        final Optional<String> migration = Optional.of(DIGEST_B);
        final FingerprintTarget target = new FingerprintTarget(DEFINITION, targetMembers);
        final FingerprintInputs inputs = new FingerprintInputs(ACTOR, target, REQUEST, 3L, DIGEST_A,
                OperationKind.MIGRATE_MAP, migration, frozen);
        assertSame(ACTOR, inputs.actorUuid());
        assertSame(target, inputs.target());
        assertSame(targetMembers, inputs.target().members());
        assertSame(REQUEST, inputs.requestId());
        assertSame(DIGEST_A, inputs.payloadDigest());
        assertSame(OperationKind.MIGRATE_MAP, inputs.operationKind());
        assertSame(migration, inputs.migrationDigest());
        assertSame(DIGEST_B, inputs.migrationDigest().get());
        assertSame(frozen, inputs.members());
        assertEquals(3L, inputs.expectedRevision());
    }

    @Test
    void inputsKeepUnknownMembersEmptyKeysUnicodeAndExactNumbersUnchanged() {
        for (String json : LEGAL_OBJECT_JSON) {
            final ExactJson.FrozenJson frozen = members(json);
            final FingerprintInputs inputs = withTarget(DEFINITION, frozen, frozen);
            assertSame(frozen, inputs.members());
            assertSame(frozen, inputs.target().members());
            assertEquals(frozen.canonical(), inputs.members().canonical());
        }
        final ExactJson.FrozenJson big = members("{\"n\":12345678901234567890123456789012345678901234567890}");
        // ExactJson keeps an exact number, but its canonical spelling is the ExactJsonNumber form, so
        // the same value reappears with the insignificant trailing zero dropped and the exponent made
        // explicit. All significant digits must survive: nothing is rounded and nothing is lost.
        assertTrue(big.canonical().replace(".", "")
                .contains("1234567890123456789012345678901234567890123456789"));
        final FingerprintInputs inputs = withTarget(DEFINITION, big, big);
        assertSame(big, inputs.members());
        assertSame(big, inputs.target().members());
        assertEquals(big.canonical(), inputs.members().canonical());
    }

    // ---------------------------------------------------------------------------------------------
    // CommitReceipt
    // ---------------------------------------------------------------------------------------------

    @Test
    void receiptRejectsEveryNullRequiredReference() {
        final FingerprintInputs inputs = legalInputs();
        final ExactJson.FrozenJson frozen = emptyMembers();
        assertThrows(IllegalArgumentException.class, () -> new CommitReceipt(null, REQUEST, DIGEST_B, inputs,
                6L, DIGEST_A, true, 0L, frozen));
        assertThrows(IllegalArgumentException.class, () -> new CommitReceipt(ACTOR, null, DIGEST_B, inputs,
                6L, DIGEST_A, true, 0L, frozen));
        assertThrows(IllegalArgumentException.class, () -> new CommitReceipt(ACTOR, REQUEST, null, inputs,
                6L, DIGEST_A, true, 0L, frozen));
        assertThrows(IllegalArgumentException.class, () -> new CommitReceipt(ACTOR, REQUEST, DIGEST_B, null,
                6L, DIGEST_A, true, 0L, frozen));
        assertThrows(IllegalArgumentException.class, () -> new CommitReceipt(ACTOR, REQUEST, DIGEST_B, inputs,
                6L, null, true, 0L, frozen));
        assertThrows(IllegalArgumentException.class, () -> new CommitReceipt(ACTOR, REQUEST, DIGEST_B, inputs,
                6L, DIGEST_A, true, 0L, null));
    }

    @Test
    void receiptFingerprintColumnRejectsEveryMalformedText() {
        assertDigestColumnRejectsEveryMalformedText(DigestColumn.RECEIPT_FINGERPRINT);
        assertDigestColumnRejectsNullText(DigestColumn.RECEIPT_FINGERPRINT);
        assertDigestColumnAcceptsAndKeepsTheReference(DigestColumn.RECEIPT_FINGERPRINT);
    }

    @Test
    void receiptPayloadDigestColumnRejectsEveryMalformedText() {
        assertDigestColumnRejectsEveryMalformedText(DigestColumn.RECEIPT_PAYLOAD);
        assertDigestColumnRejectsNullText(DigestColumn.RECEIPT_PAYLOAD);
        assertDigestColumnAcceptsAndKeepsTheReference(DigestColumn.RECEIPT_PAYLOAD);
    }

    @Test
    void receiptRejectsANonObjectMemberContainerShape() {
        for (String shape : NON_OBJECT_SHAPES) {
            final ExactJson.FrozenJson frozen = members(shape);
            assertThrows(IllegalArgumentException.class, () -> new CommitReceipt(ACTOR, REQUEST, DIGEST_B,
                    legalInputs(), 6L, DIGEST_A, true, 0L, frozen),
                    "shape " + shape + " must not be a receipt member container");
        }
    }

    @Test
    void receiptNumericBoundariesAreExact() {
        final FingerprintInputs inputs = legalInputs();
        assertThrows(IllegalArgumentException.class, () -> new CommitReceipt(ACTOR, REQUEST, DIGEST_B, inputs,
                0L, DIGEST_A, true, 0L, emptyMembers()));
        assertThrows(IllegalArgumentException.class, () -> new CommitReceipt(ACTOR, REQUEST, DIGEST_B, inputs,
                -1L, DIGEST_A, true, 0L, emptyMembers()));
        assertThrows(IllegalArgumentException.class, () -> new CommitReceipt(ACTOR, REQUEST, DIGEST_B, inputs,
                6L, DIGEST_A, true, -1L, emptyMembers()));
        assertThrows(IllegalArgumentException.class, () -> new CommitReceipt(ACTOR, REQUEST, DIGEST_B, inputs,
                6L, DIGEST_A, true, Long.MIN_VALUE, emptyMembers()));
        final CommitReceipt lowest = new CommitReceipt(ACTOR, REQUEST, DIGEST_B,
                inputs(0L, DIGEST_A, OperationKind.SAVE_MAP, Optional.empty(), emptyMembers()), 1L, DIGEST_A,
                true, 0L, emptyMembers());
        assertEquals(1L, lowest.committedRevision());
        assertEquals(0L, lowest.recordedAtEpochMillis());
        final CommitReceipt highest = new CommitReceipt(ACTOR, REQUEST, DIGEST_B,
                inputs(Long.MAX_VALUE, DIGEST_A, OperationKind.SAVE_MAP, Optional.empty(), emptyMembers()),
                Long.MAX_VALUE, DIGEST_A, false, Long.MAX_VALUE, emptyMembers());
        assertEquals(Long.MAX_VALUE, highest.committedRevision());
        assertEquals(Long.MAX_VALUE, highest.recordedAtEpochMillis());
    }

    @Test
    void receiptChangedCommitAdvancesExactlyOneAndRefusesTheMaximum() {
        // Long.MAX_VALUE - 1 plus one is the last legal content changing commit
        final CommitReceipt last = new CommitReceipt(ACTOR, REQUEST, DIGEST_B,
                inputs(Long.MAX_VALUE - 1L, DIGEST_A, OperationKind.SAVE_MAP, Optional.empty(), emptyMembers()),
                Long.MAX_VALUE, DIGEST_A, true, 0L, emptyMembers());
        assertEquals(Long.MAX_VALUE, last.committedRevision());
        // the maximum is refused before one is added, whatever the committed revision is
        for (long committed : new long[] {Long.MAX_VALUE, 5L, 1L}) {
            assertThrows(IllegalArgumentException.class, () -> new CommitReceipt(ACTOR, REQUEST, DIGEST_B,
                    inputs(Long.MAX_VALUE, DIGEST_A, OperationKind.SAVE_MAP, Optional.empty(), emptyMembers()),
                    committed, DIGEST_A, true, 0L, emptyMembers()));
        }
        // zero plus one is legal, five plus one is legal, and a wrong advance is refused
        assertEquals(1L, new CommitReceipt(ACTOR, REQUEST, DIGEST_B,
                inputs(0L, DIGEST_A, OperationKind.SAVE_MAP, Optional.empty(), emptyMembers()), 1L, DIGEST_A,
                true, 0L, emptyMembers()).committedRevision());
        for (long wrong : new long[] {5L, 7L, 4L, 100L}) {
            assertThrows(IllegalArgumentException.class, () -> new CommitReceipt(ACTOR, REQUEST, DIGEST_B,
                    inputs(5L, DIGEST_A, OperationKind.SAVE_MAP, Optional.empty(), emptyMembers()), wrong,
                    DIGEST_A, true, 0L, emptyMembers()),
                    "changed commit must refuse " + wrong + " for expected 5");
        }
    }

    @Test
    void receiptUnchangedCommitKeepsTheExpectedRevisionAndNeedsAtLeastOne() {
        for (long revision : new long[] {1L, 2L, Long.MAX_VALUE - 1L, Long.MAX_VALUE}) {
            final CommitReceipt receipt = new CommitReceipt(ACTOR, REQUEST, DIGEST_B,
                    inputs(revision, DIGEST_A, OperationKind.SAVE_MAP, Optional.empty(), emptyMembers()),
                    revision, DIGEST_A, false, 0L, emptyMembers());
            assertEquals(revision, receipt.committedRevision());
            assertFalse(receipt.contentChanged());
        }
        // an unchanged commit cannot keep a zero revision
        assertThrows(IllegalArgumentException.class, () -> new CommitReceipt(ACTOR, REQUEST, DIGEST_B,
                inputs(0L, DIGEST_A, OperationKind.SAVE_MAP, Optional.empty(), emptyMembers()), 1L, DIGEST_A,
                false, 0L, emptyMembers()));
        assertThrows(IllegalArgumentException.class, () -> new CommitReceipt(ACTOR, REQUEST, DIGEST_B,
                inputs(0L, DIGEST_A, OperationKind.SAVE_MAP, Optional.empty(), emptyMembers()), 0L, DIGEST_A,
                false, 0L, emptyMembers()));
        // an unchanged commit cannot advance the revision
        for (long wrong : new long[] {2L, 6L, 7L}) {
            assertThrows(IllegalArgumentException.class, () -> new CommitReceipt(ACTOR, REQUEST, DIGEST_B,
                    inputs(5L, DIGEST_A, OperationKind.SAVE_MAP, Optional.empty(), emptyMembers()), wrong,
                    DIGEST_A, false, 0L, emptyMembers()),
                    "unchanged commit must refuse " + wrong + " for expected 5");
        }
    }

    @Test
    void receiptRejectsAnActorThatDiffersFromTheInputs() {
        assertThrows(IllegalArgumentException.class, () -> new CommitReceipt(OTHER_ACTOR, REQUEST, DIGEST_B,
                legalInputs(), 6L, DIGEST_A, true, 0L, emptyMembers()));
    }

    @Test
    void receiptRejectsARequestThatDiffersFromTheInputs() {
        assertThrows(IllegalArgumentException.class, () -> new CommitReceipt(ACTOR, OTHER_REQUEST, DIGEST_B,
                legalInputs(), 6L, DIGEST_A, true, 0L, emptyMembers()));
    }

    @Test
    void receiptRejectsAPayloadDigestThatDiffersFromTheInputs() {
        assertThrows(IllegalArgumentException.class, () -> new CommitReceipt(ACTOR, REQUEST, DIGEST_B,
                legalInputs(), 6L, DIGEST_B, true, 0L, emptyMembers()));
    }

    @Test
    void receiptAcceptsEqualButDistinctActorRequestAndPayloadAndKeepsEachOwnReference() {
        final UUID actor = new UUID(0x1L, 0x2L);
        final UUID distinctActor = new UUID(0x1L, 0x2L);
        final Ids.RequestId request = new Ids.RequestId(new UUID(0x3L, 0x4L));
        final Ids.RequestId distinctRequest = new Ids.RequestId(new UUID(0x3L, 0x4L));
        final String payload = new String(DIGEST_A);
        final String distinctPayload = new String(DIGEST_A);
        assertNotSame(actor, distinctActor);
        assertNotSame(request, distinctRequest);
        assertNotSame(payload, distinctPayload);
        final FingerprintInputs inputs = new FingerprintInputs(actor,
                new FingerprintTarget(DEFINITION, emptyMembers()), request, 4L, payload,
                OperationKind.SAVE_MAP, Optional.empty(), emptyMembers());
        final CommitReceipt receipt = new CommitReceipt(distinctActor, distinctRequest, DIGEST_B, inputs, 5L,
                distinctPayload, true, 7L, emptyMembers());
        assertSame(distinctActor, receipt.actorUuid());
        assertSame(distinctRequest, receipt.requestId());
        assertSame(distinctPayload, receipt.payloadDigest());
        assertSame(actor, receipt.fingerprintInputs().actorUuid());
        assertSame(request, receipt.fingerprintInputs().requestId());
        assertSame(payload, receipt.fingerprintInputs().payloadDigest());
    }

    @Test
    void receiptAcceptsAFingerprintTextThatDoesNotDescribeItsOwnInputs() {
        final CommitReceipt receipt = new CommitReceipt(ACTOR, REQUEST, DIGEST_ZERO, legalInputs(), 6L,
                DIGEST_A, true, 1L, emptyMembers());
        assertSame(DIGEST_ZERO, receipt.fingerprint());
        assertNotEquals(receipt.fingerprint(), receipt.fingerprintInputs().payloadDigest());
        // no pseudo certification: the all zero fingerprint is stored exactly as supplied
        assertEquals(DIGEST_ZERO, receipt.fingerprint());
    }

    @Test
    void receiptKeepsTheSameFingerprintInputsReferenceAndMemberContainer() {
        final FingerprintInputs inputs = legalInputs();
        final ExactJson.FrozenJson frozen = members("{\"\u672a\u6765\":[1,{\"a\":null}]}");
        final CommitReceipt receipt = new CommitReceipt(ACTOR, REQUEST, DIGEST_B, inputs, 6L, DIGEST_A, true,
                12L, frozen);
        assertSame(inputs, receipt.fingerprintInputs());
        assertSame(frozen, receipt.members());
        assertSame(inputs.members(), receipt.fingerprintInputs().members());
        assertSame(inputs.target(), receipt.fingerprintInputs().target());
    }

    @Test
    void receiptKeepsUnknownMembersEmptyKeysUnicodeAndExactNumbersUnchanged() {
        for (String json : LEGAL_OBJECT_JSON) {
            final ExactJson.FrozenJson frozen = members(json);
            final CommitReceipt receipt = new CommitReceipt(ACTOR, REQUEST, DIGEST_B, legalInputs(), 6L,
                    DIGEST_A, true, 0L, frozen);
            assertSame(frozen, receipt.members());
            assertEquals(frozen.canonical(), receipt.members().canonical());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The thousand candidate model
    // ---------------------------------------------------------------------------------------------

    /** One generated candidate: every component the two records need, with no production value. */
    private record Candidate(int index, UUID actorUuid, DefinitionRef targetRef,
            ExactJson.FrozenJson targetMembers, Ids.RequestId requestId, long expectedRevision,
            String payloadDigest, OperationKind operationKind, Optional<String> migrationDigest,
            ExactJson.FrozenJson inputsMembers, UUID receiptActor, Ids.RequestId receiptRequest,
            String fingerprint, long committedRevision, String receiptPayloadDigest,
            boolean contentChanged, long recordedAtEpochMillis, ExactJson.FrozenJson receiptMembers) {
    }

    private static final long MODEL_SEED = 20261015L;
    private static final int MODEL_CANDIDATES = 1000;

    private static String digestAt(Random random) {
        // the fixed tests enumerate every malformed digest text exhaustively; the big sample keeps a
        // legal majority so that both verdicts stay well populated at four digest positions
        if (random.nextInt(10) < 9) {
            return legalDigest(random);
        }
        final String legal = legalDigest(random);
        return switch (random.nextInt(6)) {
            case 0 -> "0".repeat(63);
            case 1 -> legal + "0";
            case 2 -> legal.substring(0, 63) + "A";
            case 3 -> legal.substring(0, 63) + "g";
            case 4 -> legal.substring(0, 63) + "\u0000";
            default -> "\u00e9" + legal.substring(1);
        };
    }

    private static long revisionAt(Random random, int index) {
        return switch (random.nextInt(12)) {
            case 0 -> -1L;
            case 1 -> 0L;
            case 2 -> 1L;
            case 3 -> Long.MAX_VALUE;
            case 4 -> Long.MAX_VALUE - 1L;
            case 5 -> index;
            default -> random.nextLong() & Long.MAX_VALUE;
        };
    }

    private static long committedAt(Random random, long expectedRevision, boolean contentChanged) {
        final BigInteger expected = BigInteger.valueOf(expectedRevision);
        final BigInteger one = BigInteger.ONE;
        final BigInteger max = BigInteger.valueOf(Long.MAX_VALUE);
        final BigInteger candidate = switch (random.nextInt(10)) {
            case 0, 1, 2, 3, 4, 5, 6 -> contentChanged ? expected.add(one) : expected;
            case 7 -> contentChanged ? expected : expected.add(one);
            case 8 -> expected.add(BigInteger.TWO);
            default -> BigInteger.ZERO;
        };
        return candidate.min(max).longValue();
    }

    private static long recordedAt(Random random) {
        return switch (random.nextInt(12)) {
            case 0 -> -1L;
            case 1 -> 0L;
            case 2 -> Long.MAX_VALUE;
            default -> random.nextLong() & Long.MAX_VALUE;
        };
    }

    private static ExactJson.FrozenJson memberShapeAt(Random random, String key, int index) {
        if (random.nextInt(9) == 0) {
            return members(NON_OBJECT_SHAPES.get(random.nextInt(NON_OBJECT_SHAPES.size())));
        }
        return members("{\"" + key + "\":" + index + "}");
    }

    private static Candidate nextCandidate(Random random, int index) {
        final UUID actorUuid = new UUID(random.nextLong(), random.nextLong());
        final DefinitionRef targetRef = new DefinitionRef(
                new Ids.WorldId(new UUID(random.nextLong(), random.nextLong())),
                new Ids.DefinitionId(new UUID(random.nextLong(), random.nextLong())));
        final ExactJson.FrozenJson targetMembers = members("{\"seed\":" + index + "}");
        final Ids.RequestId requestId = new Ids.RequestId(new UUID(random.nextLong(), random.nextLong()));
        final long expectedRevision = revisionAt(random, index);
        final String payloadDigest = digestAt(random);
        final OperationKind operationKind = random.nextBoolean()
                ? OperationKind.SAVE_MAP
                : OperationKind.MIGRATE_MAP;
        final Optional<String> migrationDigest = random.nextInt(3) == 0
                ? Optional.empty()
                : Optional.of(digestAt(random));
        final ExactJson.FrozenJson inputsMembers = memberShapeAt(random, "i", index);
        final String fingerprint = digestAt(random);
        final boolean contentChanged = random.nextBoolean();
        final long committedRevision = committedAt(random, expectedRevision, contentChanged);
        final long recordedAtEpochMillis = recordedAt(random);
        // three identity shapes: the same reference, an equal but distinct instance, a mismatch
        final UUID receiptActor = switch (random.nextInt(10)) {
            case 0 -> new UUID(actorUuid.getMostSignificantBits(), actorUuid.getLeastSignificantBits());
            case 1 -> new UUID(random.nextLong(), random.nextLong());
            default -> actorUuid;
        };
        final UUID requestValue = requestId.value();
        final Ids.RequestId receiptRequest = switch (random.nextInt(10)) {
            case 0 -> new Ids.RequestId(new UUID(requestValue.getMostSignificantBits(),
                    requestValue.getLeastSignificantBits()));
            case 1 -> new Ids.RequestId(new UUID(random.nextLong(), random.nextLong()));
            default -> requestId;
        };
        final String receiptPayloadDigest = switch (random.nextInt(10)) {
            case 0 -> new String(payloadDigest);
            case 1 -> digestAt(random);
            default -> payloadDigest;
        };
        final ExactJson.FrozenJson receiptMembers = memberShapeAt(random, "r", index);
        return new Candidate(index, actorUuid, targetRef, targetMembers, requestId, expectedRevision,
                payloadDigest, operationKind, migrationDigest, inputsMembers, receiptActor, receiptRequest,
                fingerprint, committedRevision, receiptPayloadDigest, contentChanged, recordedAtEpochMillis,
                receiptMembers);
    }

    /** Generates the whole immutable candidate list before any production record is called. */
    private static List<Candidate> modelCandidates() {
        final Random random = new Random(MODEL_SEED);
        final List<Candidate> candidates = new ArrayList<>(MODEL_CANDIDATES);
        for (int index = 0; index < MODEL_CANDIDATES; index++) {
            candidates.add(nextCandidate(random, index));
        }
        return List.copyOf(candidates);
    }

    private static boolean modelDigest(String text) {
        if (text == null || text.length() != 64) {
            return false;
        }
        for (int index = 0; index < text.length(); index++) {
            final char character = text.charAt(index);
            if (HEX.indexOf(character) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean modelMembers(ExactJson.FrozenJson members) {
        if (members == null) {
            return false;
        }
        final String canonical = members.canonical();
        return !canonical.isEmpty() && canonical.charAt(0) == '{';
    }

    private static boolean modelInputsAccept(Candidate candidate) {
        final String migration = candidate.migrationDigest().orElse(null);
        return candidate.actorUuid() != null
                && candidate.targetRef() != null
                && modelMembers(candidate.targetMembers())
                && candidate.requestId() != null
                && candidate.operationKind() != null
                && candidate.migrationDigest() != null
                && modelDigest(candidate.payloadDigest())
                && (migration == null || modelDigest(migration))
                && modelMembers(candidate.inputsMembers())
                && candidate.expectedRevision() >= 0L;
    }

    /** The revision rule with BigInteger, so the model never relies on the production overflow. */
    private static boolean modelRevisionAccept(Candidate candidate) {
        final BigInteger expected = BigInteger.valueOf(candidate.expectedRevision());
        final BigInteger committed = BigInteger.valueOf(candidate.committedRevision());
        if (candidate.contentChanged()) {
            if (candidate.expectedRevision() == Long.MAX_VALUE) {
                return false;
            }
            return committed.compareTo(expected.add(BigInteger.ONE)) == 0;
        }
        if (candidate.expectedRevision() < 1L) {
            return false;
        }
        return committed.compareTo(expected) == 0;
    }

    private static boolean modelReceiptAccept(Candidate candidate) {
        if (!modelInputsAccept(candidate)) {
            return false;
        }
        if (!modelDigest(candidate.fingerprint())) {
            return false;
        }
        if (!modelMembers(candidate.receiptMembers())) {
            return false;
        }
        if (candidate.committedRevision() < 1L || candidate.recordedAtEpochMillis() < 0L) {
            return false;
        }
        if (!candidate.receiptActor().equals(candidate.actorUuid())) {
            return false;
        }
        if (!candidate.receiptRequest().equals(candidate.requestId())) {
            return false;
        }
        if (!candidate.receiptPayloadDigest().equals(candidate.payloadDigest())) {
            return false;
        }
        return modelRevisionAccept(candidate);
    }

    /** Calls the production target and inputs; null means the real record refused the candidate. */
    private static FingerprintInputs produceInputs(Candidate candidate) {
        try {
            final FingerprintTarget target = new FingerprintTarget(candidate.targetRef(),
                    candidate.targetMembers());
            return new FingerprintInputs(candidate.actorUuid(), target, candidate.requestId(),
                    candidate.expectedRevision(), candidate.payloadDigest(), candidate.operationKind(),
                    candidate.migrationDigest(), candidate.inputsMembers());
        } catch (RuntimeException failure) {
            assertEquals(IllegalArgumentException.class, failure.getClass(),
                    "only IllegalArgumentException may refuse the inputs of candidate " + candidate.index());
            return null;
        }
    }

    /** Calls the production receipt; null means the real record refused the candidate. */
    private static CommitReceipt produceReceipt(Candidate candidate, FingerprintInputs inputs) {
        try {
            return new CommitReceipt(candidate.receiptActor(), candidate.receiptRequest(),
                    candidate.fingerprint(), inputs, candidate.committedRevision(),
                    candidate.receiptPayloadDigest(), candidate.contentChanged(),
                    candidate.recordedAtEpochMillis(), candidate.receiptMembers());
        } catch (RuntimeException failure) {
            assertEquals(IllegalArgumentException.class, failure.getClass(),
                    "only IllegalArgumentException may refuse the receipt of candidate " + candidate.index());
            return null;
        }
    }

    /**
     * Proves that a deliberately broken candidate is refused by the independent model <em>and</em> by
     * the real record. {@link #produceInputs} turns a refusal into {@code null}, so the record side is
     * driven directly here instead of through that helper: the exception must really be thrown.
     */
    private static void assertInputsRefused(Candidate candidate, int index) {
        assertFalse(modelInputsAccept(candidate), "the model must refuse the broken inputs at " + index);
        assertThrows(IllegalArgumentException.class, () -> {
            final FingerprintTarget target = new FingerprintTarget(candidate.targetRef(),
                    candidate.targetMembers());
            new FingerprintInputs(candidate.actorUuid(), target, candidate.requestId(),
                    candidate.expectedRevision(), candidate.payloadDigest(), candidate.operationKind(),
                    candidate.migrationDigest(), candidate.inputsMembers());
        }, "the record must refuse the broken inputs at " + index);
    }

    @Test
    void thousandFixedSeedCandidatesFollowTheIndependentComponentModel() {
        final List<Candidate> candidates = modelCandidates();
        assertEquals(MODEL_CANDIDATES, candidates.size());
        final List<Boolean> inputsVerdicts = new ArrayList<>(candidates.size());
        final List<Boolean> receiptVerdicts = new ArrayList<>(candidates.size());
        // the model decides every verdict before the first production record is constructed
        for (Candidate candidate : candidates) {
            inputsVerdicts.add(modelInputsAccept(candidate));
            receiptVerdicts.add(modelReceiptAccept(candidate));
        }
        assertTrue(inputsVerdicts.contains(Boolean.TRUE), "the model must accept some inputs");
        assertTrue(inputsVerdicts.contains(Boolean.FALSE), "the model must refuse some inputs");
        assertTrue(receiptVerdicts.contains(Boolean.TRUE), "the model must accept some receipts");
        assertTrue(receiptVerdicts.contains(Boolean.FALSE), "the model must refuse some receipts");

        int acceptedInputs = 0;
        int refusedInputs = 0;
        // The four counters below are deliberately not two. When the real inputs are refused there is
        // no inputs value to hand to the receipt, so that candidate never reaches a real
        // CommitReceipt construction: calling it a refused receipt would claim coverage this flow
        // does not have. logicalReceiptRejections is therefore a model-only verdict, while
        // actualReceiptAttempts counts the real CommitReceipt constructions, and only those attempts
        // can report a real receipt rejection.
        int logicalReceiptRejections = 0;
        int actualReceiptAttempts = 0;
        int actualReceiptRejections = 0;
        int actualReceiptSuccesses = 0;
        for (int index = 0; index < candidates.size(); index++) {
            final Candidate candidate = candidates.get(index);
            final FingerprintInputs inputs = produceInputs(candidate);
            assertEquals(inputsVerdicts.get(index), inputs != null,
                    "inputs verdict at candidate " + index);
            if (inputs == null) {
                refusedInputs++;
                assertFalse(modelReceiptAccept(candidate),
                        "a refused inputs candidate must refuse the receipt too, at " + index);
                logicalReceiptRejections++;
                continue;
            }
            acceptedInputs++;
            actualReceiptAttempts++;
            final CommitReceipt receipt = produceReceipt(candidate, inputs);
            assertEquals(receiptVerdicts.get(index), receipt != null,
                    "receipt verdict at candidate " + index);
            if (receipt == null) {
                actualReceiptRejections++;
            } else {
                actualReceiptSuccesses++;
            }
        }
        int modelAcceptedReceipts = 0;
        for (Boolean verdict : receiptVerdicts) {
            if (verdict) {
                modelAcceptedReceipts++;
            }
        }
        assertEquals(MODEL_CANDIDATES, acceptedInputs + refusedInputs);
        assertEquals(MODEL_CANDIDATES, logicalReceiptRejections + actualReceiptAttempts);
        assertEquals(actualReceiptAttempts, acceptedInputs);
        assertEquals(actualReceiptAttempts, actualReceiptRejections + actualReceiptSuccesses);
        // the model-accepted receipts are exactly the ones the real record built
        assertEquals(modelAcceptedReceipts, actualReceiptSuccesses);
        assertTrue(acceptedInputs > 0 && refusedInputs > 0);
        assertTrue(actualReceiptSuccesses > 0 && actualReceiptRejections > 0);
        // every accepted candidate below is a real, distinct set of identities
        assertTrue(actualReceiptSuccesses >= 100,
                "the model must accept a substantial sample, got " + actualReceiptSuccesses);
        // the real call count is disclosed instead of being hidden behind the logical count
        System.out.println("a4b_old_stream logical_candidates=" + MODEL_CANDIDATES
                + " accepted_inputs=" + acceptedInputs
                + " refused_inputs=" + refusedInputs
                + " logical_receipt_rejections=" + logicalReceiptRejections
                + " actual_receipt_attempts=" + actualReceiptAttempts
                + " actual_receipt_rejections=" + actualReceiptRejections
                + " actual_receipt_successes=" + actualReceiptSuccesses);
    }

    @Test
    void thousandAcceptedCandidatesKeepEveryValueAndReferenceAndStandardRecordHash() {
        int checked = 0;
        int expectedAccepted = 0;
        for (Candidate candidate : modelCandidates()) {
            if (!modelReceiptAccept(candidate)) {
                continue;
            }
            expectedAccepted++;
            final FingerprintInputs inputs = produceInputs(candidate);
            assertNotNull(inputs, "candidate " + candidate.index() + " must build its inputs");
            final CommitReceipt receipt = produceReceipt(candidate, inputs);
            assertNotNull(receipt, "candidate " + candidate.index() + " must build its receipt");

            assertSame(candidate.actorUuid(), inputs.actorUuid());
            assertSame(candidate.requestId(), inputs.requestId());
            assertSame(candidate.payloadDigest(), inputs.payloadDigest());
            assertSame(candidate.operationKind(), inputs.operationKind());
            assertSame(candidate.migrationDigest(), inputs.migrationDigest());
            assertSame(candidate.inputsMembers(), inputs.members());
            assertSame(candidate.targetRef(), inputs.target().target());
            assertSame(candidate.targetMembers(), inputs.target().members());
            assertEquals(candidate.expectedRevision(), inputs.expectedRevision());
            final String migration = candidate.migrationDigest().orElse(null);
            if (migration != null) {
                assertSame(migration, inputs.migrationDigest().get());
            }

            assertSame(candidate.receiptActor(), receipt.actorUuid());
            assertSame(candidate.receiptRequest(), receipt.requestId());
            assertSame(candidate.fingerprint(), receipt.fingerprint());
            assertSame(inputs, receipt.fingerprintInputs());
            assertSame(candidate.receiptPayloadDigest(), receipt.payloadDigest());
            assertSame(candidate.receiptMembers(), receipt.members());
            assertEquals(candidate.committedRevision(), receipt.committedRevision());
            assertEquals(candidate.contentChanged(), receipt.contentChanged());
            assertEquals(candidate.recordedAtEpochMillis(), receipt.recordedAtEpochMillis());

            // the standard record equality and hash of both records, checked on an equal copy
            final FingerprintInputs equalInputs = new FingerprintInputs(candidate.actorUuid(), inputs.target(),
                    candidate.requestId(), candidate.expectedRevision(), candidate.payloadDigest(),
                    candidate.operationKind(), candidate.migrationDigest(), candidate.inputsMembers());
            assertEquals(inputs, equalInputs);
            assertEquals(inputs.hashCode(), equalInputs.hashCode());
            final CommitReceipt equalReceipt = new CommitReceipt(candidate.receiptActor(),
                    candidate.receiptRequest(), candidate.fingerprint(), inputs,
                    candidate.committedRevision(), candidate.receiptPayloadDigest(), candidate.contentChanged(),
                    candidate.recordedAtEpochMillis(), candidate.receiptMembers());
            assertEquals(receipt, equalReceipt);
            assertEquals(receipt.hashCode(), equalReceipt.hashCode());

            // a different member container breaks equality of both records
            final ExactJson.FrozenJson other = members("{\"other\":" + candidate.index() + "}");
            assertNotEquals(receipt, new CommitReceipt(candidate.receiptActor(), candidate.receiptRequest(),
                    candidate.fingerprint(), inputs, candidate.committedRevision(),
                    candidate.receiptPayloadDigest(), candidate.contentChanged(),
                    candidate.recordedAtEpochMillis(), other));
            assertNotEquals(inputs, new FingerprintInputs(candidate.actorUuid(), inputs.target(),
                    candidate.requestId(), candidate.expectedRevision(), candidate.payloadDigest(),
                    candidate.operationKind(), candidate.migrationDigest(), other));
            checked++;
        }
        assertEquals(expectedAccepted, checked);
        assertTrue(checked >= 100, "the accepted sample must be substantial, got " + checked);
    }

    private static Candidate withActor(Candidate candidate, UUID actor) {
        return new Candidate(candidate.index(), actor, candidate.targetRef(), candidate.targetMembers(),
                candidate.requestId(), candidate.expectedRevision(), candidate.payloadDigest(),
                candidate.operationKind(), candidate.migrationDigest(), candidate.inputsMembers(), actor,
                candidate.receiptRequest(), candidate.fingerprint(), candidate.committedRevision(),
                candidate.receiptPayloadDigest(), candidate.contentChanged(),
                candidate.recordedAtEpochMillis(), candidate.receiptMembers());
    }

    private static Candidate withRequest(Candidate candidate, Ids.RequestId request) {
        return new Candidate(candidate.index(), candidate.actorUuid(), candidate.targetRef(),
                candidate.targetMembers(), request, candidate.expectedRevision(), candidate.payloadDigest(),
                candidate.operationKind(), candidate.migrationDigest(), candidate.inputsMembers(),
                candidate.receiptActor(), request, candidate.fingerprint(), candidate.committedRevision(),
                candidate.receiptPayloadDigest(), candidate.contentChanged(),
                candidate.recordedAtEpochMillis(), candidate.receiptMembers());
    }

    private static Candidate withPayload(Candidate candidate, String payload) {
        return new Candidate(candidate.index(), candidate.actorUuid(), candidate.targetRef(),
                candidate.targetMembers(), candidate.requestId(), candidate.expectedRevision(), payload,
                candidate.operationKind(), candidate.migrationDigest(), candidate.inputsMembers(),
                candidate.receiptActor(), candidate.receiptRequest(), candidate.fingerprint(),
                candidate.committedRevision(), payload, candidate.contentChanged(),
                candidate.recordedAtEpochMillis(), candidate.receiptMembers());
    }

    private static Candidate withRevision(Candidate candidate, long expected, long committed,
            boolean contentChanged) {
        return new Candidate(candidate.index(), candidate.actorUuid(), candidate.targetRef(),
                candidate.targetMembers(), candidate.requestId(), expected, candidate.payloadDigest(),
                candidate.operationKind(), candidate.migrationDigest(), candidate.inputsMembers(),
                candidate.receiptActor(), candidate.receiptRequest(), candidate.fingerprint(), committed,
                candidate.receiptPayloadDigest(), contentChanged, candidate.recordedAtEpochMillis(),
                candidate.receiptMembers());
    }

    private static Candidate withTargetRef(Candidate candidate, DefinitionRef definition) {
        return new Candidate(candidate.index(), candidate.actorUuid(), definition, candidate.targetMembers(),
                candidate.requestId(), candidate.expectedRevision(), candidate.payloadDigest(),
                candidate.operationKind(), candidate.migrationDigest(), candidate.inputsMembers(),
                candidate.receiptActor(), candidate.receiptRequest(), candidate.fingerprint(),
                candidate.committedRevision(), candidate.receiptPayloadDigest(), candidate.contentChanged(),
                candidate.recordedAtEpochMillis(), candidate.receiptMembers());
    }

    private static Candidate withInputsMembers(Candidate candidate, ExactJson.FrozenJson members) {
        return new Candidate(candidate.index(), candidate.actorUuid(), candidate.targetRef(),
                candidate.targetMembers(), candidate.requestId(), candidate.expectedRevision(),
                candidate.payloadDigest(), candidate.operationKind(), candidate.migrationDigest(), members,
                candidate.receiptActor(), candidate.receiptRequest(), candidate.fingerprint(),
                candidate.committedRevision(), candidate.receiptPayloadDigest(), candidate.contentChanged(),
                candidate.recordedAtEpochMillis(), candidate.receiptMembers());
    }

    private static Candidate withMigrationDigest(Candidate candidate, Optional<String> migration) {
        return new Candidate(candidate.index(), candidate.actorUuid(), candidate.targetRef(),
                candidate.targetMembers(), candidate.requestId(), candidate.expectedRevision(),
                candidate.payloadDigest(), candidate.operationKind(), migration, candidate.inputsMembers(),
                candidate.receiptActor(), candidate.receiptRequest(), candidate.fingerprint(),
                candidate.committedRevision(), candidate.receiptPayloadDigest(), candidate.contentChanged(),
                candidate.recordedAtEpochMillis(), candidate.receiptMembers());
    }

    private static Candidate withOperationKind(Candidate candidate, OperationKind kind) {
        return new Candidate(candidate.index(), candidate.actorUuid(), candidate.targetRef(),
                candidate.targetMembers(), candidate.requestId(), candidate.expectedRevision(),
                candidate.payloadDigest(), kind, candidate.migrationDigest(), candidate.inputsMembers(),
                candidate.receiptActor(), candidate.receiptRequest(), candidate.fingerprint(),
                candidate.committedRevision(), candidate.receiptPayloadDigest(), candidate.contentChanged(),
                candidate.recordedAtEpochMillis(), candidate.receiptMembers());
    }

    private static Candidate withFingerprint(Candidate candidate, String fingerprint) {
        return new Candidate(candidate.index(), candidate.actorUuid(), candidate.targetRef(),
                candidate.targetMembers(), candidate.requestId(), candidate.expectedRevision(),
                candidate.payloadDigest(), candidate.operationKind(), candidate.migrationDigest(),
                candidate.inputsMembers(), candidate.receiptActor(), candidate.receiptRequest(), fingerprint,
                candidate.committedRevision(), candidate.receiptPayloadDigest(), candidate.contentChanged(),
                candidate.recordedAtEpochMillis(), candidate.receiptMembers());
    }

    private static Candidate withReceiptMembers(Candidate candidate, ExactJson.FrozenJson members) {
        return new Candidate(candidate.index(), candidate.actorUuid(), candidate.targetRef(),
                candidate.targetMembers(), candidate.requestId(), candidate.expectedRevision(),
                candidate.payloadDigest(), candidate.operationKind(), candidate.migrationDigest(),
                candidate.inputsMembers(), candidate.receiptActor(), candidate.receiptRequest(),
                candidate.fingerprint(), candidate.committedRevision(), candidate.receiptPayloadDigest(),
                candidate.contentChanged(), candidate.recordedAtEpochMillis(), members);
    }

    private static Candidate withRecordedAt(Candidate candidate, long recordedAt) {
        return new Candidate(candidate.index(), candidate.actorUuid(), candidate.targetRef(),
                candidate.targetMembers(), candidate.requestId(), candidate.expectedRevision(),
                candidate.payloadDigest(), candidate.operationKind(), candidate.migrationDigest(),
                candidate.inputsMembers(), candidate.receiptActor(), candidate.receiptRequest(),
                candidate.fingerprint(), candidate.committedRevision(), candidate.receiptPayloadDigest(),
                candidate.contentChanged(), recordedAt, candidate.receiptMembers());
    }

    private static Candidate withReceiptActorOnly(Candidate candidate, UUID actor) {
        return new Candidate(candidate.index(), candidate.actorUuid(), candidate.targetRef(),
                candidate.targetMembers(), candidate.requestId(), candidate.expectedRevision(),
                candidate.payloadDigest(), candidate.operationKind(), candidate.migrationDigest(),
                candidate.inputsMembers(), actor, candidate.receiptRequest(), candidate.fingerprint(),
                candidate.committedRevision(), candidate.receiptPayloadDigest(), candidate.contentChanged(),
                candidate.recordedAtEpochMillis(), candidate.receiptMembers());
    }

    private static Candidate withReceiptRequestOnly(Candidate candidate, Ids.RequestId request) {
        return new Candidate(candidate.index(), candidate.actorUuid(), candidate.targetRef(),
                candidate.targetMembers(), candidate.requestId(), candidate.expectedRevision(),
                candidate.payloadDigest(), candidate.operationKind(), candidate.migrationDigest(),
                candidate.inputsMembers(), candidate.receiptActor(), request, candidate.fingerprint(),
                candidate.committedRevision(), candidate.receiptPayloadDigest(), candidate.contentChanged(),
                candidate.recordedAtEpochMillis(), candidate.receiptMembers());
    }

    private static Candidate withReceiptPayloadOnly(Candidate candidate, String payload) {
        return new Candidate(candidate.index(), candidate.actorUuid(), candidate.targetRef(),
                candidate.targetMembers(), candidate.requestId(), candidate.expectedRevision(),
                candidate.payloadDigest(), candidate.operationKind(), candidate.migrationDigest(),
                candidate.inputsMembers(), candidate.receiptActor(), candidate.receiptRequest(),
                candidate.fingerprint(), candidate.committedRevision(), payload, candidate.contentChanged(),
                candidate.recordedAtEpochMillis(), candidate.receiptMembers());
    }

    private static String otherLegalDigest(String current) {
        return DIGEST_B.equals(current) ? DIGEST_A : DIGEST_B;
    }

    private static long otherLegalRevision(long current) {
        for (long candidate : new long[] {1L, 2L, 3L, 5L, 7L, 11L, 13L, 17L, 19L, 23L, 29L, 31L}) {
            if (candidate != current) {
                return candidate;
            }
        }
        throw new IllegalStateException("no distinct legal revision for " + current);
    }

    /** One legal change per dimension; every mutant must stay legal and must break equality. */
    private static List<Candidate> legalMutations(Candidate candidate) {
        final List<Candidate> mutations = new ArrayList<>();
        final UUID actor = new UUID(candidate.actorUuid().getMostSignificantBits() ^ 0x55L,
                candidate.actorUuid().getLeastSignificantBits() ^ 0xAA55L);
        mutations.add(withActor(candidate, actor));
        final UUID requestValue = candidate.requestId().value();
        mutations.add(withRequest(candidate, new Ids.RequestId(new UUID(
                requestValue.getMostSignificantBits() ^ 0x77L, requestValue.getLeastSignificantBits()))));
        mutations.add(withPayload(candidate, otherLegalDigest(candidate.payloadDigest())));
        final long keptRevision = otherLegalRevision(candidate.expectedRevision());
        mutations.add(withRevision(candidate, keptRevision,
                candidate.contentChanged() ? keptRevision + 1L : keptRevision, candidate.contentChanged()));
        final long flippedRevision = otherLegalRevision(candidate.expectedRevision());
        mutations.add(withRevision(candidate, flippedRevision,
                candidate.contentChanged() ? flippedRevision : flippedRevision + 1L, !candidate.contentChanged()));
        mutations.add(withMigrationDigest(candidate, candidate.migrationDigest().isEmpty()
                ? Optional.of(DIGEST_ZERO)
                : Optional.empty()));
        mutations.add(withOperationKind(candidate, candidate.operationKind() == OperationKind.SAVE_MAP
                ? OperationKind.MIGRATE_MAP
                : OperationKind.SAVE_MAP));
        mutations.add(withTargetRef(candidate, OTHER_DEFINITION));
        mutations.add(withInputsMembers(candidate, members("{\"left\":" + candidate.index() + "}")));
        mutations.add(withFingerprint(candidate, otherLegalDigest(candidate.fingerprint())));
        mutations.add(withReceiptMembers(candidate, members("{\"right\":" + candidate.index() + "}")));
        mutations.add(withRecordedAt(candidate, candidate.recordedAtEpochMillis() == 0L ? 1L : 0L));
        return List.copyOf(mutations);
    }

    /** One illegal change per rule; every mutant must be refused with IllegalArgumentException. */
    private static List<Candidate> illegalMutations(Candidate candidate) {
        final List<Candidate> mutations = new ArrayList<>();
        mutations.add(withReceiptActorOnly(candidate, new UUID(
                candidate.receiptActor().getMostSignificantBits() ^ 0x99L,
                candidate.receiptActor().getLeastSignificantBits())));
        final UUID requestValue = candidate.receiptRequest().value();
        mutations.add(withReceiptRequestOnly(candidate, new Ids.RequestId(new UUID(
                requestValue.getMostSignificantBits() ^ 0x99L, requestValue.getLeastSignificantBits()))));
        mutations.add(withReceiptPayloadOnly(candidate, otherLegalDigest(candidate.receiptPayloadDigest())));
        // A revision that misses the required advance. It is built from explicit edge branches rather
        // than by clamping, because clamping produced exactly the legal value whenever the expected
        // revision sat one below the maximum and the commit was a changing one.
        final long expected = candidate.expectedRevision();
        final long wrongAdvance;
        if (candidate.contentChanged()) {
            wrongAdvance = expected >= Long.MAX_VALUE - 1L ? expected - 2L : expected + 2L;
        } else {
            wrongAdvance = expected == Long.MAX_VALUE ? expected - 1L : expected + 1L;
        }
        mutations.add(withRevision(candidate, expected, wrongAdvance, candidate.contentChanged()));
        // Flipping contentChanged alone. At the maximum revision both legal shapes carry the maximum
        // committed revision, so the flipped mutation is pinned one below it to stay genuinely illegal.
        mutations.add(withRevision(candidate, expected,
                expected == Long.MAX_VALUE ? expected - 1L : candidate.committedRevision(),
                !candidate.contentChanged()));
        return List.copyOf(mutations);
    }

    @Test
    void changingEachLegalComponentBreaksRecordEquality() {
        final List<Candidate> accepted = new ArrayList<>();
        for (Candidate candidate : modelCandidates()) {
            if (modelReceiptAccept(candidate)) {
                accepted.add(candidate);
            }
        }
        assertFalse(accepted.isEmpty());
        final int stride = Math.max(1, accepted.size() / 40);
        int sampled = 0;
        for (int index = 0; index < accepted.size(); index += stride) {
            final Candidate candidate = accepted.get(index);
            final CommitReceipt base = produceReceipt(candidate, produceInputs(candidate));
            assertNotNull(base);
            for (Candidate mutated : legalMutations(candidate)) {
                assertTrue(modelReceiptAccept(mutated),
                        "a legal mutation must stay legal at candidate " + candidate.index());
                final CommitReceipt produced = produceReceipt(mutated, produceInputs(mutated));
                assertNotNull(produced);
                assertNotEquals(base, produced, "a legal change must break equality at " + candidate.index());
            }
            sampled++;
        }
        assertTrue(sampled > 0);
    }

    @Test
    void illegalMutationsAreExplicitlyRefused() {
        final List<Candidate> accepted = new ArrayList<>();
        for (Candidate candidate : modelCandidates()) {
            if (modelReceiptAccept(candidate)) {
                accepted.add(candidate);
            }
        }
        assertFalse(accepted.isEmpty());
        final int stride = Math.max(1, accepted.size() / 40);
        int sampled = 0;
        for (int index = 0; index < accepted.size(); index += stride) {
            final Candidate candidate = accepted.get(index);
            final FingerprintInputs inputs = produceInputs(candidate);
            assertNotNull(inputs);
            for (Candidate mutated : illegalMutations(candidate)) {
                assertFalse(modelReceiptAccept(mutated),
                        "the model must refuse the illegal mutation at candidate " + candidate.index());
                assertThrows(IllegalArgumentException.class,
                        () -> new CommitReceipt(mutated.receiptActor(), mutated.receiptRequest(),
                                mutated.fingerprint(), inputs, mutated.committedRevision(),
                                mutated.receiptPayloadDigest(), mutated.contentChanged(),
                                mutated.recordedAtEpochMillis(), mutated.receiptMembers()),
                        "the record must refuse the illegal mutation at candidate " + candidate.index());
            }
            // the shape, format and reference rules of the other records are refused as well
            assertThrows(IllegalArgumentException.class, () -> new FingerprintTarget(candidate.targetRef(),
                    members("[]")));
            assertThrows(IllegalArgumentException.class, () -> new FingerprintTarget(null,
                    candidate.targetMembers()));
            assertInputsRefused(withInputsMembers(candidate, members("null")), candidate.index());
            assertInputsRefused(withPayload(candidate, "0".repeat(63)), candidate.index());
            assertInputsRefused(withMigrationDigest(candidate, Optional.of("0".repeat(65))),
                    candidate.index());
            sampled++;
        }
        assertTrue(sampled > 0);
    }

    @Test
    void legalFixtureValuesAreThemselvesAccepted() {
        // a guard against a fixture that silently became illegal: the base values really construct
        assertNotNull(legalInputs());
        assertNotNull(legalReceipt());
        assertSame(DIGEST_A, legalReceipt().fingerprintInputs().payloadDigest());
        assertEquals(6L, legalReceipt().committedRevision());
        assertEquals(5L, legalReceipt().fingerprintInputs().expectedRevision());
    }

    // ---------------------------------------------------------------------------------------------
    // The second thousand candidate stream: every candidate really reaches CommitReceipt
    // ---------------------------------------------------------------------------------------------

    /**
     * The seed of the second stream. It is fixed and disjoint from {@link #MODEL_SEED} on purpose:
     * the two streams must not share their samples, so neither result can be explained by the other.
     */
    private static final long FRESH_SEED = 20261016L;

    /**
     * A legal digest whose first 16 characters encode {@code tag}, so every position of every
     * candidate of the second stream carries its own distinct and fully legal digest text.
     */
    private static String taggedDigest(Random random, long tag) {
        final StringBuilder builder = new StringBuilder(64);
        builder.append(String.format("%016x", tag));
        while (builder.length() < 64) {
            builder.append(HEX.charAt(random.nextInt(HEX.length())));
        }
        return builder.toString();
    }

    /** A legal object member container whose canonical text is unique to {@code kind} and {@code tag}. */
    private static ExactJson.FrozenJson taggedMembers(String kind, int tag) {
        return members("{\"" + kind + "\":" + tag + "}");
    }

    /** A UUID that is unique per (kind, tag) pair while its upper half stays a real random value. */
    private static UUID taggedUuid(Random random, int kind, int tag) {
        return new UUID(random.nextLong(), ((long) kind << 48) | tag);
    }

    /** An equal but distinct copy of actor, a genuinely different actor, or the same reference. */
    private static UUID freshReceiptActor(Random random, UUID actor, int tag) {
        return switch (random.nextInt(10)) {
            case 0 -> new UUID(actor.getMostSignificantBits(), actor.getLeastSignificantBits());
            case 1 -> taggedUuid(random, 5, tag);
            default -> actor;
        };
    }

    /** The same three shapes as {@link #freshReceiptActor}, for the request id. */
    private static Ids.RequestId freshReceiptRequest(Random random, Ids.RequestId request, int tag) {
        return switch (random.nextInt(10)) {
            case 0 -> new Ids.RequestId(new UUID(request.value().getMostSignificantBits(),
                    request.value().getLeastSignificantBits()));
            case 1 -> new Ids.RequestId(taggedUuid(random, 6, tag));
            default -> request;
        };
    }

    /** The same three shapes as {@link #freshReceiptActor}, for the payload digest text. */
    private static String freshReceiptPayload(Random random, String payload, int tag) {
        return switch (random.nextInt(10)) {
            case 0 -> new String(payload);
            case 1 -> taggedDigest(random, 3L * tag + 9L);
            default -> payload;
        };
    }

    /**
     * The committed revision of the second stream, decided by {@link BigInteger} arithmetic instead
     * of the {@code long} arithmetic the production record happens to use. The shapes are the exact
     * legal value, the swapped value, one advance too far and a genuinely random value; clamping only
     * keeps the {@code long} range, the verdict is always recomputed from the value itself.
     */
    private static long freshCommitted(Random random, long expectedRevision, boolean contentChanged,
            int tag) {
        final BigInteger expected = BigInteger.valueOf(expectedRevision);
        final BigInteger candidate = switch (random.nextInt(10)) {
            case 0, 1, 2, 3, 4, 5, 6 -> contentChanged ? expected.add(BigInteger.ONE) : expected;
            case 7 -> contentChanged ? expected : expected.add(BigInteger.ONE);
            case 8 -> expected.add(BigInteger.TWO);
            default -> BigInteger.valueOf(random.nextLong() & Long.MAX_VALUE);
        };
        return candidate.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
    }

    /**
     * Builds the thousand candidates of the second stream. Every target and inputs component is legal
     * by construction, so no candidate can be refused before the receipt, and the receipt side
     * carries boundary and random revisions, times and flags plus the three identity shapes.
     */
    private static List<Candidate> freshCandidates() {
        final Random random = new Random(FRESH_SEED);
        final List<Candidate> candidates = new ArrayList<>(MODEL_CANDIDATES);
        for (int index = 0; index < MODEL_CANDIDATES; index++) {
            final int tag = index + 1;
            final UUID actor = taggedUuid(random, 4, tag);
            final DefinitionRef targetRef = new DefinitionRef(
                    new Ids.WorldId(taggedUuid(random, 1, tag)),
                    new Ids.DefinitionId(taggedUuid(random, 2, tag)));
            final ExactJson.FrozenJson targetMembers = taggedMembers("target", tag);
            final Ids.RequestId requestId = new Ids.RequestId(taggedUuid(random, 3, tag));
            final long expectedRevision = switch (random.nextInt(10)) {
                case 0 -> 0L;
                case 1 -> 1L;
                case 2 -> Long.MAX_VALUE - 1L;
                case 3 -> Long.MAX_VALUE;
                case 4 -> tag;
                default -> random.nextLong() & Long.MAX_VALUE;
            };
            final String payloadDigest = taggedDigest(random, 3L * tag + 1L);
            final OperationKind operationKind = random.nextBoolean()
                    ? OperationKind.SAVE_MAP
                    : OperationKind.MIGRATE_MAP;
            final Optional<String> migrationDigest = random.nextInt(3) == 0
                    ? Optional.empty()
                    : Optional.of(taggedDigest(random, 3L * tag + 3L));
            final ExactJson.FrozenJson inputsMembers = taggedMembers("inputs", tag);
            final boolean contentChanged = random.nextBoolean();
            final long committedRevision = freshCommitted(random, expectedRevision, contentChanged, tag);
            final long recordedAtEpochMillis = switch (random.nextInt(10)) {
                case 0 -> 0L;
                case 1 -> Long.MAX_VALUE;
                case 2 -> -1L;
                default -> random.nextLong() & Long.MAX_VALUE;
            };
            candidates.add(new Candidate(index, actor, targetRef, targetMembers, requestId,
                    expectedRevision, payloadDigest, operationKind, migrationDigest, inputsMembers,
                    freshReceiptActor(random, actor, tag), freshReceiptRequest(random, requestId, tag),
                    taggedDigest(random, 3L * tag + 2L), committedRevision,
                    freshReceiptPayload(random, payloadDigest, tag), contentChanged,
                    recordedAtEpochMillis, taggedMembers("receipt", tag)));
        }
        return List.copyOf(candidates);
    }

    /** The inputs verdict of the second stream: every component is legal, so this must be true. */
    private static boolean modelFreshInputsAccept(Candidate candidate) {
        final String migration = candidate.migrationDigest().orElse(null);
        return candidate.actorUuid() != null
                && candidate.targetRef() != null
                && modelMembers(candidate.targetMembers())
                && candidate.requestId() != null
                && candidate.operationKind() != null
                && candidate.migrationDigest() != null
                && modelDigest(candidate.payloadDigest())
                && (migration == null || modelDigest(migration))
                && modelMembers(candidate.inputsMembers())
                && candidate.expectedRevision() >= 0L;
    }

    /** The receipt verdict of the second stream, computed from the components before any call. */
    private static boolean modelFreshReceiptAccept(Candidate candidate) {
        if (!modelFreshInputsAccept(candidate)) {
            return false;
        }
        if (!modelDigest(candidate.fingerprint()) || !modelMembers(candidate.receiptMembers())) {
            return false;
        }
        if (candidate.committedRevision() < 1L || candidate.recordedAtEpochMillis() < 0L) {
            return false;
        }
        if (!candidate.receiptActor().equals(candidate.actorUuid())
                || !candidate.receiptRequest().equals(candidate.requestId())
                || !candidate.receiptPayloadDigest().equals(candidate.payloadDigest())) {
            return false;
        }
        return modelRevisionAccept(candidate);
    }

    private static boolean isEqualButDistinct(Object generated, Object kept) {
        return generated != kept && generated.equals(kept);
    }

    /**
     * Checks one identity column of an accepted receipt: the same reference must stay the same
     * reference, an equal but distinct value must stay an equal distinct instance, and a genuinely
     * different value cannot be reached here because the model refused that candidate.
     */
    private static void assertIdentityColumn(Object generated, Object kept, String label) {
        if (generated == kept) {
            assertSame(generated, kept, label + " must keep the same reference");
            return;
        }
        if (generated.equals(kept)) {
            assertEquals(generated, kept, label + " must stay equal");
            assertNotSame(generated, kept, label + " must stay its own distinct instance");
            return;
        }
        assertEquals(generated, kept, label + " was accepted although it is a different value");
    }

    @Test
    void thousandFullyLegalCandidatesEachReachARealReceiptConstruction() {
        final List<Candidate> candidates = freshCandidates();
        assertEquals(MODEL_CANDIDATES, candidates.size());

        // Phase one: every expectation of every candidate is decided before the first production
        // record is called, and the identities the card asks for are checked to be pairwise distinct.
        final List<Boolean> verdicts = new ArrayList<>(MODEL_CANDIDATES);
        final List<UUID> worldIds = new ArrayList<>(MODEL_CANDIDATES);
        final List<UUID> definitionIds = new ArrayList<>(MODEL_CANDIDATES);
        final List<UUID> requestIds = new ArrayList<>(MODEL_CANDIDATES);
        final List<String> payloads = new ArrayList<>(MODEL_CANDIDATES);
        final List<String> targetMembers = new ArrayList<>(MODEL_CANDIDATES);
        final List<String> inputsMembers = new ArrayList<>(MODEL_CANDIDATES);
        final List<String> receiptMembers = new ArrayList<>(MODEL_CANDIDATES);
        int equalButDistinctIdentities = 0;
        int mismatchedIdentities = 0;
        int zeroExpected = 0;
        int oneExpected = 0;
        int belowMaxExpected = 0;
        int maxExpected = 0;
        int zeroRecordedAt = 0;
        int maxRecordedAt = 0;
        int negativeRecordedAt = 0;
        int changedTrue = 0;
        int changedFalse = 0;
        for (Candidate candidate : candidates) {
            assertTrue(modelFreshInputsAccept(candidate),
                    "every candidate of the second stream must carry legal target and inputs components, at "
                            + candidate.index());
            verdicts.add(modelFreshReceiptAccept(candidate));
            assertNotEquals(candidate.targetMembers(), candidate.inputsMembers(),
                    "the target and inputs member containers must differ, at " + candidate.index());
            assertNotEquals(candidate.targetMembers(), candidate.receiptMembers(),
                    "the target and receipt member containers must differ, at " + candidate.index());
            assertNotEquals(candidate.inputsMembers(), candidate.receiptMembers(),
                    "the inputs and receipt member containers must differ, at " + candidate.index());
            worldIds.add(candidate.targetRef().worldId().value());
            definitionIds.add(candidate.targetRef().definitionId().value());
            requestIds.add(candidate.requestId().value());
            payloads.add(candidate.payloadDigest());
            targetMembers.add(candidate.targetMembers().canonical());
            inputsMembers.add(candidate.inputsMembers().canonical());
            receiptMembers.add(candidate.receiptMembers().canonical());
            if (candidate.expectedRevision() == 0L) {
                zeroExpected++;
            }
            if (candidate.expectedRevision() == 1L) {
                oneExpected++;
            }
            if (candidate.expectedRevision() == Long.MAX_VALUE - 1L) {
                belowMaxExpected++;
            }
            if (candidate.expectedRevision() == Long.MAX_VALUE) {
                maxExpected++;
            }
            if (candidate.recordedAtEpochMillis() == 0L) {
                zeroRecordedAt++;
            }
            if (candidate.recordedAtEpochMillis() == Long.MAX_VALUE) {
                maxRecordedAt++;
            }
            if (candidate.recordedAtEpochMillis() < 0L) {
                negativeRecordedAt++;
            }
            if (candidate.contentChanged()) {
                changedTrue++;
            } else {
                changedFalse++;
            }
            if (isEqualButDistinct(candidate.receiptActor(), candidate.actorUuid())
                    || isEqualButDistinct(candidate.receiptRequest(), candidate.requestId())
                    || isEqualButDistinct(candidate.receiptPayloadDigest(), candidate.payloadDigest())) {
                equalButDistinctIdentities++;
            }
            if (!candidate.receiptActor().equals(candidate.actorUuid())
                    || !candidate.receiptRequest().equals(candidate.requestId())
                    || !candidate.receiptPayloadDigest().equals(candidate.payloadDigest())) {
                mismatchedIdentities++;
            }
        }
        assertTrue(verdicts.contains(Boolean.TRUE), "the model must accept some receipts");
        assertTrue(verdicts.contains(Boolean.FALSE), "the model must refuse some receipts");
        assertEquals(MODEL_CANDIDATES, new HashSet<>(worldIds).size(), "the world ids must be distinct");
        assertEquals(MODEL_CANDIDATES, new HashSet<>(definitionIds).size(),
                "the definition ids must be distinct");
        assertEquals(MODEL_CANDIDATES, new HashSet<>(requestIds).size(), "the request ids must be distinct");
        assertEquals(MODEL_CANDIDATES, new HashSet<>(payloads).size(), "the payloads must be distinct");
        assertEquals(MODEL_CANDIDATES, new HashSet<>(targetMembers).size(),
                "the target member containers must be distinct");
        assertEquals(MODEL_CANDIDATES, new HashSet<>(inputsMembers).size(),
                "the inputs member containers must be distinct");
        assertEquals(MODEL_CANDIDATES, new HashSet<>(receiptMembers).size(),
                "the receipt member containers must be distinct");
        assertTrue(equalButDistinctIdentities > 0, "the sample must contain equal but distinct identities");
        assertTrue(mismatchedIdentities > 0, "the sample must contain genuinely mismatched identities");
        assertTrue(zeroExpected > 0 && oneExpected > 0 && belowMaxExpected > 0 && maxExpected > 0,
                "the expected revision must cover 0, 1, MAX-1 and MAX: " + zeroExpected + "/" + oneExpected
                        + "/" + belowMaxExpected + "/" + maxExpected);
        assertTrue(zeroRecordedAt > 0 && maxRecordedAt > 0 && negativeRecordedAt > 0,
                "the record time must cover 0, MAX and a negative value: " + zeroRecordedAt + "/"
                        + maxRecordedAt + "/" + negativeRecordedAt);
        assertTrue(changedTrue > 0 && changedFalse > 0,
                "both values of the changed flag must be present: " + changedTrue + "/" + changedFalse);

        // Phase two: every candidate really calls the production receipt, accepted or refused.
        int actualReceiptAttempts = 0;
        int actualReceiptRejections = 0;
        int actualReceiptSuccesses = 0;
        for (int index = 0; index < candidates.size(); index++) {
            final Candidate candidate = candidates.get(index);
            final FingerprintTarget target = new FingerprintTarget(candidate.targetRef(),
                    candidate.targetMembers());
            final FingerprintInputs inputs = new FingerprintInputs(candidate.actorUuid(), target,
                    candidate.requestId(), candidate.expectedRevision(), candidate.payloadDigest(),
                    candidate.operationKind(), candidate.migrationDigest(), candidate.inputsMembers());
            assertSame(candidate.targetRef(), inputs.target().target());
            assertSame(candidate.targetMembers(), inputs.target().members());
            assertSame(candidate.inputsMembers(), inputs.members());
            assertEquals(candidate.expectedRevision(), inputs.expectedRevision());
            assertSame(candidate.payloadDigest(), inputs.payloadDigest());
            actualReceiptAttempts++;
            if (verdicts.get(index)) {
                final CommitReceipt receipt = new CommitReceipt(candidate.receiptActor(),
                        candidate.receiptRequest(), candidate.fingerprint(), inputs,
                        candidate.committedRevision(), candidate.receiptPayloadDigest(),
                        candidate.contentChanged(), candidate.recordedAtEpochMillis(),
                        candidate.receiptMembers());
                actualReceiptSuccesses++;
                assertIdentityColumn(candidate.receiptActor(), receipt.actorUuid(), "actor");
                assertIdentityColumn(candidate.receiptRequest(), receipt.requestId(), "request");
                assertIdentityColumn(candidate.payloadDigest(), receipt.payloadDigest(), "payload digest");
                assertSame(inputs, receipt.fingerprintInputs());
                assertSame(candidate.fingerprint(), receipt.fingerprint());
                assertSame(candidate.receiptMembers(), receipt.members());
                assertEquals(candidate.committedRevision(), receipt.committedRevision());
                assertEquals(candidate.contentChanged(), receipt.contentChanged());
                assertEquals(candidate.recordedAtEpochMillis(), receipt.recordedAtEpochMillis());
            } else {
                assertThrows(IllegalArgumentException.class, () -> new CommitReceipt(
                        candidate.receiptActor(), candidate.receiptRequest(), candidate.fingerprint(),
                        inputs, candidate.committedRevision(), candidate.receiptPayloadDigest(),
                        candidate.contentChanged(), candidate.recordedAtEpochMillis(),
                        candidate.receiptMembers()),
                        "the real receipt must refuse candidate " + candidate.index());
                actualReceiptRejections++;
            }
        }
        assertEquals(MODEL_CANDIDATES, actualReceiptAttempts,
                "every candidate must reach a real receipt construction");
        assertEquals(MODEL_CANDIDATES, actualReceiptSuccesses + actualReceiptRejections);
        assertTrue(actualReceiptSuccesses > 0 && actualReceiptRejections > 0,
                "both verdicts must be real: successes=" + actualReceiptSuccesses
                        + " rejections=" + actualReceiptRejections);
        assertTrue(actualReceiptSuccesses >= 100 && actualReceiptRejections >= 100,
                "both verdicts must be substantial: successes=" + actualReceiptSuccesses
                        + " rejections=" + actualReceiptRejections);
        System.out.println("a4b_fresh_stream logical_candidates=" + MODEL_CANDIDATES
                + " actual_receipt_attempts=" + actualReceiptAttempts
                + " actual_receipt_rejections=" + actualReceiptRejections
                + " actual_receipt_successes=" + actualReceiptSuccesses
                + " equal_but_distinct_identities=" + equalButDistinctIdentities
                + " mismatched_identities=" + mismatchedIdentities
                + " expected_boundaries=" + zeroExpected + "/" + oneExpected + "/" + belowMaxExpected + "/"
                + maxExpected
                + " recorded_at_boundaries=" + zeroRecordedAt + "/" + maxRecordedAt + "/"
                + negativeRecordedAt
                + " changed=" + changedTrue + "/" + changedFalse);
    }

    @Test
    void targetMemberIdentityDecidesTargetInputsAndReceiptEquality() {
        final ExactJson.FrozenJson one = members("{\"x\":1}");
        final ExactJson.FrozenJson two = members("{\"x\":2}");
        final FingerprintTarget target = new FingerprintTarget(DEFINITION, one);
        assertEquals(DEFINITION, target.target());
        assertSame(DEFINITION, target.target());
        assertSame(one, target.members());

        final FingerprintTarget equalTarget = new FingerprintTarget(DEFINITION, one);
        assertEquals(target, equalTarget);
        assertEquals(target.hashCode(), equalTarget.hashCode());
        assertSame(target.target(), equalTarget.target());
        assertSame(target.members(), equalTarget.members());

        // only the members change; the definition reference is untouched and stays the same one
        final FingerprintTarget memberChanged = new FingerprintTarget(DEFINITION, two);
        assertSame(DEFINITION, memberChanged.target());
        assertSame(two, memberChanged.members());
        assertNotEquals(target, memberChanged);
        assertNotEquals(memberChanged, target);

        // the inequality travels through the inputs and through the receipt that carry the target
        final FingerprintInputs inputs = new FingerprintInputs(ACTOR, target, REQUEST, 5L, DIGEST_A,
                OperationKind.SAVE_MAP, Optional.empty(), emptyMembers());
        final FingerprintInputs changedInputs = new FingerprintInputs(ACTOR, memberChanged, REQUEST, 5L,
                DIGEST_A, OperationKind.SAVE_MAP, Optional.empty(), emptyMembers());
        assertSame(target, inputs.target());
        assertSame(memberChanged, changedInputs.target());
        assertSame(one, inputs.target().members());
        assertSame(two, changedInputs.target().members());
        assertNotEquals(inputs, changedInputs);
        assertNotEquals(changedInputs, inputs);

        final CommitReceipt receipt = new CommitReceipt(ACTOR, REQUEST, DIGEST_B, inputs, 6L, DIGEST_A,
                true, 1700000000000L, emptyMembers());
        final CommitReceipt changedReceipt = new CommitReceipt(ACTOR, REQUEST, DIGEST_B, changedInputs, 6L,
                DIGEST_A, true, 1700000000000L, emptyMembers());
        assertSame(inputs, receipt.fingerprintInputs());
        assertSame(changedInputs, changedReceipt.fingerprintInputs());
        assertNotEquals(receipt, changedReceipt);
        assertNotEquals(changedReceipt, receipt);

        // an equal but distinct target keeps both records equal, and every record keeps the instance
        // it was handed instead of rebuilding it
        final FingerprintInputs sameValueInputs = new FingerprintInputs(ACTOR, equalTarget, REQUEST, 5L,
                DIGEST_A, OperationKind.SAVE_MAP, Optional.empty(), emptyMembers());
        assertEquals(inputs, sameValueInputs);
        assertEquals(inputs.hashCode(), sameValueInputs.hashCode());
        assertNotSame(inputs.target(), sameValueInputs.target());
        final CommitReceipt sameValueReceipt = new CommitReceipt(ACTOR, REQUEST, DIGEST_B, sameValueInputs,
                6L, DIGEST_A, true, 1700000000000L, emptyMembers());
        assertEquals(receipt, sameValueReceipt);
        assertEquals(receipt.hashCode(), sameValueReceipt.hashCode());
        assertNotSame(receipt.fingerprintInputs(), sameValueReceipt.fingerprintInputs());

        // the definition half decides equality as well; different values are not required to have
        // different hashes, so no such assertion is made here
        final FingerprintTarget otherDefinition = new FingerprintTarget(OTHER_DEFINITION, one);
        assertNotEquals(target, otherDefinition);
        assertNotEquals(otherDefinition, target);
        assertNotEquals(receipt, new CommitReceipt(ACTOR, REQUEST, DIGEST_B,
                new FingerprintInputs(ACTOR, otherDefinition, REQUEST, 5L, DIGEST_A,
                        OperationKind.SAVE_MAP, Optional.empty(), emptyMembers()),
                6L, DIGEST_A, true, 1700000000000L, emptyMembers()));
    }
}
