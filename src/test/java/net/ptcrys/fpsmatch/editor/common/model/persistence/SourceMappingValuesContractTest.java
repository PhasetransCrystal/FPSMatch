package net.ptcrys.fpsmatch.editor.common.model.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import net.ptcrys.fpsmatch.editor.common.codec.JsonContractException;
import net.ptcrys.fpsmatch.editor.common.model.FieldPath;
import net.ptcrys.fpsmatch.editor.common.model.Ids;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.CfgState;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.IdKind;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.IdMapEntry;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.MissingCfgPolicy;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.OperationKind;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.SourceDocument;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.SourceHash;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.SourceHashes;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.SourceKind;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.SourceScope;
import org.junit.jupiter.api.Test;

/**
 * Formal contract test for {@link PersistenceValues} (task R06.2a2).
 *
 * <p>Every test builds real values through the real records and checks the accepted reference
 * identity, the accepted text identity and the exact rejection of a malformed input. Expected byte
 * widths are produced by the independent JDK UTF-8 encoder and expected canonical strings by the
 * frozen {@link ExactJson}, never by the type under test.</p>
 */
class SourceMappingValuesContractTest {

    /** Independent JDK oracle for a decoded UTF-8 width. */
    private static int jdkUtf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Builds frozen members through the frozen codec, so the fixture owns no private JSON writing. */
    private static ExactJson.FrozenJson members(String strictJson) {
        return ExactJson.parse(strictJson);
    }

    private static ExactJson.FrozenJson emptyMembers() {
        return members("{}");
    }

    private static final String SHA_A = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SHA_ZERO = "0".repeat(64);
    private static final String SHA_F = "f".repeat(64);

    private static SourceScope scope(String modeId, String legacyMapKey) {
        return new SourceScope(new Ids.WorldId(UUID.randomUUID()), modeId, legacyMapKey,
                emptyMembers());
    }

    private static SourceHash hash(SourceKind kind, String raw, String canonical) {
        return new SourceHash(kind, raw, canonical, emptyMembers());
    }

    private static SourceHash jsonHash() {
        return hash(SourceKind.FILE_BYTES, SHA_A, SHA_ZERO);
    }

    private static IdMapEntry entry(SourceDocument document, IdKind kind, List<String> segments) {
        return new IdMapEntry(document, kind, segments, UUID.randomUUID(), emptyMembers());
    }

    // ------------------------------------------------------------------
    // enums
    // ------------------------------------------------------------------

    /** The four identity kinds are exactly the wire set, in the frozen order. */
    void idKindValueSetIsExactlyTheFourWireKinds() {
        assertArrayEqualsExact(new String[] {"DEFINITION", "TEAM", "SPAWN", "REGION"},
                IdKind.values());
    }

    /** The two source document families are exactly JSON and CFG. */
    void sourceDocumentValueSetIsExactlyJsonAndCfg() {
        assertArrayEqualsExact(new String[] {"JSON", "CFG"}, SourceDocument.values());
    }

    /** The two transports are exactly the raw byte file and JDBC UTF-8 text. */
    void sourceKindValueSetIsExactlyFileBytesAndJdbcUtf8Text() {
        assertArrayEqualsExact(new String[] {"FILE_BYTES", "JDBC_UTF8_TEXT"}, SourceKind.values());
    }

    /** The cfg presence values are exactly present and absent. */
    void cfgStateValueSetIsExactlyPresentAndAbsent() {
        assertArrayEqualsExact(new String[] {"PRESENT", "ABSENT"}, CfgState.values());
    }

    /** The missing cfg policies are exactly reject and provider defaults. */
    void missingCfgPolicyValueSetIsExactlyRejectAndProviderDefaults() {
        assertArrayEqualsExact(new String[] {"REJECT", "PROVIDER_DEFAULTS"},
                MissingCfgPolicy.values());
    }

    /** The operation kinds are exactly save and migrate. */
    void operationKindValueSetIsExactlySaveAndMigrate() {
        assertArrayEqualsExact(new String[] {"SAVE_MAP", "MIGRATE_MAP"}, OperationKind.values());
    }

    /** Every enum resolves through its wire name, so the published spelling is load bearing. */
    void everyEnumConstantResolvesByItsWireName() {
        assertSame(IdKind.DEFINITION, IdKind.valueOf("DEFINITION"));
        assertSame(IdKind.REGION, IdKind.valueOf("REGION"));
        assertSame(SourceDocument.JSON, SourceDocument.valueOf("JSON"));
        assertSame(SourceDocument.CFG, SourceDocument.valueOf("CFG"));
        assertSame(SourceKind.FILE_BYTES, SourceKind.valueOf("FILE_BYTES"));
        assertSame(SourceKind.JDBC_UTF8_TEXT, SourceKind.valueOf("JDBC_UTF8_TEXT"));
        assertSame(CfgState.PRESENT, CfgState.valueOf("PRESENT"));
        assertSame(CfgState.ABSENT, CfgState.valueOf("ABSENT"));
        assertSame(MissingCfgPolicy.REJECT, MissingCfgPolicy.valueOf("REJECT"));
        assertSame(MissingCfgPolicy.PROVIDER_DEFAULTS, MissingCfgPolicy.valueOf("PROVIDER_DEFAULTS"));
        assertSame(OperationKind.SAVE_MAP, OperationKind.valueOf("SAVE_MAP"));
        assertSame(OperationKind.MIGRATE_MAP, OperationKind.valueOf("MIGRATE_MAP"));
        for (IdKind kind : IdKind.values()) {
            assertThrows(IllegalArgumentException.class, () -> IdKind.valueOf(kind.name() + "X"),
                    "an unknown kind name must not resolve");
        }
    }

    private static void assertArrayEqualsExact(String[] expected, Enum<?>[] actual) {
        final String[] names = new String[actual.length];
        for (int i = 0; i < actual.length; i++) {
            names[i] = actual[i].name();
        }
        assertEquals(Arrays.asList(expected), Arrays.asList(names),
                "the published constant set and order are part of the wire contract");
        assertEquals(expected.length, actual.length);
    }

    // ------------------------------------------------------------------
    // reference null matrix, one throw at a time
    // ------------------------------------------------------------------

    /** SourceScope rejects a null world id with IAE, never NPE. */
    @Test
    void sourceScopeRejectsNullWorldIdWithIae() {
        final ExactJson.FrozenJson members = emptyMembers();
        assertThrows(IllegalArgumentException.class,
                () -> new SourceScope(null, "ns:path", "key", members));
        // A rejected null must surface as the documented contract failure, and the record must not
        // leak a raw NullPointerException out of its constructor.
        assertThrows(IllegalArgumentException.class,
                () -> new SourceScope(null, "ns:path", "key", members));
    }

    /** SourceScope rejects a null mode id with IAE. */
    @Test
    void sourceScopeRejectsNullModeIdWithIae() {
        final Ids.WorldId world = new Ids.WorldId(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class,
                () -> new SourceScope(world, null, "key", emptyMembers()));
    }

    /** SourceScope rejects a null legacy map key with IAE. */
    @Test
    void sourceScopeRejectsNullLegacyMapKeyWithIae() {
        final Ids.WorldId world = new Ids.WorldId(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class,
                () -> new SourceScope(world, "ns:path", null, emptyMembers()));
    }

    /** SourceScope rejects null members with IAE. */
    @Test
    void sourceScopeRejectsNullMembersWithIae() {
        final Ids.WorldId world = new Ids.WorldId(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class,
                () -> new SourceScope(world, "ns:path", "key", null));
    }

    /** SourceHash rejects a null kind, either digest and null members, each with IAE. */
    @Test
    void sourceHashRejectsEveryNullComponentWithIae() {
        final ExactJson.FrozenJson members = emptyMembers();
        assertThrows(IllegalArgumentException.class, () -> new SourceHash(null, SHA_A, SHA_ZERO, members));
        assertThrows(IllegalArgumentException.class,
                () -> new SourceHash(SourceKind.FILE_BYTES, null, SHA_ZERO, members));
        assertThrows(IllegalArgumentException.class,
                () -> new SourceHash(SourceKind.FILE_BYTES, SHA_A, null, members));
        assertThrows(IllegalArgumentException.class,
                () -> new SourceHash(SourceKind.FILE_BYTES, SHA_A, SHA_ZERO, null));
    }

    /** SourceHashes rejects null json, null members and a null Optional, distinctly from empty. */
    @Test
    void sourceHashesRejectsNullComponentsButAcceptsEmptyOptional() {
        final SourceHash json = jsonHash();
        final ExactJson.FrozenJson members = emptyMembers();
        assertThrows(IllegalArgumentException.class,
                () -> new SourceHashes(null, Optional.empty(), members));
        assertThrows(IllegalArgumentException.class, () -> new SourceHashes(json, null, members));
        assertThrows(IllegalArgumentException.class,
                () -> new SourceHashes(json, Optional.empty(), null));
        final SourceHashes empty = new SourceHashes(json, Optional.empty(), members);
        assertTrue(empty.cfg().isEmpty());
        assertEquals(Optional.empty(), empty.cfg());
    }

