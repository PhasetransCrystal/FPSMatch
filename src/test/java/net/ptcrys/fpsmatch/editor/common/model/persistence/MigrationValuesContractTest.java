package net.ptcrys.fpsmatch.editor.common.model.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import net.ptcrys.fpsmatch.editor.common.model.Ids;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.CfgState;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.IdKind;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.IdMapEntry;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.MigrationRecord;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.MissingCfgPolicy;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.SourceDocument;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.SourceHash;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.SourceHashes;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.SourceKind;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues.SourceScope;
import org.junit.jupiter.api.Test;

/**
 * Formal contract test for {@link PersistenceValues.MigrationRecord} (task R06.2a3).
 *
 * <p>Every case builds real values through the real records, checks the accepted reference identity
 * and the exact rejection of a malformed candidate, and never asserts on an invented message or on a
 * code the contract does not publish. The aggregate expectations of the thousand sample case are
 * computed by a local model from the generated entries <em>before</em> the production record is
 * called, so no expectation is read back from the type under test.</p>
 */
class MigrationValuesContractTest {

    private static ExactJson.FrozenJson members(String strictJson) {
        return ExactJson.parse(strictJson);
    }

    private static ExactJson.FrozenJson emptyMembers() {
        return members("{}");
    }

    private static final String SHA_A = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SHA_ZERO = "0".repeat(64);
    private static final String SHA_F = "f".repeat(64);

    private static SourceScope scope() {
        return scope("ns:path", "legacy-key");
    }

    private static SourceScope scope(String modeId, String legacyMapKey) {
        return new SourceScope(new Ids.WorldId(new UUID(5L, 5L)), modeId, legacyMapKey, emptyMembers());
    }

    private static SourceHash hash(SourceKind kind, String raw, String canonical) {
        return new SourceHash(kind, raw, canonical, emptyMembers());
    }

    private static SourceHash jsonHash() {
        return hash(SourceKind.FILE_BYTES, SHA_A, SHA_ZERO);
    }

    private static SourceHash cfgHash() {
        return hash(SourceKind.JDBC_UTF8_TEXT, SHA_F, SHA_A);
    }

    private static SourceHashes hashesWithCfg() {
        return new SourceHashes(jsonHash(), Optional.of(cfgHash()), emptyMembers());
    }

    private static SourceHashes hashesWithoutCfg() {
        return new SourceHashes(jsonHash(), Optional.empty(), emptyMembers());
    }

    private static IdMapEntry entry(SourceDocument document, IdKind kind, List<String> segments,
            UUID id, ExactJson.FrozenJson members) {
        return new IdMapEntry(document, kind, segments, id, members);
    }

    private static IdMapEntry rootDefinition(UUID id, ExactJson.FrozenJson members) {
        return entry(SourceDocument.JSON, IdKind.DEFINITION, List.of(), id, members);
    }

    private static IdMapEntry definition(UUID id) {
        return rootDefinition(id, emptyMembers());
    }

    private static IdMapEntry jsonEntry(IdKind kind, List<String> segments, UUID id,
            ExactJson.FrozenJson members) {
        return entry(SourceDocument.JSON, kind, segments, id, members);
    }

    private static IdMapEntry cfgEntry(IdKind kind, List<String> segments, UUID id,
            ExactJson.FrozenJson members) {
        return entry(SourceDocument.CFG, kind, segments, id, members);
    }

    /** A present cfg candidate with the two given containers spelled out. */
    private static MigrationRecord present(SourceScope sourceScope, SourceHashes sourceHashes,
            Optional<ExactJson.FrozenJson> legacyCfg, List<IdMapEntry> idMap,
            ExactJson.FrozenJson legacyJson, ExactJson.FrozenJson members) {
        return new MigrationRecord(sourceScope, sourceHashes, CfgState.PRESENT, Optional.empty(),
                idMap, legacyJson, legacyCfg, members);
    }

    private static MigrationRecord present(List<IdMapEntry> idMap) {
        return present(scope(), hashesWithCfg(), Optional.of(members("{\"cfg\":1}")), idMap,
                members("[1,2]"), emptyMembers());
    }

    /** An absent cfg candidate with the given policy. */
    private static MigrationRecord absent(MissingCfgPolicy policy, List<IdMapEntry> idMap) {
        return new MigrationRecord(scope(), hashesWithoutCfg(), CfgState.ABSENT, Optional.of(policy),
                idMap, members("{}"), Optional.empty(), emptyMembers());
    }

    private static MigrationRecord absent(List<IdMapEntry> idMap) {
        return absent(MissingCfgPolicy.REJECT, idMap);
    }

    /** A legal mapping of one root definition plus one team, used wherever only validity matters. */
    private static List<IdMapEntry> oneDefinitionOneTeam() {
        return List.of(definition(new UUID(0L, 7L)),
                jsonEntry(IdKind.TEAM, List.of("team"), new UUID(1L, 7L), emptyMembers()));
    }

    // ------------------------------------------------------------------
    // required references and the cfg combination
    // ------------------------------------------------------------------

    @Test
    void everyRequiredReferenceRejectsNull() {
        final List<IdMapEntry> valid = oneDefinitionOneTeam();
        final SourceScope sourceScope = scope();
        final SourceHashes withCfg = hashesWithCfg();
        final ExactJson.FrozenJson legacyJson = members("{}");
        final ExactJson.FrozenJson legacyCfg = members("{\"cfg\":1}");
        final ExactJson.FrozenJson frozenMembers = emptyMembers();

        assertThrows(IllegalArgumentException.class, () -> new MigrationRecord(null, withCfg,
                CfgState.PRESENT, Optional.empty(), valid, legacyJson,
                Optional.of(legacyCfg), frozenMembers));
        assertThrows(IllegalArgumentException.class, () -> new MigrationRecord(sourceScope, null,
                CfgState.PRESENT, Optional.empty(), valid, legacyJson,
                Optional.of(legacyCfg), frozenMembers));
        assertThrows(IllegalArgumentException.class, () -> new MigrationRecord(sourceScope, withCfg,
                null, Optional.empty(), valid, legacyJson, Optional.of(legacyCfg), frozenMembers));
        // a Java null optional is not an empty optional, and vice versa
        assertThrows(IllegalArgumentException.class, () -> new MigrationRecord(sourceScope, withCfg,
                CfgState.PRESENT, null, valid, legacyJson, Optional.of(legacyCfg), frozenMembers));
        assertThrows(IllegalArgumentException.class, () -> new MigrationRecord(sourceScope, withCfg,
                CfgState.PRESENT, Optional.empty(), null, legacyJson, Optional.of(legacyCfg),
                frozenMembers));
        assertThrows(IllegalArgumentException.class, () -> new MigrationRecord(sourceScope, withCfg,
                CfgState.PRESENT, Optional.empty(), valid, null, Optional.of(legacyCfg),
                frozenMembers));
        assertThrows(IllegalArgumentException.class, () -> new MigrationRecord(sourceScope, withCfg,
                CfgState.PRESENT, Optional.empty(), valid, legacyJson, null, frozenMembers));
        assertThrows(IllegalArgumentException.class, () -> new MigrationRecord(sourceScope, withCfg,
                CfgState.PRESENT, Optional.empty(), valid, legacyJson, Optional.of(legacyCfg), null));
        // an array or a scalar shaped member container is not an object
        assertThrows(IllegalArgumentException.class, () -> new MigrationRecord(sourceScope, withCfg,
                CfgState.PRESENT, Optional.empty(), valid, legacyJson, Optional.of(legacyCfg),
                members("[]")));
        assertThrows(IllegalArgumentException.class, () -> new MigrationRecord(sourceScope, withCfg,
                CfgState.PRESENT, Optional.empty(), valid, legacyJson, Optional.of(legacyCfg),
                members("7")));
    }

