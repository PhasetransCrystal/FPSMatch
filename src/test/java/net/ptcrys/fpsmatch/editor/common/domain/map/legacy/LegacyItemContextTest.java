package net.ptcrys.fpsmatch.editor.common.domain.map.legacy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.core.resources.LogicalResource;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceCatalog;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceRef;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceVersion;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import org.junit.jupiter.api.Test;

class LegacyItemContextTest {
    private static final UUID RESOURCE = uuid(1), V1 = uuid(2), V2 = uuid(3);
    private static final ResourceRef LOCKED = new ResourceRef(RESOURCE, V1);
    private static final EditorId TYPE = id("extension:bundle"), IMPORTER = id("extension:import_v1");
    private static final LegacyItemResourceKey KEY = key("extension:adapter", "extension:skin", "foreign:asset");
    private static final ExactJson.FrozenJson ITEM = ExactJson.parse("{\"id\":\"extension:item\",\"Count\":1}");

    @Test void keyIdentityUsesAllThreeExtensionDefinedIdsAndTheirLexicographicOrder() {
        List<LegacyItemResourceKey> expected = List.of(
                key("a:adapter", "z:kind", "z:asset"),
                key("b:adapter", "a:kind", "z:asset"),
                key("b:adapter", "b:kind", "a:asset"),
                key("b:adapter", "b:kind", "b:asset"));
        TreeSet<LegacyItemResourceKey> sorted = new TreeSet<>();
        sorted.add(expected.get(3)); sorted.add(expected.get(1)); sorted.add(expected.get(2)); sorted.add(expected.get(0));
        assertEquals(expected, new ArrayList<>(sorted));
        assertEquals(KEY, key("extension:adapter", "extension:skin", "foreign:asset"));
        assertNotEquals(KEY, key("other:adapter", "extension:skin", "foreign:asset"));
        assertNotEquals(KEY, key("extension:adapter", "other:skin", "foreign:asset"));
        assertNotEquals(KEY, key("extension:adapter", "extension:skin", "other:asset"));
        assertThrows(NullPointerException.class, () -> new LegacyItemResourceKey(null, TYPE, IMPORTER));
        assertThrows(NullPointerException.class, () -> new LegacyItemResourceKey(TYPE, null, IMPORTER));
        assertThrows(NullPointerException.class, () -> new LegacyItemResourceKey(TYPE, IMPORTER, null));
    }

    @Test void selectedV1MetadataAndAliasesRemainFixedWhenCurrentIsV2AndInputsChange() {
        LegacyItemResourceKey alias = key("other:adapter", "custom:appearance", "other:asset");
        LinkedHashMap<LegacyItemResourceKey, ResourceRef> input = new LinkedHashMap<>();
        input.put(alias, LOCKED); input.put(KEY, LOCKED);
        LegacyItemContext context = LegacyItemContext.from(catalog(), input);
        var binding = context.find(KEY).orElseThrow();
        assertEquals(LOCKED, binding.reference());
        assertEquals(TYPE, binding.resourceType());
        assertEquals(IMPORTER, binding.importer());
        assertEquals("algorithm-v1", binding.importerVersion());
        assertEquals(LOCKED, context.find(alias).orElseThrow().reference());
        assertEquals(List.of(KEY, alias), new ArrayList<>(context.bindings().keySet()));
        input.clear(); input.put(KEY, new ResourceRef(RESOURCE, V2));
        LegacyItemContext next = LegacyItemContext.from(catalog(), input);
        assertEquals(new ResourceRef(RESOURCE, V2), next.find(KEY).orElseThrow().reference());
        assertEquals(id("different:import_v2"), next.find(KEY).orElseThrow().importer());
        assertEquals("algorithm-v2", next.find(KEY).orElseThrow().importerVersion());
        assertEquals(LOCKED, context.bindings().get(KEY));
        assertSame(binding, context.find(KEY).orElseThrow());
        assertTrue(context.find(key("extension:adapter", "extension:skin", "missing:asset")).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> context.bindings().clear());
        assertThrows(UnsupportedOperationException.class, () -> context.bindings().entrySet().iterator().next().setValue(LOCKED));
        assertThrows(NullPointerException.class, () -> context.find(null));
        assertSame(LegacyItemContext.empty(), LegacyItemContext.empty());
        assertTrue(LegacyItemContext.empty().bindings().isEmpty());
        assertTrue(LegacyItemContext.empty().find(KEY).isEmpty());
    }