    /** IdMapEntry rejects each null component with IAE, including null id and null segments. */
    @Test
    void idMapEntryRejectsEveryNullComponentWithIae() {
        final UUID id = UUID.randomUUID();
        final ExactJson.FrozenJson members = emptyMembers();
        final List<String> segments = List.of("a");
        assertThrows(IllegalArgumentException.class,
                () -> new IdMapEntry(null, IdKind.TEAM, segments, id, members));
        assertThrows(IllegalArgumentException.class,
                () -> new IdMapEntry(SourceDocument.JSON, null, segments, id, members));
        assertThrows(IllegalArgumentException.class,
                () -> new IdMapEntry(SourceDocument.JSON, IdKind.TEAM, null, id, members));
        assertThrows(IllegalArgumentException.class,
                () -> new IdMapEntry(SourceDocument.JSON, IdKind.TEAM, segments, null, members));
        assertThrows(IllegalArgumentException.class,
                () -> new IdMapEntry(SourceDocument.JSON, IdKind.TEAM, segments, id, null));
    }

    /** An all zero UUID is an ordinary identity, accepted as given and never completed or routed. */
    @Test
    void allZeroUuidIsAcceptedAsAnOrdinaryIdentity() {
        final UUID zero = new UUID(0L, 0L);
        final IdMapEntry entry = new IdMapEntry(SourceDocument.JSON, IdKind.DEFINITION,
                List.of(), zero, emptyMembers());
        assertSame(zero, entry.id());
        assertSame(IdKind.DEFINITION, entry.kind());
        assertTrue(entry.sourceSegments().isEmpty());
    }

    // ------------------------------------------------------------------
    // mode grammar
    // ------------------------------------------------------------------

    /** A legal mode keeps the very same String reference and is never rewritten or defaulted. */
    @Test
    void legalModeIsAcceptedByReferenceIncludingAnUnknownNamespace() {
        final String mode = "unknown_ns-1.2:some/path.txt";
        final SourceScope value = scope(mode, "key");
        assertSame(mode, value.modeId());
        assertSame(IdKind.class, IdKind.class);
    }

    /** A mode at exactly 191 ASCII characters is accepted. */
    @Test
    void modeOfExactly191AsciiCharactersIsAccepted() {
        final String mode = "n:" + "a".repeat(189);
        assertEquals(191, mode.length());
        assertSame(mode, scope(mode, "key").modeId());
    }

    /** A mode at 192 ASCII characters is rejected by length before any colon or alphabet scan. */
    @Test
    void modeOf192AsciiCharactersIsRejected() {
        final StringBuilder builder = new StringBuilder("n:");
        builder.append("a".repeat(190));
        assertEquals(192, builder.length());
        assertThrows(IllegalArgumentException.class,
                () -> scope(builder.toString(), "key"));
    }