    @Test
    void onlyTheTwoCfgCombinationsAreLegal() {
        final List<IdMapEntry> valid = oneDefinitionOneTeam();
        final SourceScope sourceScope = scope();
        final ExactJson.FrozenJson frozenMembers = emptyMembers();
        final ExactJson.FrozenJson legacyJson = members("{}");
        final ExactJson.FrozenJson legacyCfg = members("{\"cfg\":1}");
        final SourceHashes withCfg = hashesWithCfg();
        final SourceHashes withoutCfg = hashesWithoutCfg();

        // Every presence shape of the three optionals crossed with both cfg states: sixteen real
        // shapes. A present policy is really constructed with both enum values, so twenty-four
        // candidates are built. The rest of every candidate is legal: the id map holds exactly one
        // JSON root definition plus one team, and every entry shares one frozen members value.
        int shapes = 0;
        int constructions = 0;
        int accepted = 0;
        int rejected = 0;
        for (final CfgState cfgState : CfgState.values()) {
            for (final boolean cfgHashPresent : new boolean[] {true, false}) {
                for (final boolean legacyCfgPresent : new boolean[] {true, false}) {
                    for (final boolean policyPresent : new boolean[] {true, false}) {
                        // The two legal shapes come from the card and are computed from the shape
                        // alone, never from any production output: PRESENT means the cfg hash and
                        // the legacy cfg are present and the policy is absent, ABSENT means neither
                        // hash nor legacy cfg is present and exactly one policy is.
                        final boolean legal = cfgState == CfgState.PRESENT
                                ? cfgHashPresent && legacyCfgPresent && !policyPresent
                                : !cfgHashPresent && !legacyCfgPresent && policyPresent;
                        final SourceHashes sourceHashes = cfgHashPresent ? withCfg : withoutCfg;
                        final Optional<ExactJson.FrozenJson> legacyCfgOptional = legacyCfgPresent
                                ? Optional.of(legacyCfg)
                                : Optional.empty();
                        final List<Optional<MissingCfgPolicy>> policies = policyPresent
                                ? List.of(Optional.of(MissingCfgPolicy.REJECT),
                                        Optional.of(MissingCfgPolicy.PROVIDER_DEFAULTS))
                                : List.of(Optional.<MissingCfgPolicy>empty());
                        shapes++;
                        for (final Optional<MissingCfgPolicy> policy : policies) {
                            constructions++;
                            if (legal) {
                                accepted++;
                                final MigrationRecord record = new MigrationRecord(sourceScope,
                                        sourceHashes, cfgState, policy, valid, legacyJson,
                                        legacyCfgOptional, frozenMembers);
                                // a legal shape keeps every reference it was handed
                                assertSame(sourceScope, record.sourceScope());
                                assertSame(sourceHashes, record.sourceHashes());
                                assertEquals(cfgState, record.cfgState());
                                assertSame(policy, record.missingCfgPolicy());
                                assertSame(legacyJson, record.legacyJson());
                                assertSame(legacyCfgOptional, record.legacyCfg());
                                assertSame(frozenMembers, record.members());
                                assertEquals(valid.size(), record.idMap().size());
                                for (int i = 0; i < valid.size(); i++) {
                                    assertSame(valid.get(i), record.idMap().get(i));
                                }
                            } else {
                                rejected++;
                                final RuntimeException thrown = assertThrows(
                                        IllegalArgumentException.class,
                                        () -> new MigrationRecord(sourceScope, sourceHashes, cfgState,
                                                policy, valid, legacyJson, legacyCfgOptional,
                                                frozenMembers));
                                assertFalse(thrown instanceof NoSuchElementException);
                            }
                        }
                    }
                }
            }
        }
        assertEquals(16, shapes);
        assertEquals(24, constructions);
        assertEquals(3, accepted);
        assertEquals(21, rejected);
    }

    @Test
    void absentCfgStateAcceptsBothPoliciesAndKeepsThemTyped() {
        final List<IdMapEntry> valid = oneDefinitionOneTeam();
        final MigrationRecord reject = absent(MissingCfgPolicy.REJECT, valid);
        final MigrationRecord providerDefaults = absent(MissingCfgPolicy.PROVIDER_DEFAULTS, valid);

        // REJECT is a legal uncommitted candidate: this value type does not refuse the migration
        assertEquals(MissingCfgPolicy.REJECT, reject.missingCfgPolicy().orElseThrow());
        assertEquals(MissingCfgPolicy.PROVIDER_DEFAULTS,
                providerDefaults.missingCfgPolicy().orElseThrow());
        assertNotEquals(reject.missingCfgPolicy(), providerDefaults.missingCfgPolicy());
        assertTrue(reject.missingCfgPolicy().isPresent());
        assertTrue(reject.sourceHashes().cfg().isEmpty());
        assertTrue(reject.legacyCfg().isEmpty());
        assertEquals(CfgState.ABSENT, reject.cfgState());
    }

    @Test
    void presentLegacyCfgKeepsJsonNullApartFromAnEmptyOptional() {
        final List<IdMapEntry> valid = oneDefinitionOneTeam();
        final ExactJson.FrozenJson jsonNull = members("null");

        final MigrationRecord nullCfg = present(scope(), hashesWithCfg(), Optional.of(jsonNull),
                valid, members("{}"), emptyMembers());
        final MigrationRecord emptyCfg = absent(MissingCfgPolicy.REJECT, valid);

        assertTrue(nullCfg.legacyCfg().isPresent());
        assertSame(jsonNull, nullCfg.legacyCfg().orElseThrow());
        assertNotEquals(nullCfg.legacyCfg(), emptyCfg.legacyCfg());
        assertFalse(emptyCfg.legacyCfg().isPresent());
    }