    @Test void bindingLimitIsCheckedBeforeTraversalAndTheExactLimitAllowsAliases() {
        assertEquals(8192, LegacyItemContext.MAX_BINDINGS);
        for (int invalid : List.of(-1, LegacyItemContext.MAX_BINDINGS + 1)) {
            Map<LegacyItemResourceKey, ResourceRef> forbidden = new AbstractMap<>() {
                @Override public int size() { return invalid; }
                @Override public Set<Entry<LegacyItemResourceKey, ResourceRef>> entrySet() {
                    throw new AssertionError("budget must reject before traversal");
                }
            };
            assertThrows(IllegalArgumentException.class, () -> LegacyItemContext.from(catalog(), forbidden));
        }
        Map<LegacyItemResourceKey, ResourceRef> boundary = new LinkedHashMap<>();
        for (int i = 0; i < LegacyItemContext.MAX_BINDINGS; i++) {
            boundary.put(key("extension:adapter", "custom:kind", "custom:asset_" + i), LOCKED);
        }
        assertEquals(LegacyItemContext.MAX_BINDINGS, LegacyItemContext.from(catalog(), boundary).bindings().size());
    }

    @Test void nullInputsAndMissingOrMisattributedReferencesAreRejectedWithoutCurrentFallback() {
        assertThrows(NullPointerException.class, () -> LegacyItemContext.from(null, Map.of()));
        assertThrows(NullPointerException.class, () -> LegacyItemContext.from(catalog(), null));
        Map<LegacyItemResourceKey, ResourceRef> nullKey = new LinkedHashMap<>(); nullKey.put(null, LOCKED);
        Map<LegacyItemResourceKey, ResourceRef> nullRef = new LinkedHashMap<>(); nullRef.put(KEY, null);
        assertThrows(NullPointerException.class, () -> LegacyItemContext.from(catalog(), nullKey));
        assertThrows(NullPointerException.class, () -> LegacyItemContext.from(catalog(), nullRef));
        for (ResourceRef missing : List.of(new ResourceRef(uuid(99), V1), new ResourceRef(RESOURCE, uuid(99)))) {
            assertThrows(IllegalArgumentException.class, () -> LegacyItemContext.from(catalog(), Map.of(KEY, missing)));
        }
        UUID other = uuid(20), otherVersion = uuid(21);
        ResourceCatalog two = new ResourceCatalog(
                Map.of(RESOURCE, catalog().resources().get(RESOURCE), other, new LogicalResource(other, TYPE, "other", otherVersion)),
                Map.of(V1, version(V1, RESOURCE, Set.of()), V2, version(V2, RESOURCE, Set.of()),
                        otherVersion, version(otherVersion, other, Set.of())), Map.of());
        assertThrows(IllegalArgumentException.class, () -> LegacyItemContext.from(two,
                Map.of(KEY, new ResourceRef(RESOURCE, otherVersion))));
    }

    @Test void evenEmptyBindingsValidateAllCatalogRelationshipsCyclesAndDescriptorBounds() {
        ResourceCatalog missingCurrent = new ResourceCatalog(
                Map.of(RESOURCE, new LogicalResource(RESOURCE, TYPE, "bundle", V2)),
                Map.of(V1, version(V1, RESOURCE, Set.of())), Map.of());
        ResourceCatalog unreachableBadVersion = new ResourceCatalog(catalog().resources(),
                Map.of(V1, version(V1, RESOURCE, Set.of(uuid(99))), V2, version(V2, RESOURCE, Set.of())), Map.of());
        ResourceCatalog cycle = new ResourceCatalog(catalog().resources(),
                Map.of(V1, version(V1, RESOURCE, Set.of(V2)), V2, version(V2, RESOURCE, Set.of(V1))), Map.of());
        ResourceCatalog missingOutput = new ResourceCatalog(catalog().resources(),
                Map.of(V1, version(V1, RESOURCE, Set.of()), V2,
                        new ResourceVersion(V2, RESOURCE, IMPORTER, "1", Map.of(), null, Set.of(), Set.of(uuid(99)))), Map.of());
        ResourceCatalog longDisplayName = new ResourceCatalog(
                Map.of(RESOURCE, new LogicalResource(RESOURCE, TYPE, "x".repeat(4097), V2)), catalog().versions(), Map.of());
        for (ResourceCatalog bad : List.of(missingCurrent, unreachableBadVersion, cycle, missingOutput, longDisplayName)) {
            assertThrows(IllegalArgumentException.class, () -> LegacyItemContext.from(bad, Map.of()));
        }
        assertTrue(LegacyItemContext.from(catalog(), Map.of()).bindings().isEmpty());
    }