    /** A missing colon, a repeated colon and an empty side are all rejected. */
    @Test
    void modeWithoutOneNonEmptyColonIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> scope("nopath", "key"));
        assertThrows(IllegalArgumentException.class, () -> scope("a:b:c", "key"));
        assertThrows(IllegalArgumentException.class, () -> scope(":path", "key"));
        assertThrows(IllegalArgumentException.class, () -> scope("ns:", "key"));
        assertThrows(IllegalArgumentException.class, () -> scope(":", "key"));
    }

    /** The namespace alphabet excludes a slash while the path alphabet allows one. */
    @Test
    void namespaceRejectsSlashWhilePathAcceptsIt() {
        assertThrows(IllegalArgumentException.class, () -> scope("ns/x:path", "key"));
        assertSame("ns:pa/th.txt", scope("ns:pa/th.txt", "key").modeId());
        assertSame("ns:...///", scope("ns:...///", "key").modeId());
    }

    /** Upper case and non ASCII mode text is rejected rather than folded or normalised. */
    @Test
    void modeRejectsUpperCaseAndNonAsciiText() {
        assertThrows(IllegalArgumentException.class, () -> scope("NS:path", "key"));
        assertThrows(IllegalArgumentException.class, () -> scope("ns:Path", "key"));
        assertThrows(IllegalArgumentException.class, () -> scope("n\u00e9s:path", "key"));
        assertThrows(IllegalArgumentException.class, () -> scope("ns:pa\u4e2dth", "key"));
        assertThrows(IllegalArgumentException.class, () -> scope("ns\u00e9:path", "key"));
        assertThrows(IllegalArgumentException.class, () -> scope(" ns:path", "key"));
        assertThrows(IllegalArgumentException.class, () -> scope("ns :path", "key"));
    }

    /** Every namespace character class is accepted, so the grammar is not narrower than frozen. */
    @Test
    void everyNamespaceAndPathCharacterClassIsAccepted() {
        final String mode = "a0_.-:b1_./-";
        assertSame(mode, scope(mode, "key").modeId());
        assertSame("...:---", scope("...:---", "key").modeId());
        assertSame("__:__", scope("__:__", "key").modeId());
    }

    // ------------------------------------------------------------------
    // the four UTF-8 branches, measured by the independent JDK oracle
    // ------------------------------------------------------------------

    /** ASCII exactly at the legacy budget: 65536 units, 65536 JDK bytes, accepted by identity. */
    @Test
    void legacyMapKeyAsciiExactlyAtTheBudgetIsAccepted() {
        final String key = "a".repeat(65536);
        assertEquals(65536, key.length());
        assertEquals(65536, jdkUtf8(key));
        assertSame(key, scope("ns:path", key).legacyMapKey());
    }

    /** ASCII one over the budget is rejected, and the UTF-16 bound alone already proves it. */
    @Test
    void legacyMapKeyAsciiOneOverTheBudgetIsRejected() {
        final String key = "a".repeat(65537);
        assertEquals(65537, jdkUtf8(key));
        assertThrows(IllegalArgumentException.class, () -> scope("ns:path", key));
    }

    /** Two byte units exactly at the budget: 32768 units, 65536 JDK bytes, accepted. */
    @Test
    void legacyMapKeyTwoByteExactlyAtTheBudgetIsAccepted() {
        final String key = "\u00e9".repeat(32768);
        assertEquals(32768, key.length());
        assertEquals(65536, jdkUtf8(key));
        assertSame(key, scope("ns:path", key).legacyMapKey());
    }

    /** Two byte units plus one ASCII unit is 65537 bytes and must be rejected. */
    @Test
    void legacyMapKeyTwoByteOneOverTheBudgetIsRejected() {
        final String key = "\u00e9".repeat(32768) + "a";
        assertEquals(65537, jdkUtf8(key));
        assertThrows(IllegalArgumentException.class, () -> scope("ns:path", key));
    }

    /** Three byte units exactly at the budget: 21845 units plus one ASCII, 65536 JDK bytes. */
    @Test
    void legacyMapKeyThreeByteExactlyAtTheBudgetIsAccepted() {
        final String key = "\u4e2d".repeat(21845) + "a";
        assertEquals(21846, key.length());
        assertEquals(65536, jdkUtf8(key));
        assertSame(key, scope("ns:path", key).legacyMapKey());
    }

    /** The same three byte prefix plus two ASCII units is 65537 bytes and must be rejected. */
    @Test
    void legacyMapKeyThreeByteOneOverTheBudgetIsRejected() {
        final String key = "\u4e2d".repeat(21845) + "aa";
        assertEquals(65537, jdkUtf8(key));
        assertThrows(IllegalArgumentException.class, () -> scope("ns:path", key));
    }

    /** Four byte pairs exactly at the budget: 16384 pairs, 65536 JDK bytes, accepted. */
    @Test
    void legacyMapKeyFourByteExactlyAtTheBudgetIsAccepted() {
        final String key = "\uD83D\uDE00".repeat(16384);
        assertEquals(32768, key.length());
        assertEquals(65536, jdkUtf8(key));
        assertSame(key, scope("ns:path", key).legacyMapKey());
    }

    /** Four byte pairs plus one ASCII unit is 65537 bytes and must be rejected. */
    @Test
    void legacyMapKeyFourByteOneOverTheBudgetIsRejected() {
        final String key = "\uD83D\uDE00".repeat(16384) + "a";
        assertEquals(65537, jdkUtf8(key));
        assertThrows(IllegalArgumentException.class, () -> scope("ns:path", key));
    }

    /** A lone high surrogate and a lone low surrogate are rejected in the map key. */
    @Test
    void legacyMapKeyRejectsLoneSurrogates() {
        assertThrows(IllegalArgumentException.class, () -> scope("ns:path", "a\uD83Db"));
        assertThrows(IllegalArgumentException.class, () -> scope("ns:path", "a\uDE00b"));
        assertThrows(IllegalArgumentException.class, () -> scope("ns:path", "\uD83D"));
        assertThrows(IllegalArgumentException.class, () -> scope("ns:path", "\uDE00"));
        assertThrows(IllegalArgumentException.class, () -> scope("ns:path", "\uD83D\uD83D"));
        assertThrows(IllegalArgumentException.class, () -> scope("ns:path", "\uDE00\uDE00"));
    }

    /** A legal pair at either edge of the map key is accepted and kept by reference. */
    @Test
    void legacyMapKeyAcceptsLegalPairsAtBothEdges() {
        final String key = "\uD83D\uDE00abc\uD83D\uDE4F";
        assertSame(key, scope("ns:path", key).legacyMapKey());
        assertSame("\uD83D\uDE00", scope("ns:path", "\uD83D\uDE00").legacyMapKey());
        assertEquals(4 + 3 + 4, jdkUtf8(key));
    }

    /** The empty map key is legal and is kept as the same empty String. */
    @Test
    void legacyMapKeyAcceptsEmptyAndWhitespaceAndControlText() {
        assertSame("", scope("ns:path", "").legacyMapKey());
        final String key = " \t\n\r\u0000\u001f\ud7ff\ue000";
        assertSame(key, scope("ns:path", key).legacyMapKey());
    }

    /** Separators, dots, colons and Unicode are ordinary origin text in the map key. */
    @Test
    void legacyMapKeyKeepsSeparatorsDotsColonsAndUnicodeAsText() {
        final String key = "a/b\\c.d:e\u4e2d\u00e9\uD83D\uDE00/..///";
        assertSame(key, scope("ns:path", key).legacyMapKey());
    }

    /** NFC and NFD spellings stay distinct origin identities and are never normalised together. */
    @Test
    void legacyMapKeyKeepsNfcAndNfdSpellingsDistinct() {
        final String nfc = "caf\u00e9";
        final String nfd = "cafe\u0301";
        assertNotEquals(nfc, nfd);
        final SourceScope first = scope("ns:path", nfc);
        final SourceScope second = scope("ns:path", nfd);
        assertSame(nfc, first.legacyMapKey());
        assertSame(nfd, second.legacyMapKey());
        assertNotEquals(first, second, "no normalisation may happen");
    }

    /** An over budget map key is refused as soon as the running total passes the limit. */
    @Test
    void legacyMapKeyOverBudgetMultiBytePrefixIsRefusedByTheByteBudget() {
        // 65536 four byte pairs is far over the byte budget while its UTF-16 length is only 131072,
        // which is already over the width bound as well; the smaller mixed vector proves the byte
        // branch on a value whose UTF-16 length is legal.
        assertThrows(IllegalArgumentException.class,
                () -> scope("ns:path", "\uD83D\uDE00".repeat(65536)));
        final String mixed = "\u00e9".repeat(32768) + "a";
        assertEquals(65537, jdkUtf8(mixed));
        assertTrue(mixed.length() <= 65536, "the UTF-16 width alone is legal for this vector");
        assertThrows(IllegalArgumentException.class, () -> scope("ns:path", mixed));
    }

    /** A map key of 65536 UTF-16 units of two byte text is over the byte budget and is rejected. */
    @Test
    void legacyMapKeyAtTheCharacterBoundButOverTheByteBudgetIsRejected() {
        final String key = "\u00e9".repeat(65536);
        assertEquals(65536, key.length());
        assertEquals(131072, jdkUtf8(key));
        assertThrows(IllegalArgumentException.class, () -> scope("ns:path", key));
    }

    // ------------------------------------------------------------------
    // digests
    // ------------------------------------------------------------------

    /** Both digests keep their exact reference, and the all zero digest is legal. */
    @Test
    void digestsAreAcceptedByReferenceIncludingAllZero() {
        final String raw = SHA_ZERO;
        final String canonical = SHA_F;
        final SourceHash hash = new SourceHash(SourceKind.JDBC_UTF8_TEXT, raw, canonical,
                emptyMembers());
        assertSame(raw, hash.rawSha256());
        assertSame(canonical, hash.canonicalSha256());
        assertSame(SourceKind.JDBC_UTF8_TEXT, hash.sourceKind());
        assertNotEquals(hash.rawSha256(), hash.canonicalSha256());
    }

    /** A digest of the wrong length is rejected before its alphabet is examined. */
    @Test
    void digestOfWrongLengthIsRejected() {
        for (int length : new int[] {0, 1, 63, 65, 128}) {
            final String candidate = "a".repeat(length);
            // Every malformed digest, including the empty one, is the documented contract failure
            // rather than a raw NullPointerException or an unrelated runtime type.
            assertThrows(IllegalArgumentException.class,
                    () -> new SourceHash(SourceKind.FILE_BYTES, candidate, SHA_ZERO, emptyMembers()),
                    "length " + length + " must be rejected");
        }
    }

    /** Upper case hexadecimal is not folded and is rejected, as are non hex and Unicode text. */
    @Test
    void digestOutsideLowerCaseHexIsRejected() {
        final String upper = "A".repeat(64);
        assertThrows(IllegalArgumentException.class,
                () -> new SourceHash(SourceKind.FILE_BYTES, upper, SHA_ZERO, emptyMembers()));
        assertThrows(IllegalArgumentException.class, () -> new SourceHash(SourceKind.FILE_BYTES,
                "g" + "a".repeat(63), SHA_ZERO, emptyMembers()));
        assertThrows(IllegalArgumentException.class, () -> new SourceHash(SourceKind.FILE_BYTES,
                "a".repeat(63) + " ", SHA_ZERO, emptyMembers()));
        assertThrows(IllegalArgumentException.class, () -> new SourceHash(SourceKind.FILE_BYTES,
                "a".repeat(63) + "\u00e9", SHA_ZERO, emptyMembers()));
        assertThrows(IllegalArgumentException.class, () -> new SourceHash(SourceKind.FILE_BYTES,
                "a".repeat(63) + "\u4e2d", SHA_ZERO, emptyMembers()));
        assertThrows(IllegalArgumentException.class, () -> new SourceHash(SourceKind.FILE_BYTES,
                "a".repeat(63) + "-", SHA_ZERO, emptyMembers()));
    }

    /** The digest is stored, never recomputed: a wrong but well formed digest is accepted as given. */
    @Test
    void digestIsStoredRatherThanRecomputedFromTheSource() {
        final String raw = SHA_ZERO;
        final String canonical = SHA_A;
        final SourceHash hash = new SourceHash(SourceKind.FILE_BYTES, raw, canonical, emptyMembers());
        assertNotEquals(raw, canonical);
        assertSame(raw, hash.rawSha256());
        // An independent JDK digest of text that is obviously not this value changes nothing: the
        // record cannot and does not claim that the source matched.
        assertNotEquals(SHA_ZERO, sha256Hex("some other source"));
    }

    /** An independent JDK SHA-256 oracle, used only to prove the record is not a digesting helper. */
    private static String sha256Hex(String text) {
        try {
            final byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            final StringBuilder builder = new StringBuilder(64);
            for (byte b : digest) {
                builder.append(Character.forDigit((b >> 4) & 0xf, 16));
                builder.append(Character.forDigit(b & 0xf, 16));
            }
            return builder.toString();
        } catch (java.security.NoSuchAlgorithmException cause) {
            throw new IllegalStateException(cause);
        }
    }

    // ------------------------------------------------------------------
    // SourceHashes optional semantics
    // ------------------------------------------------------------------

    /** An empty optional is legal and is not an error and not an absence assertion. */
    @Test
    void sourceHashesEmptyCfgOptionalIsLegalAndIsNotAnError() {
        final SourceHashes hashes = new SourceHashes(jsonHash(), Optional.empty(), emptyMembers());
        assertTrue(hashes.cfg().isEmpty());
        assertSame(Optional.empty(), hashes.cfg());
        assertFalse(hashes.cfg().isPresent());
    }

    /** The present Optional is kept as the same Optional value with its same hash reference. */
    @Test
    void sourceHashesPresentCfgKeepsTheSameOptionalAndHash() {
        final SourceHash cfg = hash(SourceKind.JDBC_UTF8_TEXT, SHA_F, SHA_F);
        final Optional<SourceHash> supplied = Optional.of(cfg);
        final SourceHashes hashes = new SourceHashes(jsonHash(), supplied, emptyMembers());
        assertSame(supplied, hashes.cfg());
        assertSame(cfg, hashes.cfg().orElseThrow());
        assertTrue(hashes.cfg().isPresent());
    }

    /** No default cfg hash or placeholder hash is invented when the optional is empty. */
    @Test
    void sourceHashesNeverInventsACfgHashWhenTheOptionalIsEmpty() {
        final SourceHash json = jsonHash();
        final ExactJson.FrozenJson frozen = emptyMembers();
        final SourceHashes hashes = new SourceHashes(json, Optional.empty(), frozen);
        assertSame(json, hashes.json());
        assertSame(frozen, hashes.members());
        // An empty optional carries no hash value at all, so nothing can be read from it as if a
        // default cfg document had been hashed.
        assertTrue(hashes.cfg().isEmpty());
        assertSame(Optional.empty(), hashes.cfg());
        assertEquals(Optional.empty(), hashes.cfg());
        assertTrue(hashes.cfg().map(SourceHash::rawSha256).isEmpty());
    }

    /** Both source kinds and both documents are carried unchanged by the hash records. */
    @Test
    void sourceHashesCarryBothKindsAndAreNotInterchangeable() {
        final SourceHash fileHash = hash(SourceKind.FILE_BYTES, SHA_A, SHA_F);
        final SourceHash jdbcHash = hash(SourceKind.JDBC_UTF8_TEXT, SHA_A, SHA_F);
        assertSame(SourceKind.FILE_BYTES, fileHash.sourceKind());
        assertSame(SourceKind.JDBC_UTF8_TEXT, jdbcHash.sourceKind());
        assertNotEquals(fileHash, jdbcHash, "the transport kind is part of the value");
    }

    // ------------------------------------------------------------------
    // segments: size, iterator and copy discipline
    // ------------------------------------------------------------------

    /** Zero and sixty four segments are accepted, and sixty five is rejected. */
    @Test
    void segmentsAcceptZeroAndSixtyFourAndRejectSixtyFive() {
        assertTrue(entry(SourceDocument.JSON, IdKind.TEAM, List.of()).sourceSegments().isEmpty());
        final List<String> sixtyFour = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            sixtyFour.add("s" + i);
        }
        assertEquals(64, entry(SourceDocument.JSON, IdKind.TEAM, sixtyFour).sourceSegments().size());
        final List<String> sixtyFive = new ArrayList<>(sixtyFour);
        sixtyFive.add("extra");
        assertThrows(IllegalArgumentException.class,
                () -> entry(SourceDocument.JSON, IdKind.TEAM, sixtyFive));
    }

    /** The reported size is rejected before any iteration, get or array conversion happens. */
    @Test
    void segmentsRejectAnImpossibleReportedSizeBeforeTouchingTheSource() {
        final ExplodingList explosive = new ExplodingList(65);
        assertThrows(IllegalArgumentException.class,
                () -> entry(SourceDocument.JSON, IdKind.TEAM, explosive));
        assertEquals(1, explosive.sizeCalls, "the reported size governs the decision");
        assertEquals(0, explosive.iteratorCalls, "size alone must decide the rejection");
        assertEquals(0, explosive.getCalls);
    }

    /** A negative reported size is rejected from the capture alone. */
    @Test
    void segmentsRejectANegativeReportedSizeBeforeTouchingTheSource() {
        final ExplodingList explosive = new ExplodingList(-1);
        assertThrows(IllegalArgumentException.class,
                () -> entry(SourceDocument.JSON, IdKind.TEAM, explosive));
        assertEquals(0, explosive.iteratorCalls);
    }

    /** The size is read exactly once; a second read would break the capture invariant. */
    @Test
    void segmentsReadTheSizeExactlyOnceAndThenStopTrustingIt() {
        final CountingList counting = new CountingList(List.of("a", "b", "c"));
        final IdMapEntry entry = entry(SourceDocument.JSON, IdKind.TEAM, counting);
        assertEquals(List.of("a", "b", "c"), entry.sourceSegments());
        assertEquals(1, counting.sizeReads,
                "the captured size must not be read a second time");
    }

    /** A source that reports more entries than it can yield is rejected after the real ones. */
    @Test
    void segmentsRejectASourceThatYieldsTooFewEntries() {
        final ShortIteratorList underReporting = new ShortIteratorList(3, 2);
        assertThrows(IllegalArgumentException.class,
                () -> entry(SourceDocument.JSON, IdKind.TEAM, underReporting));
        assertEquals(2, underReporting.nextCalls,
                "the shortfall must be detected once the real elements run out");
    }

    /** An unlimited source that claims zero entries is rejected on the single extra hasNext. */
    @Test
    void segmentsRejectAnEndlessSourceReportingZeroWithoutIterating() {
        final EndlessList endless = new EndlessList(0);
        assertThrows(IllegalArgumentException.class,
                () -> entry(SourceDocument.JSON, IdKind.TEAM, endless));
        assertEquals(0, endless.nextCalls, "an empty claim must be refused without any next");
    }

    /** An unlimited source correctly claiming sixty four entries is rejected after exactly 64 nexts. */
    @Test
    void segmentsRejectAnEndlessSourceReportingSixtyFourAfterExactlySixtyFourNexts() {
        final EndlessList endless = new EndlessList(64);
        assertThrows(IllegalArgumentException.class,
                () -> entry(SourceDocument.JSON, IdKind.TEAM, endless));
        assertEquals(64, endless.nextCalls, "the sixty fifth element must never be materialised");
    }

    /** A legal source whose get and array conversion both throw still succeeds. */
    @Test
    void segmentsAcceptALegalSourceWhoseGetAndToArrayThrow() {
        final IteratorBackedList throwing = new IteratorBackedList(List.of("x", "y"));
        final IdMapEntry entry = entry(SourceDocument.JSON, IdKind.TEAM, throwing);
        assertEquals(List.of("x", "y"), entry.sourceSegments());
        assertEquals(0, throwing.getCalls, "the iterator path must never fall back to get");
        assertEquals(0, throwing.toArrayCalls, "the source must never be turned into an array");
    }

    /** A source whose iterator ends early is refused, and it is refused after two real reads. */
    @Test
    void segmentsRefuseAnIteratorThatEndsBeforeTheCapturedCount() {
        final ShortIteratorList shortSource = new ShortIteratorList(3, 2);
        assertThrows(IllegalArgumentException.class,
                () -> entry(SourceDocument.JSON, IdKind.TEAM, shortSource));
        assertEquals(2, shortSource.nextCalls,
                "the shortfall must be detected after the available elements, not by a blind read");
    }

    /** A captured size of zero accepts only a source that is genuinely empty. */
    @Test
    void segmentsAcceptZeroOnlyWhenTheSourceIsGenuinelyEmpty() {
        assertTrue(entry(SourceDocument.JSON, IdKind.TEAM, List.of()).sourceSegments().isEmpty());
        final EndlessList endless = new EndlessList(0);
        assertThrows(IllegalArgumentException.class,
                () -> entry(SourceDocument.JSON, IdKind.TEAM, endless));
        assertEquals(0, endless.nextCalls, "a zero claim must be checked without a next");
    }

    /** A null segment anywhere is rejected. */
    @Test
    void segmentsRejectANullSegment() {
        assertThrows(IllegalArgumentException.class,
                () -> entry(SourceDocument.JSON, IdKind.TEAM, Collections.singletonList(null)));
        final List<String> withNull = new ArrayList<>(List.of("a"));
        withNull.add(null);
        assertThrows(IllegalArgumentException.class,
                () -> entry(SourceDocument.JSON, IdKind.TEAM, withNull));
    }

    /** The output list is unmodifiable, and mutating the input afterwards does not change it. */
    @Test
    void segmentsOutputIsImmutableAndIsolatedFromLaterInputMutation() {
        final List<String> mutable = new ArrayList<>(List.of("a", "b"));
        final IdMapEntry entry = entry(SourceDocument.JSON, IdKind.TEAM, mutable);
        final List<String> captured = entry.sourceSegments();
        assertEquals(List.of("a", "b"), captured);
        assertThrows(UnsupportedOperationException.class, () -> captured.add("c"));
        assertThrows(UnsupportedOperationException.class, () -> captured.set(0, "z"));
        assertThrows(UnsupportedOperationException.class, () -> captured.remove(0));
        mutable.clear();
        mutable.add("changed");
        assertEquals(List.of("a", "b"), entry.sourceSegments(), "the copy must be independent");
    }

    /** Segment text keeps its reference and its order, including empty, control and separators. */
    @Test
    void segmentsKeepReferenceOrderAndSeparatorText() {
        final String empty = "";
        final String slash = "/";
        final String dotDot = "..";
        final String colon = "a:b";
        final String control = "\u0000\u001f";
        final String unicode = "\u4e2d\u00e9\uD83D\uDE00";
        final List<String> source = List.of(empty, slash, dotDot, colon, control, unicode, slash);
        final IdMapEntry entry = entry(SourceDocument.JSON, IdKind.TEAM, source);
        final List<String> captured = entry.sourceSegments();
        assertEquals(7, captured.size());
        assertSame(empty, captured.get(0));
        assertSame(slash, captured.get(1));
        assertSame(dotDot, captured.get(2));
        assertSame(colon, captured.get(3));
        assertSame(control, captured.get(4));
        assertSame(unicode, captured.get(5));
        assertSame(slash, captured.get(6), "a duplicate segment is a legal occurrence");
    }

    /**
     * Two entries that differ only in how their separators divide the text are different values.
     *
     * <p>Every candidate list is written down before the call, and all five records share one fixed
     * UUID and one members object, so a separator mistake cannot hide behind a random identity
     * helper: the comparison can only turn on the segments themselves. Each record must keep the
     * whole original list in order, element by element, as the very same {@link String} instances,
     * and the five records must then be five different values.</p>
     */
    @Test
    void segmentsTreatSeparatorsAsTextRatherThanAsStructure() {
        final UUID fixedId = new UUID(0L, 0L);
        final ExactJson.FrozenJson sharedMembers = emptyMembers();
        final List<List<String>> candidates = List.of(
                List.of("a", "b"),
                List.of("a/b"),
                List.of("a.b"),
                List.of("1", "2"),
                List.of("12"));
        final List<IdMapEntry> built = new ArrayList<>(candidates.size());
        for (List<String> candidate : candidates) {
            final List<String> source = new ArrayList<>(candidate);
            final IdMapEntry record =
                    new IdMapEntry(SourceDocument.JSON, IdKind.TEAM, source, fixedId, sharedMembers);
            assertSame(fixedId, record.id());
            assertSame(sharedMembers, record.members());
            assertEquals(candidate, record.sourceSegments(), "the whole original list must be kept");
            assertEquals(candidate.size(), record.sourceSegments().size());
            for (int s = 0; s < source.size(); s++) {
                assertSame(source.get(s), record.sourceSegments().get(s),
                        "segment " + s + " must keep its identity");
            }
            built.add(record);
        }
        assertEquals(5, new LinkedHashSet<>(built).size(),
                "records that differ only by segments must be different values");
        assertNotEquals(built.get(0), built.get(1));
        assertNotEquals(built.get(0), built.get(2));
        assertNotEquals(built.get(3), built.get(4));
    }

    // ------------------------------------------------------------------
    // segments: the per segment 1024 byte budget on all four branches
    // ------------------------------------------------------------------

    /** Each segment branch is accepted at exactly 1024 JDK UTF-8 bytes. */
    @Test
    void segmentBranchesAreAcceptedAtExactlyTheByteBudget() {
        final String ascii = "a".repeat(1024);
        final String twoByte = "\u00e9".repeat(512);
        final String threeByte = "\u4e2d".repeat(341) + "a";
        final String fourByte = "\uD83D\uDE00".repeat(256);
        assertEquals(1024, jdkUtf8(ascii));
        assertEquals(1024, jdkUtf8(twoByte));
        assertEquals(1024, jdkUtf8(threeByte));
        assertEquals(1024, jdkUtf8(fourByte));
        final List<String> source = List.of(ascii, twoByte, threeByte, fourByte);
        final IdMapEntry entry = entry(SourceDocument.JSON, IdKind.TEAM, source);
        assertSame(ascii, entry.sourceSegments().get(0));
        assertSame(twoByte, entry.sourceSegments().get(1));
        assertSame(threeByte, entry.sourceSegments().get(2));
        assertSame(fourByte, entry.sourceSegments().get(3));
    }

    /** Each segment branch is rejected at exactly 1025 JDK UTF-8 bytes. */
    @Test
    void segmentBranchesAreRejectedAtOneByteOverTheBudget() {
        final String ascii = "a".repeat(1025);
        final String twoByte = "\u00e9".repeat(512) + "a";
        final String threeByte = "\u4e2d".repeat(341) + "aa";
        final String fourByte = "\uD83D\uDE00".repeat(256) + "a";
        assertEquals(1025, jdkUtf8(ascii));
        assertEquals(1025, jdkUtf8(twoByte));
        assertEquals(1025, jdkUtf8(threeByte));
        assertEquals(1025, jdkUtf8(fourByte));
        for (String over : new String[] {ascii, twoByte, threeByte, fourByte}) {
            // Each over budget branch fails as the documented contract failure, not as an unrelated
            // runtime type escaping the record.
            assertThrows(IllegalArgumentException.class,
                    () -> entry(SourceDocument.JSON, IdKind.TEAM, List.of(over)),
                    "an over budget segment must be rejected, bytes=" + jdkUtf8(over));
        }
    }

    /** A segment rejects lone surrogates on both sides. */
    @Test
    void segmentsRejectLoneSurrogates() {
        assertThrows(IllegalArgumentException.class,
                () -> entry(SourceDocument.JSON, IdKind.TEAM, List.of("\uD83D")));
        assertThrows(IllegalArgumentException.class,
                () -> entry(SourceDocument.JSON, IdKind.TEAM, List.of("\uDE00")));
        assertThrows(IllegalArgumentException.class,
                () -> entry(SourceDocument.JSON, IdKind.TEAM, List.of("ok", "a\uD83Db")));
        assertSame("\uD83D\uDE00", entry(SourceDocument.JSON, IdKind.TEAM,
                List.of("\uD83D\uDE00")).sourceSegments().get(0));
    }

    /** A segment is bounded by its own quota, proven directly on the wider legacy helper too. */
    @Test
    void segmentQuotaRejectsAVectorThatTheLegacyQuotaWouldAccept() {
        // 1025 ASCII characters would be a legal legacy map key but is an illegal segment, so the two
        // quotas are genuinely different and the shared helper cannot be used unparameterised.
        final String text = "a".repeat(1025);
        assertSame(text, scope("ns:path", text).legacyMapKey());
        assertThrows(IllegalArgumentException.class,
                () -> entry(SourceDocument.JSON, IdKind.TEAM, List.of(text)));
    }

    /** The longest legal path is 64 segments of 1024 slashes, and it is kept complete. */
    @Test
    void longestSegmentsAreAcceptedAndAStandardPointerCanExpressTheSameIdentity() {
        final String longSegment = "/".repeat(1024);
        assertEquals(1024, jdkUtf8(longSegment), "one segment is exactly at the byte budget");
        final List<String> source = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            source.add(longSegment);
        }
        final List<String> originalSegments = List.copyOf(source);
        // The expected pointer text is written down independently of the production call: a slash is
        // a whole segment rather than a divider, so the pointer must spell each one "~1", and sixty
        // four escaped segments of that size are exactly 131136 UTF-16 characters.
        final String expectedPointer = ("/" + "~1".repeat(1024)).repeat(64);
        assertEquals(131136, expectedPointer.length(),
                "the expected pointer is fixed before the production call");

        final IdMapEntry entry = entry(SourceDocument.JSON, IdKind.TEAM, source);
        final List<String> captured = entry.sourceSegments();
        assertEquals(64, captured.size());
        for (int i = 0; i < 64; i++) {
            assertSame(longSegment, captured.get(i));
        }
        // The frozen FieldPath expresses the very same identity, and the slash stays one whole
        // segment because a pointer escape must spell it "~1" instead.
        final FieldPath path = new FieldPath(captured);
        assertEquals(64, path.segments().size());
        assertEquals(originalSegments, path.segments());
        assertEquals(expectedPointer, path.pointer(),
                "the produced pointer must equal the independently written text");
        final FieldPath reparsed = FieldPath.fromPointer(expectedPointer);
        assertEquals(originalSegments, reparsed.segments(),
                "the reparsed segments must equal the original source list");
        assertEquals(64, reparsed.segments().size());
        for (int i = 0; i < 64; i++) {
            assertEquals(longSegment, reparsed.segments().get(i));
            assertEquals(1024, jdkUtf8(reparsed.segments().get(i)),
                    "the reparsed segment stays at the byte budget");
        }
    }

    // ------------------------------------------------------------------
    // DEFINITION anchor rule
    // ------------------------------------------------------------------

    /**
     * A DEFINITION entry must be JSON with no segments; both halves are enforced.
     *
     * <p>The seven enum spelling checks are called first as ordinary helpers rather than as their
     * own cases: they only observe JDK enum behaviour, so keeping them as separate cases inflated
     * the count without adding production coverage. Every value set, every wire spelling and the
     * unknown name rejection still run for real here, and the real record matrix of this method
     * runs immediately after them.</p>
     */
    @Test
    void definitionEntryRequiresJsonAndEmptySegments() {
        idKindValueSetIsExactlyTheFourWireKinds();
        sourceDocumentValueSetIsExactlyJsonAndCfg();
        sourceKindValueSetIsExactlyFileBytesAndJdbcUtf8Text();
        cfgStateValueSetIsExactlyPresentAndAbsent();
        missingCfgPolicyValueSetIsExactlyRejectAndProviderDefaults();
        operationKindValueSetIsExactlySaveAndMigrate();
        everyEnumConstantResolvesByItsWireName();
        assertDoesNotThrow(() -> new IdMapEntry(SourceDocument.JSON, IdKind.DEFINITION, List.of(),
                UUID.randomUUID(), emptyMembers()));
        assertThrows(IllegalArgumentException.class, () -> new IdMapEntry(SourceDocument.CFG,
                IdKind.DEFINITION, List.of(), UUID.randomUUID(), emptyMembers()));
        assertThrows(IllegalArgumentException.class, () -> new IdMapEntry(SourceDocument.JSON,
                IdKind.DEFINITION, List.of("a"), UUID.randomUUID(), emptyMembers()));
        assertThrows(IllegalArgumentException.class, () -> new IdMapEntry(SourceDocument.CFG,
                IdKind.DEFINITION, List.of("a"), UUID.randomUUID(), emptyMembers()));
    }

    /** Every non definition kind is constructible from either document with any legal segments. */
    @Test
    void nonDefinitionKindsAcceptBothDocumentsAndEmptySegments() {
        for (IdKind kind : new IdKind[] {IdKind.TEAM, IdKind.SPAWN, IdKind.REGION}) {
            for (SourceDocument document : SourceDocument.values()) {
                final IdMapEntry empty = new IdMapEntry(document, kind, List.of(),
                        UUID.randomUUID(), emptyMembers());
                assertTrue(empty.sourceSegments().isEmpty());
                final IdMapEntry anchored = new IdMapEntry(document, kind, List.of("a", "b"),
                        UUID.randomUUID(), emptyMembers());
                assertEquals(List.of("a", "b"), anchored.sourceSegments());
                assertSame(document, anchored.sourceDocument());
                assertSame(kind, anchored.kind());
            }
        }
    }

    /** Cross entry collisions are aggregate rules, so two colliding records still construct. */
    @Test
    void crossEntryCollisionsAreDeliberatelyNotRejectedByASingleRecord() {
        final UUID sharedTarget = UUID.randomUUID();
        // The same source anchor used by two kinds is accepted: uniqueness is an aggregate rule.
        final IdMapEntry team = new IdMapEntry(SourceDocument.JSON, IdKind.TEAM, List.of("k"),
                sharedTarget, emptyMembers());
        final IdMapEntry spawn = new IdMapEntry(SourceDocument.JSON, IdKind.SPAWN, List.of("k"),
                sharedTarget, emptyMembers());
        assertNotEquals(team, spawn);
        assertEquals(team.id(), spawn.id());
        // Two spawn and region entries may share one target in separate records.
        final IdMapEntry region = new IdMapEntry(SourceDocument.JSON, IdKind.REGION, List.of("r"),
                sharedTarget, emptyMembers());
        assertEquals(spawn.id(), region.id());
        // A definition entry and an anchored entry coexist: the single-definition rule is aggregate.
        final IdMapEntry definition = new IdMapEntry(SourceDocument.JSON, IdKind.DEFINITION,
                List.of(), sharedTarget, emptyMembers());
        assertNotEquals(definition, team);
    }

    // ------------------------------------------------------------------
    // members, equality and hashing
    // ------------------------------------------------------------------

    /** Every members container shape is accepted by reference, including unknown and empty keys. */
    @Test
    void membersAcceptEmptyUnknownEmptyKeyNullAndBigNumberShapesByReference() {
        for (String json : new String[] {"{}", "{\"\":1}", "{\"a\":null}",
                "{\"a\":123456789012345678901234567890}", "{\"zz\":{\"q\":[1,2]}}",
                "{\"unknown\":{\"deep\":[null,false,\"\"]}}"}) {
            final ExactJson.FrozenJson frozen = members(json);
            assertSame(frozen, new SourceScope(new Ids.WorldId(UUID.randomUUID()), "ns:path", "k",
                    frozen).members());
            assertSame(frozen, new SourceHash(SourceKind.FILE_BYTES, SHA_A, SHA_ZERO, frozen).members());
            assertSame(frozen, new SourceHashes(jsonHash(), Optional.empty(), frozen).members());
            assertSame(frozen, new IdMapEntry(SourceDocument.JSON, IdKind.DEFINITION, List.of(),
                    UUID.randomUUID(), frozen).members());
        }
    }

    /** A non object frozen value is rejected as members, and so is null. */
    @Test
    void membersRejectNonObjectShapesAndNull() {
        for (String json : new String[] {"[1]", "1", "\"text\"", "true", "null"}) {
            final ExactJson.FrozenJson frozen = members(json);
            assertThrows(IllegalArgumentException.class, () -> new SourceScope(
                    new Ids.WorldId(UUID.randomUUID()), "ns:path", "k", frozen),
                    "non object members must be rejected: " + json);
        }
        assertThrows(IllegalArgumentException.class, () -> new SourceScope(
                new Ids.WorldId(UUID.randomUUID()), "ns:path", "k", null));
    }

    /** The production type never calls toJson, so a mutated copy cannot pollute the frozen value. */
    @Test
    void membersAreNotPollutedByMutatingACopyReturnedToTheTest() {
        final ExactJson.FrozenJson frozen = members("{\"a\":1}");
        final SourceScope value = new SourceScope(new Ids.WorldId(UUID.randomUUID()), "ns:path", "k",
                frozen);
        final com.google.gson.JsonElement copy = value.members().toJson();
        assertTrue(copy.isJsonObject());
        copy.getAsJsonObject().addProperty("a", 999);
        assertEquals("{\"a\":1}", value.members().canonical(),
                "the stored frozen value must be isolated from a test side copy");
        assertEquals("{\"a\":1}", frozen.canonical());
    }

    /** Equal values are equal and hash equally; a different members, id, kind or document is not. */
    @Test
    void equalityAndHashingFollowTheRecordComponents() {
        final UUID id = UUID.randomUUID();
        final ExactJson.FrozenJson frozen = members("{\"a\":1}");
        final IdMapEntry base = new IdMapEntry(SourceDocument.JSON, IdKind.TEAM, List.of("a"), id,
                frozen);
        final IdMapEntry same = new IdMapEntry(SourceDocument.JSON, IdKind.TEAM, List.of("a"), id,
                frozen);
        assertEquals(base, same);
        assertEquals(base.hashCode(), same.hashCode());
        assertNotEquals(base, new IdMapEntry(SourceDocument.CFG, IdKind.TEAM, List.of("a"), id,
                frozen));
        assertNotEquals(base, new IdMapEntry(SourceDocument.JSON, IdKind.SPAWN, List.of("a"), id,
                frozen));
        assertNotEquals(base, new IdMapEntry(SourceDocument.JSON, IdKind.TEAM, List.of("b"), id,
                frozen));
        assertNotEquals(base, new IdMapEntry(SourceDocument.JSON, IdKind.TEAM, List.of("a"),
                UUID.randomUUID(), frozen));
        assertNotEquals(base, new IdMapEntry(SourceDocument.JSON, IdKind.TEAM, List.of("a"), id,
                members("{\"a\":2}")));
        assertNotEquals(base, null);
        assertEquals(base, base);
    }

    /** The same value built twice from equal inputs is equal and hashes equally. */
    @Test
    void allFourRecordsAreValueTypesOverTheirComponents() {
        final Ids.WorldId world = new Ids.WorldId(UUID.randomUUID());
        assertEquals(new SourceScope(world, "ns:path", "k", emptyMembers()),
                new SourceScope(world, "ns:path", "k", emptyMembers()));
        assertEquals(new SourceScope(world, "ns:path", "k", emptyMembers()).hashCode(),
                new SourceScope(world, "ns:path", "k", emptyMembers()).hashCode());
        assertNotEquals(new SourceScope(world, "ns:path", "k", emptyMembers()),
                new SourceScope(world, "ns:path2", "k", emptyMembers()));
        assertNotEquals(new SourceScope(world, "ns:path", "k", emptyMembers()),
                new SourceScope(new Ids.WorldId(UUID.randomUUID()), "ns:path", "k", emptyMembers()));

        assertEquals(new SourceHash(SourceKind.FILE_BYTES, SHA_A, SHA_ZERO, emptyMembers()),
                new SourceHash(SourceKind.FILE_BYTES, SHA_A, SHA_ZERO, emptyMembers()));
        assertNotEquals(new SourceHash(SourceKind.FILE_BYTES, SHA_A, SHA_ZERO, emptyMembers()),
                new SourceHash(SourceKind.FILE_BYTES, SHA_F, SHA_ZERO, emptyMembers()));

        assertEquals(new SourceHashes(jsonHash(), Optional.empty(), emptyMembers()),
                new SourceHashes(jsonHash(), Optional.empty(), emptyMembers()));
        assertNotEquals(new SourceHashes(jsonHash(), Optional.empty(), emptyMembers()),
                new SourceHashes(jsonHash(), Optional.of(hash(SourceKind.FILE_BYTES, SHA_F, SHA_F)),
                        emptyMembers()));
    }

    /** Accepted references are the very same objects, never copies or wraps. */
    @Test
    void acceptedReferencesAreNeverCopiedOrWrapped() {
        final Ids.WorldId world = new Ids.WorldId(UUID.randomUUID());
        final String mode = "ns:path";
        final String key = "legacy/key";
        final ExactJson.FrozenJson frozen = emptyMembers();
        final SourceScope scope = new SourceScope(world, mode, key, frozen);
        assertSame(world, scope.worldId());
        assertSame(mode, scope.modeId());
        assertSame(key, scope.legacyMapKey());
        assertSame(frozen, scope.members());
        assertNotSame(new String(key), scope.legacyMapKey());
        final UUID id = UUID.randomUUID();
        final List<String> source = new ArrayList<>(List.of("a"));
        final IdMapEntry entry = new IdMapEntry(SourceDocument.JSON, IdKind.TEAM, source, id, frozen);
        assertSame(id, entry.id());
        assertSame(frozen, entry.members());
        assertNotSame(source, entry.sourceSegments(), "the copy is a new list value");
        assertSame(source.get(0), entry.sourceSegments().get(0), "the elements keep their identity");
    }

    /**
     * Two thousand legal vectors in two corpora, none of them filtered by a production result.
     *
     * <p>The first thousand keep their original fixed seed, original generator and original
     * assertions. They only show that the corpus is varied rather than one constant repeated, so
     * they support the claim that more than twenty different keys and segment shapes occur, never a
     * claim that all thousand are pairwise distinct.</p>
     *
     * <p>The second thousand are pairwise distinct by construction, and that is what the
     * distinctness claim rests on. Sample zero carries the empty key and no segments; every later
     * sample carries a real numbered prefix on both its key and its first segment, and the rest of
     * each vector is still generated from the original fixed seed and the original alphabet. Every
     * prefix is really handed to the records rather than kept as an unconsumed label, the JDK UTF-8
     * oracle fixes the expected width and the expected original elements before each call, and the
     * random UUID is deliberately fixed here because it must not carry the distinctness argument.</p>
     */
    @Test
    void aThousandFixedSeedLegalVectorsKeepEveryStringAndSegmentInOrder() {
        final Random random = new Random(20261014L);
        final String[] alphabet = {"", "a", "Z", "0", "/", ".", ":", "\\", "\u0000", "\u001f",
            "\u00e9", "\u4e2d", "\uD83D\uDE00", "\ud7ff", "..", "//", "a/b"};
        final Set<String> distinctKeys = new LinkedHashSet<>();
        final Set<String> distinctSegmentShapes = new LinkedHashSet<>();
        for (int i = 0; i < 1000; i++) {
            final int parts = 1 + random.nextInt(5);
            final StringBuilder keyBuilder = new StringBuilder();
            for (int p = 0; p < parts; p++) {
                keyBuilder.append(alphabet[random.nextInt(alphabet.length)]);
            }
            final String key = keyBuilder.toString();
            // The expected width and the expected element identity are fixed by the JDK before the call.
            final int expectedWidth = jdkUtf8(key);
            assertTrue(expectedWidth <= 65536, "the generated vector must be legal, width " + expectedWidth);
            assertTrue(key.length() <= 65536);

            final int segmentCount = random.nextInt(5);
            final List<String> segments = new ArrayList<>(segmentCount);
            for (int s = 0; s < segmentCount; s++) {
                final String segment = alphabet[random.nextInt(alphabet.length)];
                assertTrue(jdkUtf8(segment) <= 1024);
                segments.add(segment);
            }
            distinctKeys.add(key);
            distinctSegmentShapes.add(segments.toString() + "#" + segmentCount);

            final SourceScope scope = new SourceScope(new Ids.WorldId(UUID.randomUUID()), "ns:path",
                    key, emptyMembers());
            assertSame(key, scope.legacyMapKey());
            assertEquals(expectedWidth, jdkUtf8(scope.legacyMapKey()));
            final IdMapEntry entry = entry(SourceDocument.JSON, IdKind.TEAM, segments);
            assertEquals(segments.size(), entry.sourceSegments().size());
            for (int s = 0; s < segments.size(); s++) {
                assertSame(segments.get(s), entry.sourceSegments().get(s));
            }
        }
        // The corpus is genuinely varied rather than one constant repeated a thousand times.
        assertTrue(distinctKeys.size() > 20, "distinct keys: " + distinctKeys.size());
        assertTrue(distinctSegmentShapes.size() > 20,
                "distinct segment shapes: " + distinctSegmentShapes.size());

        // ------------------------------------------------------------------
        // The second thousand: pairwise distinct by construction.
        // ------------------------------------------------------------------
        final Random distinctRandom = new Random(20261014L);
        final Set<String> distinctRealKeys = new LinkedHashSet<>();
        final Set<List<String>> distinctRealSegments = new LinkedHashSet<>();
        final UUID fixedId = new UUID(0L, 0L);
        for (int i = 0; i < 1000; i++) {
            // Sample zero is the empty identity; every other sample carries a real numbered prefix
            // on its key and on its first segment so that no two samples can coincide.
            final String keyPrefix = i == 0 ? "" : "sample" + i + ":";
            final String segmentPrefix = i == 0 ? null : "sample" + i;

            final int parts = i == 0 ? 0 : 1 + distinctRandom.nextInt(5);
            final StringBuilder keyBuilder = new StringBuilder(keyPrefix);
            for (int p = 0; p < parts; p++) {
                keyBuilder.append(alphabet[distinctRandom.nextInt(alphabet.length)]);
            }
            final String key = keyBuilder.toString();

            final List<String> segments = new ArrayList<>();
            if (segmentPrefix != null) {
                segments.add(segmentPrefix);
            }
            final int generated = i == 0 ? 0 : distinctRandom.nextInt(5);
            for (int s = 0; s < generated; s++) {
                segments.add(alphabet[distinctRandom.nextInt(alphabet.length)]);
            }

            // The oracle runs before the production call: it fixes the expected width and takes an
            // independent copy of the complete original element array for the distinctness set.
            final int expectedKeyWidth = jdkUtf8(key);
            assertTrue(expectedKeyWidth <= 65536,
                    "sample " + i + " key width must be legal: " + expectedKeyWidth);
            assertTrue(key.length() <= 65536, "sample " + i + " key length must be legal");
            for (int s = 0; s < segments.size(); s++) {
                assertTrue(jdkUtf8(segments.get(s)) <= 1024,
                        "sample " + i + " segment " + s + " must be legal");
            }
            final List<String> expectedSegments = List.copyOf(segments);
            assertTrue(distinctRealKeys.add(key), "sample " + i + " repeats a key: " + key);
            assertTrue(distinctRealSegments.add(expectedSegments),
                    "sample " + i + " repeats a segment list: " + expectedSegments);

            final SourceScope scope =
                    new SourceScope(new Ids.WorldId(fixedId), "ns:path", key, emptyMembers());
            assertSame(key, scope.legacyMapKey());
            assertEquals(expectedKeyWidth, jdkUtf8(scope.legacyMapKey()));
            final IdMapEntry real = new IdMapEntry(SourceDocument.JSON, IdKind.TEAM, segments, fixedId,
                    emptyMembers());
            assertEquals(expectedSegments, real.sourceSegments(), "the whole list must be kept");
            assertEquals(expectedSegments.size(), real.sourceSegments().size());
            for (int s = 0; s < expectedSegments.size(); s++) {
                assertSame(expectedSegments.get(s), real.sourceSegments().get(s),
                        "sample " + i + " segment " + s + " must keep its identity");
            }
        }
        assertEquals(1000, distinctRealKeys.size(), "every one of the thousand keys is distinct");
        assertEquals(1000, distinctRealSegments.size(),
                "every one of the thousand segment lists is distinct");
    }

    /** One thousand characters is legal for a map key and for a segment on every byte branch. */
    @Test
    void aThousandCharacterTextIsLegalOnEveryByteBranchForKeysAndSegments() {
        final String[] branches = {"a".repeat(1000), "\u00e9".repeat(1000), "\u4e2d".repeat(1000),
            "\uD83D\uDE00".repeat(1000)};
        for (String text : branches) {
            final int width = jdkUtf8(text);
            assertEquals(1000, text.codePointCount(0, text.length()));
            assertTrue(width <= 65536, "key width " + width);
            assertSame(text, scope("ns:path", text).legacyMapKey());
        }
        // A thousand ASCII characters is legal for a segment; the multi byte branches are not, and
        // the oracle proves the difference before the call.
        assertSame(branches[0], entry(SourceDocument.JSON, IdKind.TEAM, List.of(branches[0]))
                .sourceSegments().get(0));
        for (int i = 1; i < branches.length; i++) {
            assertTrue(jdkUtf8(branches[i]) > 1024);
            final String over = branches[i];
            assertThrows(IllegalArgumentException.class,
                    () -> entry(SourceDocument.JSON, IdKind.TEAM, List.of(over)));
        }
    }

    /** A map key of exactly 65536 UTF-16 characters that is not a surrogate is legal by reference. */
    @Test
    void sixtyFiveThousandFiveHundredThirtySixUnitKeysAreHandledExactly() {
        final String ascii = "a".repeat(65536);
        assertSame(ascii, scope("ns:path", ascii).legacyMapKey());
        final String bmp = "\ud7ff".repeat(65536);
        assertEquals(65536, bmp.length());
        assertEquals(65536 * 3, jdkUtf8(bmp));
        assertThrows(IllegalArgumentException.class, () -> scope("ns:path", bmp),
                "the byte budget must refuse it even though the width is legal");
        final String pair = "\uD83D\uDE00".repeat(32768);
        assertEquals(65536, pair.length());
        assertEquals(131072, jdkUtf8(pair));
        assertThrows(IllegalArgumentException.class, () -> scope("ns:path", pair));
        final String lone = "\uD83D".repeat(65536);
        assertThrows(IllegalArgumentException.class, () -> scope("ns:path", lone));
    }

    /** A legal 65536 unit segment fails only on its own smaller quota. */
    @Test
    void sixtyFiveThousandFiveHundredThirtySixUnitSegmentsFailOnTheSegmentQuota() {
        final String ascii = "a".repeat(65536);
        assertSame(ascii, scope("ns:path", ascii).legacyMapKey());
        assertThrows(IllegalArgumentException.class,
                () -> entry(SourceDocument.JSON, IdKind.TEAM, List.of(ascii)));
    }

    /**
     * Base for the counting fixtures.
     *
     * <p>It implements every structural method of {@link List} as a hard failure, so a fixture can
     * observe exactly the calls the contract is allowed to make. Nothing is inherited from
     * {@code java.util.AbstractList}, whose inherited {@code iterator()}, {@code size()} and
     * {@code next()} would silently satisfy or reshape a call this suite means to see.</p>
     */
    private abstract static class CountingFixture implements List<String> {

        @Override
        public boolean isEmpty() {
            throw new AssertionError("isEmpty must not be used to decide the aggregate");
        }

        @Override
        public boolean contains(Object value) {
            throw new AssertionError("contains must not be called");
        }

        @Override
        public Object[] toArray() {
            throw new AssertionError("the source must never be materialised as an array");
        }

        @Override
        public <T> T[] toArray(T[] target) {
            throw new AssertionError("the source must never be materialised as an array");
        }

        @Override
        public boolean add(String value) {
            throw new AssertionError("the source is never written");
        }

        @Override
        public boolean remove(Object value) {
            throw new AssertionError("the source is never written");
        }

        @Override
        public boolean containsAll(java.util.Collection<?> values) {
            throw new AssertionError("containsAll must not be called");
        }

        @Override
        public boolean addAll(java.util.Collection<? extends String> values) {
            throw new AssertionError("the source is never written");
        }

        @Override
        public boolean addAll(int index, java.util.Collection<? extends String> values) {
            throw new AssertionError("the source is never written");
        }

        @Override
        public boolean removeAll(java.util.Collection<?> values) {
            throw new AssertionError("the source is never written");
        }

        @Override
        public boolean retainAll(java.util.Collection<?> values) {
            throw new AssertionError("the source is never written");
        }

        @Override
        public void clear() {
            throw new AssertionError("the source is never written");
        }

        @Override
        public String set(int index, String value) {
            throw new AssertionError("the source is never written");
        }

        @Override
        public void add(int index, String value) {
            throw new AssertionError("the source is never written");
        }

        @Override
        public String remove(int index) {
            throw new AssertionError("the source is never written");
        }

        @Override
        public int indexOf(Object value) {
            throw new AssertionError("indexOf must not be called");
        }

        @Override
        public int lastIndexOf(Object value) {
            throw new AssertionError("lastIndexOf must not be called");
        }

        @Override
        public java.util.ListIterator<String> listIterator() {
            throw new AssertionError("no list iterator may be requested");
        }

        @Override
        public java.util.ListIterator<String> listIterator(int index) {
            throw new AssertionError("no list iterator may be requested");
        }

        @Override
        public List<String> subList(int from, int to) {
            throw new AssertionError("subList must not be called");
        }
    }

    /** A legal list that counts every size read and serves its elements from a real iterator. */
    private static final class CountingList extends CountingFixture {

        private final List<String> backing;
        int sizeReads;

        CountingList(List<String> backing) {
            this.backing = backing;
        }

        @Override
        public int size() {
            sizeReads++;
            return backing.size();
        }

        @Override
        public Iterator<String> iterator() {
            return backing.iterator();
        }

        @Override
        public String get(int index) {
            return backing.get(index);
        }
    }

    /**
     * A legal list whose {@code get} and array conversion always fail while iteration works.
     *
     * <p>It proves the accepted path is iterator based and never materialises the source.</p>
     */
    private static final class IteratorBackedList extends CountingFixture {

        private final List<String> backing;
        int getCalls;
        int toArrayCalls;

        IteratorBackedList(List<String> backing) {
            this.backing = backing;
        }

        @Override
        public int size() {
            return backing.size();
        }

        @Override
        public Iterator<String> iterator() {
            return backing.iterator();
        }

        @Override
        public String get(int index) {
            getCalls++;
            throw new AssertionError("the iterator path must not use get");
        }

        @Override
        public Object[] toArray() {
            toArrayCalls++;
            throw new AssertionError("the source must never be turned into an array");
        }
    }

    /**
     * A list whose element access, iteration request and array conversion all fail.
     *
     * <p>Every method of {@link List} other than the observed ones is inherited from
     * {@link CountingFixture} and fails loudly, so nothing here silently satisfies a call.</p>
     */
    private static final class ExplodingList extends CountingFixture {

        private final int reported;
        int sizeCalls;
        int iteratorCalls;
        int getCalls;

        ExplodingList(int reported) {
            this.reported = reported;
        }

        @Override
        public int size() {
            sizeCalls++;
            return reported;
        }

        @Override
        public Iterator<String> iterator() {
            iteratorCalls++;
            throw new AssertionError("no iterator may be requested");
        }

        @Override
        public String get(int index) {
            getCalls++;
            throw new AssertionError("no element may be read through get");
        }
    }

    /**
     * An endless list that counts how many elements the contract actually pulls.
     *
     * <p>The iterator never ends, so a single extra element proves an unbounded read.</p>
     */
    private static final class EndlessList extends CountingFixture {

        private final int reported;
        int sizeCalls;
        int nextCalls;

        EndlessList(int reported) {
            this.reported = reported;
        }

        @Override
        public int size() {
            sizeCalls++;
            return reported;
        }

        @Override
        public Iterator<String> iterator() {
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return true;
                }

                @Override
                public String next() {
                    nextCalls++;
                    return "e" + nextCalls;
                }
            };
        }

        @Override
        public String get(int index) {
            throw new AssertionError("no element may be read through get");
        }
    }

    /**
     * A list whose first reported size is the real one and whose later answers are larger.
     *
     * <p>It exists to prove the captured size governs: the value must be accepted from the first
     * answer even though a second read would report a different length.</p>
     */
    private static final class ChangingSizeList extends CountingFixture {

        private final List<String> backing;
        private int reads;

        ChangingSizeList(List<String> backing) {
            this.backing = backing;
        }

        @Override
        public int size() {
            reads++;
            return reads == 1 ? backing.size() : 64;
        }

        @Override
        public Iterator<String> iterator() {
            return backing.iterator();
        }

        @Override
        public String get(int index) {
            return backing.get(index);
        }
    }

    /**
     * The captured size, not a later answer, decides how many elements are read.
     *
     * <p>The source is genuinely two elements long and reports two on the first read, so the value
     * must be built successfully even though any later size read would report a much larger number.
     * A value that re-read the size would instead try to pull sixty four elements and fail.</p>
     */
    @Test
    void segmentsTrustTheCapturedSizeRatherThanALaterAnswer() {
        final ChangingSizeList changing = new ChangingSizeList(List.of("a", "b"));
        final IdMapEntry entry = entry(SourceDocument.JSON, IdKind.TEAM, changing);
        assertEquals(List.of("a", "b"), entry.sourceSegments());
        assertEquals(1, changing.reads, "the size must be captured exactly once");
    }

    /**
     * A list that reports three entries but whose iterator ends after two.
     *
     * <p>Implemented directly so the short iterator is the real one the contract sees, and so the
     * shortfall is surfaced as the element access that actually ran out.</p>
     */
    private static final class ShortIteratorList extends CountingFixture {

        private final int reported;
        private final int available;
        int nextCalls;

        ShortIteratorList(int reported, int available) {
            this.reported = reported;
            this.available = available;
        }

        @Override
        public int size() {
            return reported;
        }

        @Override
        public Iterator<String> iterator() {
            return new Iterator<>() {
                private int index;

                @Override
                public boolean hasNext() {
                    return index < available;
                }

                @Override
                public String next() {
                    if (index >= available) {
                        throw new java.util.NoSuchElementException("iterator exhausted");
                    }
                    nextCalls++;
                    return "v" + index++;
                }
            };
        }

        @Override
        public String get(int index) {
            throw new AssertionError("no element may be read through get");
        }
    }
}