    @Test
    void acceptedReferencesAreKeptAndOnlyTheEntryListIsCopied() {
        final SourceScope sourceScope = scope();
        final SourceHashes sourceHashes = hashesWithCfg();
        final ExactJson.FrozenJson legacyJson = members("[1,2]");
        final ExactJson.FrozenJson legacyCfg = members("{\"cfg\":1}");
        final ExactJson.FrozenJson frozenMembers = members("{\"a\":1}");
        final ExactJson.FrozenJson entryMembers = members("{\"m\":1}");
        final UUID definitionId = new UUID(4L, 4L);
        final List<IdMapEntry> input = new ArrayList<>();
        final IdMapEntry definition = rootDefinition(definitionId, entryMembers);
        final IdMapEntry team = jsonEntry(IdKind.TEAM, List.of("team"), new UUID(4L, 5L),
                entryMembers);
        input.add(definition);
        input.add(team);

        final MigrationRecord record = present(sourceScope, sourceHashes, Optional.of(legacyCfg),
                input, legacyJson, frozenMembers);

        assertSame(sourceScope, record.sourceScope());
        assertSame(sourceHashes, record.sourceHashes());
        assertSame(sourceHashes.cfg().orElseThrow(), record.sourceHashes().cfg().orElseThrow());
        assertSame(legacyJson, record.legacyJson());
        assertSame(legacyCfg, record.legacyCfg().orElseThrow());
        assertSame(frozenMembers, record.members());
        assertNotSame(input, record.idMap());
        assertEquals(input, record.idMap());
        assertSame(definition, record.idMap().get(0));
        assertSame(team, record.idMap().get(1));
        assertSame(entryMembers, record.idMap().get(0).members());
        assertSame(entryMembers, record.idMap().get(1).members());
        assertSame(definition.id(), record.idMap().get(0).id());
    }

    // ------------------------------------------------------------------
    // the definition rule and the per kind quotas
    // ------------------------------------------------------------------