    @Test void oldSamDefaultOverloadChecksArgumentsAndCallsOriginalExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        Optional<LegacyItemEncoding> expected = Optional.of(encoding());
        LegacyItemAdapter old = item -> { assertSame(ITEM, item); calls.incrementAndGet(); return expected; };
        assertSame(expected, old.convert(ITEM, LegacyItemContext.empty()));
        assertEquals(1, calls.get());
        assertThrows(NullPointerException.class, () -> old.convert(null, LegacyItemContext.empty()));
        assertThrows(NullPointerException.class, () -> old.convert(ITEM, null));
        assertEquals(1, calls.get());
        IllegalStateException failure = new IllegalStateException("legacy failure");
        LegacyItemAdapter broken = item -> { throw failure; };
        assertSame(failure, assertThrows(IllegalStateException.class, () -> broken.convert(ITEM, LegacyItemContext.empty())));
        AssertionError error = new AssertionError("legacy error");
        LegacyItemAdapter fatal = item -> { throw error; };
        assertSame(error, assertThrows(AssertionError.class, () -> fatal.convert(ITEM, LegacyItemContext.empty())));
    }

    @Test void contextualSingleAndDoubleArgumentCallsShareTheFunctionWithoutHidingFailures() {
        AtomicInteger calls = new AtomicInteger(); List<LegacyItemContext> contexts = new ArrayList<>();
        Optional<LegacyItemEncoding> expected = Optional.of(encoding());
        LegacyItemAdapter adapter = LegacyItemAdapter.contextual((item, context) -> {
            assertSame(ITEM, item); calls.incrementAndGet(); contexts.add(context); return expected;
        });
        LegacyItemContext selected = LegacyItemContext.from(catalog(), Map.of(KEY, LOCKED));
        assertSame(expected, adapter.convert(ITEM)); assertSame(expected, adapter.convert(ITEM, selected));
        assertEquals(2, calls.get()); assertSame(LegacyItemContext.empty(), contexts.get(0)); assertSame(selected, contexts.get(1));
        assertThrows(NullPointerException.class, () -> adapter.convert(null));
        assertThrows(NullPointerException.class, () -> adapter.convert(null, selected));
        assertThrows(NullPointerException.class, () -> adapter.convert(ITEM, null));
        assertEquals(2, calls.get());
        assertThrows(NullPointerException.class, () -> LegacyItemAdapter.contextual(null));
        AtomicInteger nullCalls = new AtomicInteger();
        LegacyItemAdapter nullResult = LegacyItemAdapter.contextual((item, context) -> { nullCalls.incrementAndGet(); return null; });
        assertThrows(NullPointerException.class, () -> nullResult.convert(ITEM));
        assertThrows(NullPointerException.class, () -> nullResult.convert(ITEM, selected)); assertEquals(2, nullCalls.get());
        IllegalArgumentException failure = new IllegalArgumentException("context failure");
        AssertionError fatal = new AssertionError("context error");
        LegacyItemAdapter broken = LegacyItemAdapter.contextual((item, context) -> { throw failure; });
        LegacyItemAdapter errored = LegacyItemAdapter.contextual((item, context) -> { throw fatal; });
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> broken.convert(ITEM)));
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> broken.convert(ITEM, selected)));
        assertSame(fatal, assertThrows(AssertionError.class, () -> errored.convert(ITEM)));
        assertSame(fatal, assertThrows(AssertionError.class, () -> errored.convert(ITEM, selected)));
        assertTrue(LegacyItemAdapter.contextual((item, context) -> Optional.empty()).convert(ITEM, selected).isEmpty());
    }

    private static ResourceCatalog catalog() {
        return new ResourceCatalog(Map.of(RESOURCE, new LogicalResource(RESOURCE, TYPE, "bundle", V2)),
                Map.of(V1, version(V1, RESOURCE, Set.of()), V2,
                        new ResourceVersion(V2, RESOURCE, id("different:import_v2"), "algorithm-v2", Map.of(), null, Set.of(), Set.of())), Map.of());
    }
    private static ResourceVersion version(UUID version, UUID resource, Set<UUID> dependencies) {
        return new ResourceVersion(version, resource, IMPORTER, "algorithm-v1", Map.of(), null, dependencies, Set.of());
    }
    private static LegacyItemEncoding encoding() { return new LegacyItemEncoding(TYPE, id("extension:codec"), 1, new byte[0]); }
    private static LegacyItemResourceKey key(String provider, String kind, String asset) {
        return new LegacyItemResourceKey(id(provider), id(kind), id(asset));
    }
    private static EditorId id(String text) { return EditorId.parse(text); }
    private static UUID uuid(long value) { return new UUID(0, value); }
}
