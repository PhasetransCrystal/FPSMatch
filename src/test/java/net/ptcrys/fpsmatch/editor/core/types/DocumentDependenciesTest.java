package net.ptcrys.fpsmatch.editor.core.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceRef;

/** Contract of {@link DocumentDependencies}: budget, copying, sorting and null handling. */
class DocumentDependenciesTest {

    private static final int MAX = DocumentDependencies.MAX_DEPENDENCIES;

    private static UUID id(long value) {
        return new UUID(0L, value);
    }

    private static ResourceRef ref(long resource, long version) {
        return new ResourceRef(id(resource), id(version));
    }

    private static ContributionKey key(String kind, String contribution) {
        return new ContributionKey(EditorId.parse("sample:" + kind), EditorId.parse("sample:" + contribution));
    }

    private static Set<UUID> documents(int count) {
        Set<UUID> values = new HashSet<>();
        for (int i = 0; i < count; i++) {
            values.add(id(i));
        }
        return values;
    }

    private static Set<ResourceRef> resources(int count) {
        Set<ResourceRef> values = new HashSet<>();
        for (int i = 0; i < count; i++) {
            values.add(ref(1000L + i, i));
        }
        return values;
    }

    private static Set<ContributionKey> contributions(int count) {
        Set<ContributionKey> values = new HashSet<>();
        for (int i = 0; i < count; i++) {
            values.add(key("badge", "rank" + i));
        }
        return values;
    }

    @Test
    void noDependenciesUseThreeEmptyImmutableSets() {
        DocumentDependencies empty = new DocumentDependencies(Set.of(), Set.of(), Set.of());

        assertTrue(empty.documents().isEmpty());
        assertTrue(empty.resources().isEmpty());
        assertTrue(empty.contributions().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> empty.documents().add(id(1)));
        assertThrows(UnsupportedOperationException.class, () -> empty.resources().add(ref(1, 1)));
        assertThrows(UnsupportedOperationException.class,
                () -> empty.contributions().add(key("badge", "rank")));
    }

    @Test
    void theCallersSetsAreCopiedAndNeverModified() {
        Set<UUID> documents = new HashSet<>(Set.of(id(7)));
        Set<ResourceRef> resources = new HashSet<>(Set.of(ref(2, 9)));
        Set<ContributionKey> contributions = new HashSet<>(Set.of(key("badge", "rank1")));

        DocumentDependencies dependencies = new DocumentDependencies(documents, resources, contributions);

        // The caller's sets are untouched by construction ...
        assertEquals(Set.of(id(7)), documents);
        assertEquals(Set.of(ref(2, 9)), resources);
        assertEquals(Set.of(key("badge", "rank1")), contributions);

        // ... and later changes to them do not leak into the record, nor does the record alias them.
        documents.add(id(8));
        resources.add(ref(1, 1));
        contributions.add(key("badge", "rank2"));

        assertEquals(Set.of(id(7)), dependencies.documents());
        assertEquals(Set.of(ref(2, 9)), dependencies.resources());
        assertEquals(Set.of(key("badge", "rank1")), dependencies.contributions());
        assertNotSame(documents, dependencies.documents());
        assertThrows(UnsupportedOperationException.class, () -> dependencies.documents().add(id(9)));
    }

    @Test
    void documentsSortByNaturalOrderAndResourcesByIdentityThenVersion() {
        DocumentDependencies dependencies = new DocumentDependencies(
                new HashSet<>(Set.of(id(7), id(3), id(5))),
                new HashSet<>(Set.of(ref(5, 50), ref(2, 90), ref(2, 9), ref(2, 30))),
                new HashSet<>(Set.of(key("badge", "rank2"), key("aaa", "x"), key("badge", "rank1"))));

        assertEquals(List.of(id(3), id(5), id(7)), List.copyOf(dependencies.documents()));
        assertEquals(List.of(ref(2, 9), ref(2, 30), ref(2, 90), ref(5, 50)),
                List.copyOf(dependencies.resources()));
        assertEquals(List.of(key("aaa", "x"), key("badge", "rank1"), key("badge", "rank2")),
                List.copyOf(dependencies.contributions()));
    }

    @Test
    void twoLockedVersionsOfTheSameResourceAreBothKept() {
        Set<ResourceRef> resources = Set.of(ref(1, 10), ref(1, 11));

        DocumentDependencies dependencies =
                new DocumentDependencies(Set.of(), resources, Set.of(key("badge", "rank1")));

        assertEquals(2, dependencies.resources().size());
        assertTrue(dependencies.resources().containsAll(resources));
        assertEquals(List.of(ref(1, 10), ref(1, 11)), List.copyOf(dependencies.resources()));
    }

    @Test
    void theTotalBudgetIsCheckedBeforeAnyDependencyIsInserted() {
        DocumentDependencies atLimit =
                new DocumentDependencies(documents(4000), resources(200), contributions(MAX - 4200));
        assertEquals(MAX, atLimit.documents().size() + atLimit.resources().size()
                + atLimit.contributions().size());

        IllegalArgumentException tooMany = assertThrows(IllegalArgumentException.class,
                () -> new DocumentDependencies(documents(4000), resources(200),
                        contributions(MAX - 4200 + 1)));
        assertTrue(tooMany.getMessage().contains(String.valueOf(MAX)));

        // The budget is evaluated before the per-element null checks: an oversized set with a null
        // element reports the exceeded budget, not a NullPointerException.
        Set<UUID> oversized = documents(MAX + 1);
        oversized.add(null);
        IllegalArgumentException budgetFirst = assertThrows(IllegalArgumentException.class,
                () -> new DocumentDependencies(oversized, Set.of(), Set.of()));
        assertTrue(budgetFirst.getMessage().contains(String.valueOf(MAX)));
    }

    @Test
    void nullSetsAndNullElementsAreRejected() {
        assertThrows(NullPointerException.class,
                () -> new DocumentDependencies(null, Set.of(), Set.of()));
        assertThrows(NullPointerException.class,
                () -> new DocumentDependencies(Set.of(), null, Set.of()));
        assertThrows(NullPointerException.class,
                () -> new DocumentDependencies(Set.of(), Set.of(), null));

        Set<UUID> documents = new HashSet<>();
        documents.add(id(1));
        documents.add(null);
        assertThrows(NullPointerException.class,
                () -> new DocumentDependencies(documents, Set.of(), Set.of()));

        Set<ResourceRef> resources = new HashSet<>();
        resources.add(ref(1, 1));
        resources.add(null);
        assertThrows(NullPointerException.class,
                () -> new DocumentDependencies(Set.of(), resources, Set.of()));

        Set<ContributionKey> contributions = new HashSet<>();
        contributions.add(key("badge", "rank1"));
        contributions.add(null);
        assertThrows(NullPointerException.class,
                () -> new DocumentDependencies(Set.of(), Set.of(), contributions));
    }

    @Test
    void equalContentComparesEqualRegardlessOfCallerOrder() {
        List<ResourceRef> shuffled = new ArrayList<>(List.of(ref(2, 30), ref(2, 9)));
        DocumentDependencies first = new DocumentDependencies(Set.of(),
                new HashSet<>(List.of(ref(2, 9), ref(2, 30))), Set.of());
        DocumentDependencies second = new DocumentDependencies(Set.of(),
                new HashSet<>(shuffled), Set.of());

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(first.toString(), second.toString());
    }
}