    @Test
    void definitionMustAppearExactlyOnce() {
        final UUID definitionId = new UUID(0L, 9L);

        assertThrows(IllegalArgumentException.class, () -> absent(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> present(List.of(jsonEntry(IdKind.TEAM, List.of("t"), new UUID(1L, 1L),
                        emptyMembers()))));
        assertThrows(IllegalArgumentException.class,
                () -> absent(List.of(rootDefinition(definitionId, emptyMembers()),
                        rootDefinition(new UUID(0L, 10L), emptyMembers()))));
        // the definition may appear after another kind: only the count is constrained, and the input
        // order is the published order
        final MigrationRecord reversed = absent(List.of(
                jsonEntry(IdKind.TEAM, List.of("t"), new UUID(1L, 1L), emptyMembers()),
                definition(definitionId)));
        assertEquals(2, reversed.idMap().size());
        assertEquals(IdKind.TEAM, reversed.idMap().get(0).kind());
        assertEquals(IdKind.DEFINITION, reversed.idMap().get(1).kind());

        final MigrationRecord accepted = absent(List.of(definition(definitionId)));
        assertEquals(1, accepted.idMap().size());
        assertEquals(IdKind.DEFINITION, accepted.idMap().get(0).kind());
    }

    @Test
    void teamQuotaIs64And65IsRejected() {
        final List<IdMapEntry> accepted = new ArrayList<>();
        accepted.add(definition(new UUID(0L, 1L)));
        for (int i = 0; i < 64; i++) {
            accepted.add(jsonEntry(IdKind.TEAM, List.of("team" + i), new UUID(2L, i),
                    emptyMembers()));
        }
        final MigrationRecord record = absent(accepted);
        assertEquals(65, record.idMap().size());
        assertEquals(64, countKind(record.idMap(), IdKind.TEAM));

        final List<IdMapEntry> tooMany = new ArrayList<>(accepted);
        tooMany.add(jsonEntry(IdKind.TEAM, List.of("team64"), new UUID(2L, 64), emptyMembers()));
        final List<IdMapEntry> frozenTooMany = List.copyOf(tooMany);
        assertThrows(IllegalArgumentException.class, () -> absent(frozenTooMany));
    }

    @Test
    void spawnQuotaIs16384And16385IsRejected() {
        final ExactJson.FrozenJson shared = emptyMembers();
        final List<IdMapEntry> accepted = new ArrayList<>(16385);
        accepted.add(definition(new UUID(0L, 2L)));
        for (int i = 0; i < 16384; i++) {
            accepted.add(jsonEntry(IdKind.SPAWN, List.of("spawn" + i), new UUID(3L, i), shared));
        }
        final MigrationRecord record = absent(accepted);
        assertEquals(16385, record.idMap().size());
        assertEquals(16384, countKind(record.idMap(), IdKind.SPAWN));

        final List<IdMapEntry> tooMany = new ArrayList<>(accepted);
        tooMany.add(jsonEntry(IdKind.SPAWN, List.of("spawn16384"), new UUID(3L, 16384), shared));
        final List<IdMapEntry> frozenTooMany = List.copyOf(tooMany);
        assertThrows(IllegalArgumentException.class, () -> absent(frozenTooMany));
    }

    @Test
    void regionQuotaIs1024And1025IsRejected() {
        final ExactJson.FrozenJson shared = emptyMembers();
        final List<IdMapEntry> accepted = new ArrayList<>(1025);
        accepted.add(definition(new UUID(0L, 3L)));
        for (int i = 0; i < 1024; i++) {
            accepted.add(jsonEntry(IdKind.REGION, List.of("region" + i), new UUID(4L, i), shared));
        }
        final MigrationRecord record = absent(accepted);
        assertEquals(1025, record.idMap().size());
        assertEquals(1024, countKind(record.idMap(), IdKind.REGION));

        final List<IdMapEntry> tooMany = new ArrayList<>(accepted);
        tooMany.add(jsonEntry(IdKind.REGION, List.of("region1024"), new UUID(4L, 1024), shared));
        final List<IdMapEntry> frozenTooMany = List.copyOf(tooMany);
        assertThrows(IllegalArgumentException.class, () -> absent(frozenTooMany));
    }

    @Test
    void largestMappingOf17473IsAcceptedAndKeepsTheInputOrder() {
        final ExactJson.FrozenJson shared = emptyMembers();
        final List<IdMapEntry> input = new ArrayList<>(17473);
        input.add(rootDefinition(new UUID(0L, 4L), shared));
        for (int i = 0; i < 64; i++) {
            input.add(jsonEntry(IdKind.TEAM, List.of("team" + i), new UUID(5L, i), shared));
        }
        for (int i = 0; i < 16384; i++) {
            input.add(jsonEntry(IdKind.SPAWN, List.of("spawn" + i), new UUID(6L, i), shared));
        }
        for (int i = 0; i < 1024; i++) {
            input.add(jsonEntry(IdKind.REGION, List.of("region" + i), new UUID(7L, i), shared));
        }
        assertEquals(17473, input.size());

        final MigrationRecord record = absent(input);

        assertEquals(17473, record.idMap().size());
        assertEquals(1, countKind(record.idMap(), IdKind.DEFINITION));
        assertEquals(64, countKind(record.idMap(), IdKind.TEAM));
        assertEquals(16384, countKind(record.idMap(), IdKind.SPAWN));
        assertEquals(1024, countKind(record.idMap(), IdKind.REGION));
        for (int i = 0; i < input.size(); i++) {
            assertSame(input.get(i), record.idMap().get(i));
            assertSame(shared, record.idMap().get(i).members());
        }
        assertSame(input.get(0), record.idMap().get(0));
        assertSame(input.get(17472), record.idMap().get(17472));
    }

    private static int countKind(List<IdMapEntry> entries, IdKind kind) {
        int count = 0;
        for (IdMapEntry entry : entries) {
            if (entry.kind() == kind) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // the bounded copy: hostile lists
    // ------------------------------------------------------------------

    @Test
    void declaredSizeAbove17473IsRejectedBeforeAnyIteration() {
        final DeclaredSizeList list = new DeclaredSizeList(17474);
        assertThrows(IllegalArgumentException.class, () -> absent(list));
        assertEquals(1, list.sizeReads);
        assertEquals(0, list.iteratorCalls);
        assertEquals(0, list.getCalls);
        assertEquals(0, list.toArrayCalls);

        final DeclaredSizeList negative = new DeclaredSizeList(-1);
        assertThrows(IllegalArgumentException.class, () -> absent(negative));
        assertEquals(1, negative.sizeReads);
        assertEquals(0, negative.iteratorCalls);
    }

    @Test
    void declaredSizeZeroIsRejectedWithoutASingleNext() {
        final EndlessDeclaredZeroList list = new EndlessDeclaredZeroList();
        assertThrows(IllegalArgumentException.class, () -> absent(list));
        assertEquals(1, list.sizeReads);
        // the captured zero is trusted: the iterator is opened, the extra hasNext probe rejects the
        // candidate, and not one element is ever taken from the endless source
        assertEquals(1, list.iteratorCalls);
        assertEquals(0, list.nextCalls);
    }

    @Test
    void theSizeIsReadExactlyOnce() {
        final SecondSizeThrowsList list = new SecondSizeThrowsList(oneDefinitionOneTeam());
        final MigrationRecord record = assertDoesNotThrow(() -> absent(list));
        assertEquals(1, list.sizeReads);
        assertEquals(2, record.idMap().size());
        assertSame(list.backing.get(0), record.idMap().get(0));
        assertSame(list.backing.get(1), record.idMap().get(1));
    }

    @Test
    void shortAndLongIteratorsAndNullEntriesAreRejected() {
        final List<IdMapEntry> valid = oneDefinitionOneTeam();

        assertThrows(IllegalArgumentException.class,
                () -> absent(new IteratorShapedList(valid.size(), valid.subList(0, 1))));
        assertThrows(IllegalArgumentException.class, () -> absent(
                new IteratorShapedList(2, Arrays.asList(valid.get(0), valid.get(1), valid.get(1)))));
        assertThrows(IllegalArgumentException.class,
                () -> absent(new IteratorShapedList(1, Collections.singletonList(null))));
        // the declared number is the exact number that is accepted
        final MigrationRecord accepted = absent(new IteratorShapedList(2, valid));
        assertEquals(2, accepted.idMap().size());
    }

    @Test
    void getAndArrayConversionAreNeverUsed() {
        final List<IdMapEntry> valid = oneDefinitionOneTeam();
        final IteratorOnlyList list = new IteratorOnlyList(valid);
        final MigrationRecord record = absent(list);
        assertEquals(2, record.idMap().size());
        assertSame(valid.get(0), record.idMap().get(0));
        assertEquals(0, list.getCalls);
        assertEquals(0, list.toArrayCalls);
        assertEquals(1, list.iteratorCalls);
        assertEquals(1, list.sizeReads);
    }

    /** A legal list that counts every access and serves its elements from a real iterator. */
    private static final class IteratorOnlyList extends EntryListFixture {

        private final List<IdMapEntry> backing;
        int sizeReads;
        int iteratorCalls;
        int getCalls;
        int toArrayCalls;

        IteratorOnlyList(List<IdMapEntry> backing) {
            this.backing = backing;
        }

        @Override
        public int size() {
            sizeReads++;
            return backing.size();
        }

        @Override
        public Iterator<IdMapEntry> iterator() {
            iteratorCalls++;
            return backing.iterator();
        }

        @Override
        public IdMapEntry get(int index) {
            getCalls++;
            throw new AssertionError("get must not be used to read the mapping");
        }

        @Override
        public Object[] toArray() {
            toArrayCalls++;
            throw new AssertionError("the mapping must never be materialised as an array");
        }
    }

    /** A list whose declared size is served without ever handing out an element. */
    private static final class DeclaredSizeList extends EntryListFixture {

        private final int declared;
        int sizeReads;
        int iteratorCalls;
        int getCalls;
        int toArrayCalls;

        DeclaredSizeList(int declared) {
            this.declared = declared;
        }

        @Override
        public int size() {
            sizeReads++;
            return declared;
        }

        @Override
        public Iterator<IdMapEntry> iterator() {
            iteratorCalls++;
            throw new AssertionError("an oversized or negative declaration must be rejected first");
        }

        @Override
        public IdMapEntry get(int index) {
            getCalls++;
            throw new AssertionError("get must not be used to read the mapping");
        }

        @Override
        public Object[] toArray() {
            toArrayCalls++;
            throw new AssertionError("the mapping must never be materialised as an array");
        }
    }

    /** A list that claims zero entries while its iterator would never end. */
    private static final class EndlessDeclaredZeroList extends EntryListFixture {

        int sizeReads;
        int iteratorCalls;
        int nextCalls;

        @Override
        public int size() {
            sizeReads++;
            return 0;
        }

        @Override
        public Iterator<IdMapEntry> iterator() {
            iteratorCalls++;
            return new Iterator<>() {

                @Override
                public boolean hasNext() {
                    return true;
                }

                @Override
                public IdMapEntry next() {
                    nextCalls++;
                    throw new AssertionError("no element may ever be taken from this list");
                }
            };
        }
    }

    /** A legal list that fails loudly if its size is read a second time. */
    private static final class SecondSizeThrowsList extends EntryListFixture {

        final List<IdMapEntry> backing;
        int sizeReads;

        SecondSizeThrowsList(List<IdMapEntry> backing) {
            this.backing = backing;
        }

        @Override
        public int size() {
            sizeReads++;
            if (sizeReads > 1) {
                throw new AssertionError("the reported size must be read exactly once");
            }
            return backing.size();
        }

        @Override
        public Iterator<IdMapEntry> iterator() {
            return backing.iterator();
        }
    }

    /** A list whose declared size is deliberately different from what its iterator yields. */
    private static final class IteratorShapedList extends EntryListFixture {

        private final int declared;
        private final List<IdMapEntry> yielded;

        IteratorShapedList(int declared, List<IdMapEntry> yielded) {
            this.declared = declared;
            this.yielded = yielded;
        }

        @Override
        public int size() {
            return declared;
        }

        @Override
        public Iterator<IdMapEntry> iterator() {
            return yielded.iterator();
        }
    }

    /**
     * The base of every hostile mapping list.
     *
     * <p>It implements {@link List} directly and never extends {@code java.util.AbstractList}, whose
     * inherited {@code iterator()}, {@code size()} and {@code next()} would silently satisfy or
     * reshape a call this suite means to see. Every callback the aggregate must not use fails
     * loudly.</p>
     */
    private abstract static class EntryListFixture implements List<IdMapEntry> {

        @Override
        public boolean isEmpty() {
            throw new AssertionError("isEmpty must not be used to decide the aggregate");
        }

        @Override
        public boolean contains(Object value) {
            throw new AssertionError("contains must not be called");
        }

        @Override
        public IdMapEntry get(int index) {
            throw new AssertionError("get must not be used to read the mapping");
        }

        @Override
        public Object[] toArray() {
            throw new AssertionError("the mapping must never be materialised as an array");
        }

        @Override
        public <T> T[] toArray(T[] target) {
            throw new AssertionError("the mapping must never be materialised as an array");
        }

        @Override
        public boolean add(IdMapEntry value) {
            throw new AssertionError("the mapping is never written");
        }

        @Override
        public boolean remove(Object value) {
            throw new AssertionError("the mapping is never written");
        }

        @Override
        public boolean containsAll(java.util.Collection<?> values) {
            throw new AssertionError("containsAll must not be called");
        }

        @Override
        public boolean addAll(java.util.Collection<? extends IdMapEntry> values) {
            throw new AssertionError("the mapping is never written");
        }

        @Override
        public boolean addAll(int index, java.util.Collection<? extends IdMapEntry> values) {
            throw new AssertionError("the mapping is never written");
        }

        @Override
        public boolean removeAll(java.util.Collection<?> values) {
            throw new AssertionError("the mapping is never written");
        }

        @Override
        public boolean retainAll(java.util.Collection<?> values) {
            throw new AssertionError("the mapping is never written");
        }

        @Override
        public void clear() {
            throw new AssertionError("the mapping is never written");
        }

        @Override
        public IdMapEntry set(int index, IdMapEntry value) {
            throw new AssertionError("the mapping is never written");
        }

        @Override
        public void add(int index, IdMapEntry value) {
            throw new AssertionError("the mapping is never written");
        }

        @Override
        public IdMapEntry remove(int index) {
            throw new AssertionError("the mapping is never written");
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
        public java.util.ListIterator<IdMapEntry> listIterator() {
            throw new AssertionError("no list iterator may be requested");
        }

        @Override
        public java.util.ListIterator<IdMapEntry> listIterator(int index) {
            throw new AssertionError("no list iterator may be requested");
        }

        @Override
        public List<IdMapEntry> subList(int from, int to) {
            throw new AssertionError("subList must not be called");
        }
    }

    /** Proves the exception type is the contract one and never a raw {@link NoSuchElementException}. */
    @Test
    void aShorterIteratorIsReportedAsAnIllegalArgument() {
        final RuntimeException thrown = assertThrows(IllegalArgumentException.class,
                () -> absent(new IteratorShapedList(2, List.of(definition(new UUID(0L, 8L))))));
        assertFalse(thrown instanceof NoSuchElementException);
    }

    // ------------------------------------------------------------------
    // source position and target domains
    // ------------------------------------------------------------------

    @Test
    void theSameSourcePositionCollidesAcrossKindsAndTargets() {
        final UUID teamId = new UUID(1L, 1L);
        final UUID definitionId = new UUID(0L, 1L);

        // the same JSON root is claimed by a definition and by a team: different kinds, different
        // targets, still one position
        assertThrows(IllegalArgumentException.class,
                () -> absent(List.of(definition(definitionId), jsonEntry(IdKind.TEAM, List.of(),
                        teamId, emptyMembers()))));
        // the same document, the same kind, different targets
        assertThrows(IllegalArgumentException.class,
                () -> absent(List.of(definition(definitionId),
                        jsonEntry(IdKind.TEAM, List.of("a"), teamId, emptyMembers()),
                        jsonEntry(IdKind.TEAM, List.of("a"), new UUID(1L, 2L), emptyMembers()))));
        // the same document, the same position, different kinds and different target domains
        assertThrows(IllegalArgumentException.class,
                () -> absent(List.of(definition(definitionId),
                        jsonEntry(IdKind.TEAM, List.of("x"), teamId, emptyMembers()),
                        jsonEntry(IdKind.SPAWN, List.of("x"), new UUID(3L, 1L), emptyMembers()))));
        // a CFG sourced position collides with another CFG sourced entry just the same
        assertThrows(IllegalArgumentException.class,
                () -> present(List.of(definition(definitionId),
                        cfgEntry(IdKind.TEAM, List.of("c"), teamId, emptyMembers()),
                        cfgEntry(IdKind.TEAM, List.of("c"), new UUID(1L, 3L), emptyMembers()))));
    }

    @Test
    void jsonAndCfgSegmentsAreDifferentSourcesWhileTheCfgExists() {
        // the same segments read from two different documents are two different source positions
        final MigrationRecord accepted = present(List.of(definition(new UUID(0L, 2L)),
                jsonEntry(IdKind.TEAM, List.of("same"), new UUID(1L, 1L), emptyMembers()),
                cfgEntry(IdKind.TEAM, List.of("same"), new UUID(1L, 2L), emptyMembers())));
        assertEquals(3, accepted.idMap().size());

        // a CFG root may carry a non definition entry
        final MigrationRecord cfgRoot = present(List.of(definition(new UUID(0L, 3L)),
                cfgEntry(IdKind.REGION, List.of(), new UUID(4L, 1L), emptyMembers())));
        assertEquals(SourceDocument.CFG, cfgRoot.idMap().get(1).sourceDocument());
    }

    @Test
    void segmentDivisionNumericAndUnicodeSpellingsAreNeverMerged() {
        final MigrationRecord division = absent(List.of(definition(new UUID(0L, 4L)),
                jsonEntry(IdKind.TEAM, List.of("a/b"), new UUID(1L, 1L), emptyMembers()),
                jsonEntry(IdKind.TEAM, List.of("a", "b"), new UUID(1L, 2L), emptyMembers())));
        assertEquals(2, countKind(division.idMap(), IdKind.TEAM));

        final MigrationRecord numeric = absent(List.of(definition(new UUID(0L, 5L)),
                jsonEntry(IdKind.TEAM, List.of("01"), new UUID(1L, 3L), emptyMembers()),
                jsonEntry(IdKind.TEAM, List.of("1"), new UUID(1L, 4L), emptyMembers())));
        assertEquals(List.of("01"), numeric.idMap().get(1).sourceSegments());
        assertEquals(List.of("1"), numeric.idMap().get(2).sourceSegments());

        final String nfc = "\u00e9";
        final String nfd = "e\u0301";
        assertNotEquals(nfc, nfd);
        final MigrationRecord unicode = absent(List.of(definition(new UUID(0L, 6L)),
                jsonEntry(IdKind.TEAM, List.of(nfc), new UUID(1L, 5L), emptyMembers()),
                jsonEntry(IdKind.TEAM, List.of(nfd), new UUID(1L, 6L), emptyMembers())));
        assertEquals(2, countKind(unicode.idMap(), IdKind.TEAM));

        final MigrationRecord empty = absent(List.of(definition(new UUID(0L, 7L)),
                jsonEntry(IdKind.SPAWN, List.of(""), new UUID(3L, 1L), emptyMembers()),
                jsonEntry(IdKind.SPAWN, List.of("\u0000"), new UUID(3L, 2L), emptyMembers())));
        assertEquals(List.of(""), empty.idMap().get(1).sourceSegments());
        assertEquals(List.of("\u0000"), empty.idMap().get(2).sourceSegments());
    }

    @Test
    void twoTeamsWithOneTargetAreRejectedEvenFromDifferentSources() {
        final UUID shared = new UUID(1L, 1L);
        assertThrows(IllegalArgumentException.class,
                () -> absent(List.of(definition(new UUID(0L, 8L)),
                        jsonEntry(IdKind.TEAM, List.of("first"), shared, emptyMembers()),
                        jsonEntry(IdKind.TEAM, List.of("second"), shared, emptyMembers()))));
        // the very same shape is legal once the targets differ
        final MigrationRecord accepted = absent(List.of(definition(new UUID(0L, 8L)),
                jsonEntry(IdKind.TEAM, List.of("first"), shared, emptyMembers()),
                jsonEntry(IdKind.TEAM, List.of("second"), new UUID(1L, 2L), emptyMembers())));
        assertEquals(2, countKind(accepted.idMap(), IdKind.TEAM));
    }

    @Test
    void spawnsAndRegionsShareOneObjectTargetDomain() {
        final UUID shared = new UUID(3L, 1L);
        final UUID definitionId = new UUID(0L, 9L);

        assertThrows(IllegalArgumentException.class,
                () -> absent(List.of(definition(definitionId),
                        jsonEntry(IdKind.SPAWN, List.of("s1"), shared, emptyMembers()),
                        jsonEntry(IdKind.SPAWN, List.of("s2"), shared, emptyMembers()))));
        assertThrows(IllegalArgumentException.class,
                () -> absent(List.of(definition(definitionId),
                        jsonEntry(IdKind.REGION, List.of("r1"), shared, emptyMembers()),
                        jsonEntry(IdKind.REGION, List.of("r2"), shared, emptyMembers()))));
        // the shared domain is cross type: one spawn and one region with the same target collide
        assertThrows(IllegalArgumentException.class,
                () -> absent(List.of(definition(definitionId),
                        jsonEntry(IdKind.SPAWN, List.of("s1"), shared, emptyMembers()),
                        jsonEntry(IdKind.REGION, List.of("r1"), shared, emptyMembers()))));
        // while distinct object targets stay legal
        final MigrationRecord accepted = absent(List.of(definition(definitionId),
                jsonEntry(IdKind.SPAWN, List.of("s1"), shared, emptyMembers()),
                jsonEntry(IdKind.REGION, List.of("r1"), new UUID(3L, 2L), emptyMembers())));
        assertEquals(2, countKind(accepted.idMap(), IdKind.SPAWN)
                + countKind(accepted.idMap(), IdKind.REGION));
    }

    @Test
    void definitionTeamAndObjectTargetDomainsStaySeparate() {
        final UUID shared = new UUID(2L, 2L);
        final MigrationRecord record = absent(List.of(
                rootDefinition(shared, emptyMembers()),
                jsonEntry(IdKind.TEAM, List.of("team"), shared, emptyMembers()),
                jsonEntry(IdKind.SPAWN, List.of("spawn"), shared, emptyMembers()),
                jsonEntry(IdKind.REGION, List.of("region"), new UUID(2L, 3L), emptyMembers())));

        assertEquals(4, record.idMap().size());
        assertSame(shared, record.idMap().get(0).id());
        assertEquals(IdKind.DEFINITION, record.idMap().get(0).kind());
        assertSame(shared, record.idMap().get(1).id());
        assertEquals(IdKind.TEAM, record.idMap().get(1).kind());
        assertSame(shared, record.idMap().get(2).id());
        assertEquals(IdKind.SPAWN, record.idMap().get(2).kind());
        // the three domains are not merged into one set, so the same typed value can appear in each
        assertSame(record.idMap().get(0).id(), record.idMap().get(2).id());
    }

    @Test
    void theSameSourceWithDifferentTargetsIsStillRejected() {
        // the target rule must not stand in for the source rule: here the targets are all distinct
        // and only the source position is broken
        final List<String> position = List.of("shared", "position");
        assertThrows(IllegalArgumentException.class,
                () -> absent(List.of(definition(new UUID(0L, 10L)),
                        jsonEntry(IdKind.TEAM, position, new UUID(1L, 1L), emptyMembers()),
                        jsonEntry(IdKind.SPAWN, position, new UUID(3L, 1L), emptyMembers()))));
        assertThrows(IllegalArgumentException.class,
                () -> present(List.of(definition(new UUID(0L, 10L)),
                        cfgEntry(IdKind.TEAM, position, new UUID(1L, 1L), emptyMembers()),
                        cfgEntry(IdKind.REGION, position, new UUID(4L, 1L), emptyMembers()))));
    }

    @Test
    void anAbsentCfgForbidsCfgEntriesWhileAPresentCfgAcceptsThem() {
        final IdMapEntry cfgTeam = cfgEntry(IdKind.TEAM, List.of("c"), new UUID(1L, 1L),
                emptyMembers());
        final List<IdMapEntry> mapping = List.of(definition(new UUID(0L, 11L)), cfgTeam);

        assertThrows(IllegalArgumentException.class, () -> absent(mapping));
        // the identical candidate is legal as soon as the cfg exists
        final MigrationRecord accepted = present(mapping);
        assertEquals(2, accepted.idMap().size());
        assertSame(cfgTeam, accepted.idMap().get(1));
        assertEquals(SourceDocument.CFG, accepted.idMap().get(1).sourceDocument());
        // a historical mapping may be kept even though this value type cannot look at the payload
        assertEquals(IdKind.TEAM, accepted.idMap().get(1).kind());
    }

    @Test
    void theScopeIsTheGivenOneAndNeverADisplayName() {
        final ExactJson.FrozenJson shared = emptyMembers();
        final List<IdMapEntry> mapping = List.of(rootDefinition(new UUID(0L, 12L), shared));
        final SourceScope first = scope("ns:first", "rename-me");
        final SourceScope second = scope("ns:second", "rename-me");

        final MigrationRecord one = present(first, hashesWithCfg(),
                Optional.of(members("{\"cfg\":1}")), mapping, members("{}"), shared);
        final MigrationRecord two = present(second, hashesWithCfg(),
                Optional.of(members("{\"cfg\":1}")), mapping, members("{}"), shared);

        assertSame(first, one.sourceScope());
        assertSame(second, two.sourceScope());
        assertNotEquals(one.sourceScope(), two.sourceScope());
        assertSame(shared, one.members());
        assertSame(shared, two.members());
    }

    // ------------------------------------------------------------------
    // published value
    // ------------------------------------------------------------------

    @Test
    void thePublishedListIsImmutableAndIsolatedFromTheCaller() {
        final List<IdMapEntry> input = new ArrayList<>(oneDefinitionOneTeam());
        final MigrationRecord record = absent(input);
        assertEquals(2, record.idMap().size());

        // the caller may clear or overwrite its own list without touching the published value
        input.clear();
        input.add(jsonEntry(IdKind.SPAWN, List.of("late"), new UUID(3L, 9L), emptyMembers()));
        assertEquals(2, record.idMap().size());
        assertEquals(IdKind.DEFINITION, record.idMap().get(0).kind());
        assertEquals(IdKind.TEAM, record.idMap().get(1).kind());
        assertFalse(record.idMap().isEmpty());
        assertEquals(2, record.idMap().size());
        assertThrows(UnsupportedOperationException.class,
                () -> record.idMap().add(definition(new UUID(0L, 13L))));
        assertThrows(UnsupportedOperationException.class,
                () -> record.idMap().set(0, definition(new UUID(0L, 14L))));
        assertThrows(UnsupportedOperationException.class, () -> record.idMap().clear());
    }

    @Test
    void everyRequiredFieldParticipatesInTheRecordValueSemantics() {
        final ExactJson.FrozenJson shared = emptyMembers();
        final List<IdMapEntry> mapping = List.of(rootDefinition(new UUID(0L, 15L), shared));
        final MigrationRecord base = absent(mapping);
        final MigrationRecord same = absent(List.of(rootDefinition(new UUID(0L, 15L), shared)));

        assertEquals(base, base);
        assertEquals(base, same);
        assertEquals(base.hashCode(), same.hashCode());
        assertTrue(base.toString().contains("MigrationRecord"));

        assertNotEquals(base, null);
        assertNotEquals(base, "not a migration record");

        // a different scope
        assertNotEquals(base, new MigrationRecord(scope("ns:other", "legacy-key"),
                base.sourceHashes(), base.cfgState(), base.missingCfgPolicy(), base.idMap(),
                base.legacyJson(), base.legacyCfg(), base.members()));
        // different source hashes, still inside the legal absent combination
        final SourceHashes otherHashes = new SourceHashes(
                hash(SourceKind.FILE_BYTES, SHA_F, SHA_ZERO), Optional.empty(), emptyMembers());
        assertNotEquals(base, new MigrationRecord(base.sourceScope(), otherHashes,
                base.cfgState(), base.missingCfgPolicy(), base.idMap(), base.legacyJson(),
                base.legacyCfg(), base.members()));
        // a different cfg state, with its own legal combination
        assertNotEquals(base, present(base.idMap()));
        // a different policy
        assertNotEquals(base, absent(MissingCfgPolicy.PROVIDER_DEFAULTS, base.idMap()));
        // a different mapping
        assertNotEquals(base, absent(List.of(rootDefinition(new UUID(0L, 16L), shared))));
        // a different legacy json container
        assertNotEquals(base, new MigrationRecord(base.sourceScope(), base.sourceHashes(),
                base.cfgState(), base.missingCfgPolicy(), base.idMap(), members("{\"other\":1}"),
                base.legacyCfg(), base.members()));
        // a different legacy cfg optional
        assertNotEquals(present(mapping), present(scope(), hashesWithCfg(),
                Optional.of(members("{\"cfg\":2}")), mapping, members("[1,2]"), emptyMembers()));
        // a different member container
        assertNotEquals(base, new MigrationRecord(base.sourceScope(), base.sourceHashes(),
                base.cfgState(), base.missingCfgPolicy(), base.idMap(), base.legacyJson(),
                base.legacyCfg(), members("{\"m\":1}")));
    }

    // ------------------------------------------------------------------
    // the thousand sample model
    // ------------------------------------------------------------------

    @Test
    void aThousandFixedSeedCandidatesMatchAnIndependentModel() {
        final Random random = new Random(20261015L);
        final ExactJson.FrozenJson shared = emptyMembers();
        final ExactJson.FrozenJson legacyJson = members("{}");
        final ExactJson.FrozenJson legacyCfgValue = members("{\"cfg\":1}");
        int expectedAccepts = 0;
        int expectedRejects = 0;

        for (int sample = 0; sample < 1000; sample++) {
            final List<IdMapEntry> entries = new ArrayList<>();
            final boolean dropDefinition = random.nextInt(100) < 10;
            final boolean duplicateSource = random.nextInt(100) < 12;
            final boolean duplicateTeamTarget = random.nextInt(100) < 10;
            final boolean duplicateObjectTarget = random.nextInt(100) < 8;
            final boolean cfgSourceEntry = random.nextBoolean();
            final boolean cfgCombinationBroken = random.nextInt(100) < 5;
            final int breakFlavour = cfgCombinationBroken ? sample % 3 : -1; // b2: 不消耗原 seed Random
            final CfgState cfgState = cfgSourceEntry || random.nextInt(100) < 20
                    ? CfgState.ABSENT
                    : CfgState.PRESENT;
            final UUID definitionId = new UUID(0L, sample);
            final UUID teamTarget = new UUID(1L, sample);
            final UUID objectTarget = new UUID(3L, sample);

            if (!dropDefinition) {
                entries.add(rootDefinition(definitionId, shared));
            }
            entries.add(jsonEntry(IdKind.TEAM, List.of("team", "sample" + sample), teamTarget,
                    shared));
            entries.add(jsonEntry(IdKind.SPAWN, List.of("spawn", "sample" + sample), objectTarget,
                    shared));
            entries.add(jsonEntry(IdKind.REGION, List.of("region", "sample" + sample),
                    new UUID(4L, sample), shared));

            if (duplicateTeamTarget) {
                entries.add(jsonEntry(IdKind.TEAM, List.of("team", "other" + sample), teamTarget,
                        shared));
            }
            if (duplicateObjectTarget) {
                entries.add(cfgState == CfgState.PRESENT
                        ? cfgEntry(IdKind.REGION, List.of("region", "twin" + sample), objectTarget,
                                shared)
                        : jsonEntry(IdKind.REGION, List.of("region", "twin" + sample), objectTarget,
                                shared));
            }
            if (duplicateSource) {
                entries.add(jsonEntry(IdKind.SPAWN, List.of("team", "sample" + sample),
                        new UUID(6L, sample), shared));
            }
            if (cfgSourceEntry) {
                entries.add(cfgEntry(IdKind.SPAWN, List.of("cfg", "sample" + sample),
                        new UUID(7L, sample), shared));
            }

            // The broken combination only ever shapes the real components below; it never enters the
            // expectation. Each flavour breaks a different presence bit, so a broken sample can miss
            // the cfg hash, the legacy cfg or the policy emptiness just as it can add a forbidden one.
            final boolean cfgHashPresent;
            final boolean legacyCfgPresent;
            final boolean policyPresent;
            if (cfgState == CfgState.PRESENT) {
                cfgHashPresent = breakFlavour != 0;
                legacyCfgPresent = breakFlavour != 1;
                policyPresent = breakFlavour == 2;
            } else {
                cfgHashPresent = breakFlavour == 0;
                legacyCfgPresent = breakFlavour == 1;
                policyPresent = breakFlavour != 2;
            }
            final Candidate candidate = new Candidate(scope(),
                    cfgHashPresent ? hashesWithCfg() : hashesWithoutCfg(), cfgState,
                    policyPresent ? Optional.of(MissingCfgPolicy.REJECT) : Optional.empty(),
                    List.copyOf(entries), legacyJson,
                    legacyCfgPresent ? Optional.of(legacyCfgValue) : Optional.empty(), shared);

            // the independent model reads this frozen input and decides before production is called
            final boolean expected = modelAccepts(candidate);
            if (expected) {
                expectedAccepts++;
                final MigrationRecord record = candidate.toRecord();
                final List<IdMapEntry> frozen = candidate.idMap();
                assertEquals(frozen.size(), record.idMap().size());
                for (int i = 0; i < frozen.size(); i++) {
                    assertSame(frozen.get(i), record.idMap().get(i));
                    assertSame(shared, record.idMap().get(i).members());
                }
            } else {
                expectedRejects++;
                final RuntimeException thrown = assertThrows(IllegalArgumentException.class,
                        candidate::toRecord);
                assertFalse(thrown instanceof NoSuchElementException);
            }
        }

        assertEquals(1000, expectedAccepts + expectedRejects);
        assertTrue(expectedAccepts > 0);
        assertTrue(expectedRejects > 0);
    }

    private static MigrationRecord absent(MissingCfgPolicy policy, List<IdMapEntry> idMap,
            ExactJson.FrozenJson members) {
        return absent(Optional.of(policy), idMap, members);
    }

    /** An absent cfg candidate with the given policy optional, which may be deliberately empty. */
    private static MigrationRecord absent(Optional<MissingCfgPolicy> policy, List<IdMapEntry> idMap,
            ExactJson.FrozenJson members) {
        return new MigrationRecord(scope(), hashesWithoutCfg(), CfgState.ABSENT, policy, idMap,
                members("{}"), Optional.empty(), members);
    }

    /**
     * One frozen sampled input: exactly the eight components that are handed to the production
     * record. The independent model and the production call both read this same value, so a sample
     * predicted to be illegal is rejected with the very input it was predicted from; nothing is
     * regenerated, reordered or repaired between the prediction and the call.
     */
    private record Candidate(SourceScope sourceScope, SourceHashes sourceHashes, CfgState cfgState,
            Optional<MissingCfgPolicy> policy, List<IdMapEntry> idMap,
            ExactJson.FrozenJson legacyJson, Optional<ExactJson.FrozenJson> legacyCfg,
            ExactJson.FrozenJson members) {

        MigrationRecord toRecord() {
            return new MigrationRecord(sourceScope, sourceHashes, cfgState, policy, idMap, legacyJson,
                    legacyCfg, members);
        }
    }

    /**
     * The independent model decided from the frozen input's own components.
     *
     * <p>The cfg combination is read off the actual {@code sourceHashes.cfg()}, {@code legacyCfg()}
     * and {@code policy()} values of that input, never off a generation flag, and the aggregate rules
     * then run over the very entries that are handed to production.</p>
     */
    private static boolean modelAccepts(Candidate candidate) {
        final boolean cfgHashPresent = candidate.sourceHashes().cfg().isPresent();
        final boolean legacyCfgPresent = candidate.legacyCfg().isPresent();
        final boolean policyPresent = candidate.policy().isPresent();
        if (candidate.cfgState() == CfgState.PRESENT) {
            if (!cfgHashPresent || !legacyCfgPresent || policyPresent) {
                return false;
            }
        } else if (cfgHashPresent || legacyCfgPresent || !policyPresent) {
            return false;
        }
        return modelAccepts(candidate.idMap(), candidate.cfgState());
    }

    /**
     * The independent local model of the aggregate rules.
     *
     * <p>It is computed from the generated entries alone, before the production record is called, and
     * it shares no code with the type under test. The source positions are compared as documents
     * with their whole immutable segment list, never as a joined string and never as a pointer.</p>
     */
    private static boolean modelAccepts(List<IdMapEntry> entries, CfgState cfgState) {
        int definitions = 0;
        int teams = 0;
        int spawns = 0;
        int regions = 0;
        final Set<List<String>> jsonSources = new LinkedHashSet<>();
        final Set<List<String>> cfgSources = new LinkedHashSet<>();
        final Set<UUID> teamTargets = new LinkedHashSet<>();
        final Set<UUID> objectTargets = new LinkedHashSet<>();
        for (IdMapEntry candidate : entries) {
            switch (candidate.kind()) {
                case DEFINITION -> definitions++;
                case TEAM -> teams++;
                case SPAWN -> spawns++;
                case REGION -> regions++;
            }
            if (cfgState == CfgState.ABSENT && candidate.sourceDocument() == SourceDocument.CFG) {
                return false;
            }
            final Set<List<String>> sources = candidate.sourceDocument() == SourceDocument.JSON
                    ? jsonSources
                    : cfgSources;
            if (!sources.add(candidate.sourceSegments())) {
                return false;
            }
            switch (candidate.kind()) {
                case TEAM -> {
                    if (!teamTargets.add(candidate.id())) {
                        return false;
                    }
                }
                case SPAWN, REGION -> {
                    if (!objectTargets.add(candidate.id())) {
                        return false;
                    }
                }
                case DEFINITION -> {
                    // no target domain
                }
            }
        }
        return definitions == 1 && teams <= 64 && spawns <= 16384 && regions <= 1024;
    }
}
