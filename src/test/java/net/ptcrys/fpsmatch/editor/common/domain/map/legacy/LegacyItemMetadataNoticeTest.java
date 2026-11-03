package net.ptcrys.fpsmatch.editor.common.domain.map.legacy;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.ptcrys.fpsmatch.editor.api.*;
import net.ptcrys.fpsmatch.editor.core.resources.*;
import org.junit.jupiter.api.Test;

class LegacyItemMetadataNoticeTest {
    private static final EditorId TYPE = EditorId.parse("test:asset"), IMPORTER = EditorId.parse("test:importer");
    private static final UUID RESOURCE = new UUID(40, 1), V1 = new UUID(40, 2), V2 = new UUID(40, 3);
    private static final LegacyItemResourceKey KEY = new LegacyItemResourceKey(TYPE, TYPE, EditorId.parse("foreign:gun"));
    @Test void settingsBelongToLockedVersionAndRemainImmutableAfterInputAndSelectionMutation() {
        Map<String, String> settings = new LinkedHashMap<>(Map.of("assetId", "foreign:gun"));
        ResourceVersion first = new ResourceVersion(V1, RESOURCE, IMPORTER, "1", settings, null, Set.of(), Set.of());
        settings.put("assetId", "foreign:changed");
        var catalog = new ResourceCatalog(Map.of(RESOURCE, new LogicalResource(RESOURCE, TYPE, "synthetic", V2)),
                Map.of(V1, first, V2, new ResourceVersion(V2, RESOURCE, IMPORTER, "2", Map.of("assetId", "foreign:new"), null, Set.of(), Set.of())), Map.of());
        Map<LegacyItemResourceKey, ResourceRef> choices = new LinkedHashMap<>(); choices.put(KEY, new ResourceRef(RESOURCE, V1));
        var selected = LegacyItemContext.from(catalog, choices).find(KEY).orElseThrow();
        choices.put(KEY, new ResourceRef(RESOURCE, V2));
        assertEquals(Map.of("assetId", "foreign:gun"), selected.settings());
        assertSame(first.settings(), selected.settings(), "Binding shares the already frozen version settings");
        assertEquals(Map.of("assetId", "foreign:new"), LegacyItemContext.from(catalog, choices).find(KEY).orElseThrow().settings());
        assertThrows(UnsupportedOperationException.class, () -> selected.settings().put("assetId", "other:gun"));
        assertThrows(UnsupportedOperationException.class, () -> selected.settings().entrySet().iterator().next().setValue("other:gun"));
    }
    @Test void oldConstructorAndNewNoticeListAreDefensiveAndTyped() {
        byte[] bytes = {1, 2};
        assertTrue(new LegacyItemEncoding(TYPE, TYPE, 1, bytes).notices().isEmpty());
        List<Problem> notices = new ArrayList<>(List.of(notice(Problem.Severity.INFO), notice(Problem.Severity.WARNING)));
        var encoded = new LegacyItemEncoding(TYPE, TYPE, 1, bytes, notices); notices.clear(); bytes[0] = 9;
        assertEquals(2, encoded.notices().size()); assertArrayEquals(new byte[] {1, 2}, encoded.bytes());
        encoded.bytes()[0] = 8; assertEquals(1, encoded.bytes()[0]);
        assertThrows(UnsupportedOperationException.class, () -> encoded.notices().clear());
        assertThrows(NullPointerException.class, () -> new LegacyItemEncoding(TYPE, TYPE, 1, bytes, null));
        List<Problem> withNull = new ArrayList<>(); withNull.add(null);
        assertThrows(NullPointerException.class, () -> new LegacyItemEncoding(TYPE, TYPE, 1, bytes, withNull));
        assertThrows(IllegalArgumentException.class, () -> new LegacyItemEncoding(TYPE, TYPE, 1, bytes, List.of(notice(Problem.Severity.ERROR))));
        assertThrows(IllegalArgumentException.class, () -> new LegacyItemEncoding(TYPE, TYPE, 1, bytes,
                List.of(new Problem(Problem.Severity.INFO, TYPE, "extension:/x", new TextContent.Literal("untranslated")))));
        assertEquals(256, LegacyItemEncoding.MAX_NOTICES);
        assertEquals(256, new LegacyItemEncoding(TYPE, TYPE, 1, bytes, Collections.nCopies(256, notice(Problem.Severity.WARNING))).notices().size());
    }
    @Test void noticeBudgetPrecedesTraversalAndByteValidation() {
        List<Problem> hostile = new AbstractList<>() {
            public int size() { return 257; }
            public Problem get(int index) { throw new AssertionError("traversed over-budget notices"); }
        };
        assertThrows(IllegalArgumentException.class, () -> new LegacyItemEncoding(TYPE, TYPE, 1, null, hostile));
        assertThrows(IllegalArgumentException.class, () -> new LegacyItemEncoding(TYPE, TYPE, 1, new byte[8 * 1024 * 1024 + 1], List.of()));
    }
    private static Problem notice(Problem.Severity severity) {
        return new Problem(severity, TYPE, "extension:/x", new TextContent.Message("test.notice", List.of(new TextContent.LiteralArgument("asset"))));
    }
}
